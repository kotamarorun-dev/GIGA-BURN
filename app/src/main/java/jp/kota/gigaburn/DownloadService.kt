package jp.kota.gigaburn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * ダウンロードエンジン本体のフォアグラウンドサービス。
 * バーン（目標GB到達で停止）とスピードテスト（10秒間計測）の2モードを持つ。
 * 状態は companion object 経由で MainActivity と共有する（プロトタイプ簡易実装）。
 * セッション終了時には HistoryStore へ履歴を1件保存する。
 * ステータスはコード（STATUS_*）で保持し、表示時に文字列リソースへ解決する（日英対応）。
 */
class DownloadService : Service() {

    companion object {
        const val ACTION_START = "jp.kota.gigaburn.START"
        const val ACTION_START_TEST = "jp.kota.gigaburn.START_TEST"
        const val ACTION_STOP = "jp.kota.gigaburn.STOP"
        const val EXTRA_TARGET_BYTES = "target_bytes"
        const val EXTRA_MOBILE_ONLY = "mobile_only"

        private const val CHANNEL_ID = "gigaburn_progress"
        private const val NOTIFICATION_ID = 1
        private const val THREAD_COUNT = 4
        private const val BUFFER_SIZE = 64 * 1024

        const val SOURCE_FASTCOM = "Fast.com"
        const val SOURCE_FALLBACK = "代替サーバー"

        const val MODE_NONE = 0
        const val MODE_BURN = 1
        const val MODE_TEST = 2

        // ---- ステータスコード（表示文言はリソースで解決） ----
        const val STATUS_IDLE = 0
        const val STATUS_FETCHING = 1
        const val STATUS_DOWNLOADING = 2
        const val STATUS_TESTING = 3
        const val STATUS_PAUSED_WIFI = 4
        const val STATUS_STOPPED = 5
        const val STATUS_TEST_COMPLETE = 6
        const val STATUS_BURN_COMPLETE = 7

        /** スピードテストの計測時間（秒）。先頭 TEST_RAMPUP_SEC 秒は助走として平均から除外 */
        const val TEST_DURATION_SEC = 10
        const val TEST_RAMPUP_SEC = 2

        // ---- Activity と共有する状態 ----
        val downloadedBytes = AtomicLong(0)
        @Volatile var targetBytes: Long = 0
        @Volatile var isRunning: Boolean = false
        @Volatile var isComplete: Boolean = false
        @Volatile var isPausedWifi: Boolean = false
        @Volatile var statusCode: Int = STATUS_IDLE
        @Volatile var sourceName: String = "-"
        @Volatile var speedBps: Double = 0.0 // bytes/sec（平滑化済み）
        @Volatile var startedAtElapsed: Long = 0 // SystemClock.elapsedRealtime()
        @Volatile var finishedElapsedMs: Long = 0
        @Volatile var sessionMode: Int = MODE_NONE
        @Volatile var testElapsedSec: Int = 0 // スピードテストの有効経過秒（Wi-Fi停止中は進まない）

        // ---- 直近セッションの結果（結果表示・共有用） ----
        @Volatile var hasLastResult: Boolean = false
        @Volatile var lastResultType: String = ""
        @Volatile var lastResultAvgMbps: Double = 0.0
        @Volatile var lastResultMaxMbps: Double = 0.0
        @Volatile var lastResultBytes: Long = 0
        @Volatile var lastResultSeconds: Long = 0
        @Volatile var lastResultSource: String = "-"
        @Volatile var lastResultTimestampMs: Long = 0

        private val FALLBACK_URLS = listOf(
            "https://ash-speed.hetzner.com/10GB.bin",
            "https://speed.cloudflare.com/__down?bytes=1073741824"
        )

        private const val FASTCOM_API =
            "https://api.fast.com/netflix/speedtest/v2?https=true" +
            "&token=YXNkZmFzZGxmbnNkYWZoYXNkZmhrYWxm&urlCount=5"

        fun formatGB(bytes: Long): String = String.format("%.3f GB", bytes / 1_000_000_000.0)
        fun formatMbps(bps: Double): String = String.format("%.1f Mbps", bps * 8.0 / 1_000_000.0)

        /** 現在のステータスコードを、渡されたコンテキストのロケールで文言に解決する */
        fun statusLabel(ctx: Context): String = when (statusCode) {
            STATUS_FETCHING -> ctx.getString(R.string.status_fetching)
            STATUS_DOWNLOADING ->
                ctx.getString(R.string.status_downloading, HistoryStore.displaySource(ctx, sourceName))
            STATUS_TESTING ->
                ctx.getString(R.string.status_testing, HistoryStore.displaySource(ctx, sourceName))
            STATUS_PAUSED_WIFI -> ctx.getString(R.string.status_paused_wifi)
            STATUS_STOPPED -> ctx.getString(R.string.status_stopped)
            STATUS_TEST_COMPLETE -> ctx.getString(R.string.status_test_complete)
            STATUS_BURN_COMPLETE -> ctx.getString(R.string.status_burn_complete)
            else -> ctx.getString(R.string.status_idle)
        }
    }

    @Volatile private var stopRequested = false
    @Volatile private var mobileOnly = true
    @Volatile private var currentUrls: List<String> = emptyList()
    @Volatile private var usingFallback = false
    private val fallbackLock = Any()
    private val activeConnections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var workers: List<Thread> = emptyList()
    private var monitor: Thread? = null

    // ---- 計測用（監視スレッドからのみ触る） ----
    private val secondRatesMbps = mutableListOf<Double>() // 1秒ごとの実効レート（Wi-Fi停止中は記録しない）
    @Volatile private var activeSec = 0 // Wi-Fi停止を除いた有効秒数
    @Volatile private var recordSaved = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEngine(complete = false, status = STATUS_STOPPED)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (isRunning) return START_STICKY
                targetBytes = intent.getLongExtra(EXTRA_TARGET_BYTES, 10_000_000_000L)
                mobileOnly = intent.getBooleanExtra(EXTRA_MOBILE_ONLY, true)
                startEngine(MODE_BURN)
                return START_STICKY
            }
            ACTION_START_TEST -> {
                if (isRunning) return START_STICKY
                mobileOnly = intent.getBooleanExtra(EXTRA_MOBILE_ONLY, true)
                targetBytes = Long.MAX_VALUE // テストはGB目標ではなく時間で止める
                startEngine(MODE_TEST)
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun startEngine(mode: Int) {
        sessionMode = mode
        downloadedBytes.set(0)
        speedBps = 0.0
        isComplete = false
        isPausedWifi = false
        stopRequested = false
        usingFallback = false
        currentUrls = emptyList()
        sourceName = "-"
        statusCode = STATUS_FETCHING
        startedAtElapsed = SystemClock.elapsedRealtime()
        finishedElapsedMs = 0
        secondRatesMbps.clear()
        activeSec = 0
        testElapsedSec = 0
        recordSaved = false
        isRunning = true

        createChannel()
        startForegroundWith(buildNotification(statusLabel(this), 0))

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gigaburn:download")
            .apply { acquire(12 * 60 * 60 * 1000L) }

        // 接続先の解決（Fast.com → ダメなら代替）を別スレッドで
        thread(name = "gigaburn-resolver") {
            val fastUrls = fetchFastComUrls()
            if (fastUrls.isNotEmpty()) {
                currentUrls = fastUrls
                sourceName = SOURCE_FASTCOM
            } else {
                switchToFallback()
            }
            if (!stopRequested) {
                statusCode = if (sessionMode == MODE_TEST) STATUS_TESTING else STATUS_DOWNLOADING
            }
            workers = (0 until THREAD_COUNT).map { idx ->
                thread(name = "gigaburn-dl-$idx") { downloadLoop(idx) }
            }
        }
        monitor = thread(name = "gigaburn-monitor") { monitorLoop() }
    }

    // ---------------- ダウンロード本体 ----------------

    private fun downloadLoop(index: Int) {
        val buffer = ByteArray(BUFFER_SIZE)
        var consecutiveFailures = 0
        var roundRobin = index
        while (!stopRequested && downloadedBytes.get() < targetBytes) {
            // Wi-Fi 一時停止中は読まずに待つ
            while (isPausedWifi && !stopRequested) {
                try { Thread.sleep(500) } catch (e: InterruptedException) { return }
            }
            if (stopRequested) break
            val urls = currentUrls
            if (urls.isEmpty()) {
                try { Thread.sleep(300) } catch (e: InterruptedException) { return }
                continue
            }
            val base = urls[roundRobin % urls.size]
            roundRobin++
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(withCacheBuster(base)).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "GigaBurn/0.4 (Android prototype)")
                }
                activeConnections.add(conn)
                conn.connect()
                if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
                val input: InputStream = conn.inputStream
                var gotBytesThisConnection = false
                while (!stopRequested && downloadedBytes.get() < targetBytes) {
                    if (isPausedWifi) break // 一旦切って、再開時に繋ぎ直す
                    val n = input.read(buffer) // 読んだデータは保存せず捨てる
                    if (n == -1) break
                    if (n > 0) {
                        downloadedBytes.addAndGet(n.toLong())
                        gotBytesThisConnection = true
                    }
                }
                consecutiveFailures = if (gotBytesThisConnection) 0 else consecutiveFailures + 1
            } catch (e: Exception) {
                consecutiveFailures++
            } finally {
                if (conn != null) activeConnections.remove(conn)
                try { conn?.disconnect() } catch (e: Exception) { /* ignore */ }
            }
            // Fast.com の URL が繰り返し失敗する場合は代替サーバーへ切替
            if (!usingFallback && consecutiveFailures >= 5) {
                switchToFallback()
                consecutiveFailures = 0
            }
            if (consecutiveFailures > 0) {
                try { Thread.sleep(500) } catch (e: InterruptedException) { return }
            }
        }
    }

    private fun withCacheBuster(url: String): String {
        // キャッシュで実通信が起きない事態を避けるため、毎回クエリを変える
        val sep = if (url.contains("?")) "&" else "?"
        return "$url${sep}cb=${System.nanoTime()}-${(Math.random() * 1e9).toLong()}"
    }

    private fun switchToFallback() {
        synchronized(fallbackLock) {
            if (usingFallback) return
            usingFallback = true
            currentUrls = FALLBACK_URLS
            sourceName = SOURCE_FALLBACK
            if (isRunning && !isComplete) {
                statusCode = if (sessionMode == MODE_TEST) STATUS_TESTING else STATUS_DOWNLOADING
            }
        }
    }

    /** Fast.com 非公式 API から Netflix の計測用 URL 一覧を取得する。失敗時は空リスト。 */
    private fun fetchFastComUrls(): List<String> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(FASTCOM_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "GigaBurn/0.4 (Android prototype)")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val urls = mutableListOf<String>()
            val trimmed = text.trim()
            if (trimmed.startsWith("{")) {
                val targets = JSONObject(trimmed).optJSONArray("targets") ?: JSONArray()
                for (i in 0 until targets.length()) {
                    val u = targets.optJSONObject(i)?.optString("url") ?: ""
                    if (u.isNotEmpty()) urls.add(u)
                }
            } else if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val u = arr.optJSONObject(i)?.optString("url") ?: ""
                    if (u.isNotEmpty()) urls.add(u)
                }
            }
            urls
        } catch (e: Exception) {
            emptyList()
        } finally {
            try { conn?.disconnect() } catch (e: Exception) { /* ignore */ }
        }
    }

    // ---------------- 監視（速度・Wi-Fi・完了判定・通知・計測） ----------------

    private fun monitorLoop() {
        var lastBytes = downloadedBytes.get()
        var lastTime = SystemClock.elapsedRealtime()
        while (!stopRequested) {
            try { Thread.sleep(1000) } catch (e: InterruptedException) { break }

            // Wi-Fi チェック
            val wifi = isOnWifi()
            if (mobileOnly && wifi) {
                if (!isPausedWifi) {
                    isPausedWifi = true
                    statusCode = STATUS_PAUSED_WIFI
                }
            } else if (isPausedWifi) {
                isPausedWifi = false
                statusCode = if (sessionMode == MODE_TEST) STATUS_TESTING else STATUS_DOWNLOADING
            }

            // 速度（指数移動平均で平滑化）。一時停止中は 0 に寄せる
            val now = SystemClock.elapsedRealtime()
            val cur = downloadedBytes.get()
            val dt = (now - lastTime) / 1000.0
            if (dt > 0) {
                val instant = (cur - lastBytes) / dt
                speedBps = if (isPausedWifi) 0.0 else speedBps * 0.7 + instant * 0.3
            }

            // 計測用サンプル：Wi-Fi停止中は数えない（有効秒として扱わない）
            if (!isPausedWifi && dt > 0) {
                activeSec++
                if (sessionMode == MODE_TEST) testElapsedSec = activeSec
                secondRatesMbps.add((cur - lastBytes) / dt * 8.0 / 1_000_000.0)
            }
            lastBytes = cur
            lastTime = now

            // 完了判定
            if (sessionMode == MODE_TEST && activeSec >= TEST_DURATION_SEC) {
                stopEngine(complete = true, status = STATUS_TEST_COMPLETE)
                return
            }
            if (sessionMode == MODE_BURN && cur >= targetBytes && targetBytes > 0) {
                stopEngine(complete = true, status = STATUS_BURN_COMPLETE)
                return
            }

            val pct = currentProgressPct(cur)
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(statusLabel(this), pct))
        }
    }

    private fun currentProgressPct(cur: Long): Int = when (sessionMode) {
        MODE_TEST -> (testElapsedSec * 100 / TEST_DURATION_SEC).coerceIn(0, 100)
        else -> if (targetBytes > 0) (cur * 100 / targetBytes).toInt().coerceIn(0, 100) else 0
    }

    private fun isOnWifi(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * セッション結果を確定し、履歴に保存する（二重保存防止つき）。
     * - スピードテスト：先頭 TEST_RAMPUP_SEC 秒のサンプルを除いた残りの平均／最大
     * - バーン：全体の平均（総バイト÷実経過秒）と1秒サンプルの最大
     */
    private fun finalizeRecord() {
        if (recordSaved) return
        recordSaved = true
        val bytes = downloadedBytes.get()
        if (bytes <= 0) return
        val wallSec = if (startedAtElapsed > 0)
            (SystemClock.elapsedRealtime() - startedAtElapsed) / 1000 else 0
        val avg: Double
        val maxMbps: Double
        val type: String
        val recSeconds: Long
        if (sessionMode == MODE_TEST) {
            val usable = secondRatesMbps.drop(TEST_RAMPUP_SEC)
            val samples = if (usable.isNotEmpty()) usable else secondRatesMbps
            avg = if (samples.isNotEmpty()) samples.average() else 0.0
            maxMbps = samples.maxOrNull() ?: 0.0
            type = HistoryStore.TYPE_TEST
            recSeconds = activeSec.toLong()
        } else {
            avg = if (wallSec > 0) bytes * 8.0 / wallSec / 1_000_000.0 else 0.0
            maxMbps = secondRatesMbps.maxOrNull() ?: 0.0
            type = HistoryStore.TYPE_BURN
            recSeconds = wallSec
        }
        lastResultType = type
        lastResultAvgMbps = avg
        lastResultMaxMbps = maxMbps
        lastResultBytes = bytes
        lastResultSeconds = recSeconds
        lastResultSource = sourceName
        lastResultTimestampMs = System.currentTimeMillis()
        hasLastResult = true
        HistoryStore.add(
            applicationContext,
            HistoryStore.Record(lastResultTimestampMs, type, avg, maxMbps, bytes, recSeconds, sourceName)
        )
    }

    private fun stopEngine(complete: Boolean, status: Int) {
        if (!isRunning && !complete) return
        val wasRunning = isRunning
        stopRequested = true
        isComplete = complete
        statusCode = status
        finishedElapsedMs = if (startedAtElapsed > 0)
            SystemClock.elapsedRealtime() - startedAtElapsed else 0
        if (wasRunning) finalizeRecord()
        speedBps = 0.0
        isPausedWifi = false
        // ブロック中の読み取りを中断する
        for (c in activeConnections) try { c.disconnect() } catch (e: Exception) { /* ignore */ }
        try { wakeLock?.release() } catch (e: Exception) { /* ignore */ }
        wakeLock = null
        isRunning = false

        val pct = if (complete) 100 else currentProgressPct(downloadedBytes.get())
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(statusLabel(this), pct))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() {
        if (isRunning) finalizeRecord()
        stopRequested = true
        for (c in activeConnections) try { c.disconnect() } catch (e: Exception) { /* ignore */ }
        try { wakeLock?.release() } catch (e: Exception) { /* ignore */ }
        super.onDestroy()
    }

    // ---------------- 通知 ----------------

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String, pct: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val body = if (sessionMode == MODE_TEST) {
            getString(
                R.string.notif_body_test,
                testElapsedSec, TEST_DURATION_SEC, formatMbps(speedBps), formatGB(downloadedBytes.get())
            )
        } else {
            getString(
                R.string.notif_body_burn,
                formatGB(downloadedBytes.get()), formatGB(targetBytes), formatMbps(speedBps)
            )
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text_with_body, text, body))
            .setContentIntent(pi)
            .setOngoing(isRunning)
            .setOnlyAlertOnce(true)

        if (isRunning) {
            // 通知から直接止められるようにストップアクションを付ける
            val stopPi = PendingIntent.getService(
                this, 1,
                Intent(this, DownloadService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), stopPi)
        }

        if (isRunning && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            // Android 16（API 36）以降：Live Updates（昇格した継続中通知）として投稿する。
            val style = Notification.ProgressStyle()
                .setProgress(pct.coerceIn(0, 100))
            builder.setStyle(style)
            builder.requestPromotedOngoing()
            // ステータスバーのチップに出す短いテキスト（7文字以内目安）
            builder.setShortCriticalText("${pct.coerceIn(0, 100)}%")
        } else {
            // API 35 以下、または完了通知：従来の進捗バー付き通常通知
            builder.setProgress(100, pct.coerceIn(0, 100), sessionMode == MODE_NONE)
        }
        return builder.build()
    }

    /**
     * Live Updates への昇格を要求する。
     * Notification.Builder#setRequestPromotedOngoing は API 36.1 で追加されたため
     * compileSdk 36.0 の android.jar には存在しない。そこで公式ドキュメントにもある
     * EXTRA_REQUEST_PROMOTED_ONGOING（"android.requestPromotedOngoing"）を extras に入れ、
     * メソッドが存在する環境ではリフレクションでも呼ぶ二段構えにする。
     */
    private fun Notification.Builder.requestPromotedOngoing(): Notification.Builder {
        val extras = Bundle()
        extras.putBoolean("android.requestPromotedOngoing", true)
        addExtras(extras)
        try {
            val m = Notification.Builder::class.java.getMethod(
                "setRequestPromotedOngoing", Boolean::class.javaPrimitiveType
            )
            m.invoke(this, true)
        } catch (e: Exception) {
            // メソッドが無い環境では extras 方式のみで要求する
        }
        return this
    }

    private fun startForegroundWith(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
