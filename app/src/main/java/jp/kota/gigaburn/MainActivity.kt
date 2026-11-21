package jp.kota.gigaburn

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var inputGB: EditText
    private lateinit var mobileOnlyCheck: CheckBox
    private lateinit var startStopButton: Button
    private lateinit var speedTestButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var downloadedText: TextView
    private lateinit var speedText: TextView
    private lateinit var elapsedText: TextView
    private lateinit var etaText: TextView
    private lateinit var statusTextView: TextView
    private lateinit var resultText: TextView
    private lateinit var shareResultButton: Button
    private lateinit var liveGraph: LiveGraphView
    private lateinit var liveUpdateText: TextView
    private lateinit var liveUpdateButton: Button

    // ライブグラフ用：1秒ごとに採取したMbpsサンプル（停止後も保持し、開始時にリセット）
    private val liveSamples = mutableListOf<Double>()
    private var pollCount = 0
    private var wasRunning = false

    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            sampleIfNeeded()
            refreshUi()
            handler.postDelayed(this, 500)
        }
    }

    /** 500msポーリングのうち2回に1回（=1秒間隔）で速度サンプルを採取する */
    private fun sampleIfNeeded() {
        val running = DownloadService.isRunning
        if (running && !wasRunning) {
            liveSamples.clear() // セッション開始でリセット
        }
        wasRunning = running
        pollCount++
        if (running && pollCount % 2 == 0) {
            liveSamples.add(DownloadService.speedBps * 8.0 / 1_000_000.0)
            if (liveSamples.size > 600) liveSamples.removeAt(0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
        if (::liveUpdateText.isInitialized) refreshLiveUpdateStatus()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val sub = TextView(this).apply {
            text = getString(R.string.app_subtitle)
            textSize = 13f
            gravity = Gravity.CENTER
        }
        root.addView(sub)

        val inputLabel = TextView(this).apply { text = getString(R.string.target_label); textSize = 15f }
        root.addView(inputLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        inputGB = EditText(this).apply {
            setText("10")
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            textSize = 20f
        }
        root.addView(inputGB)

        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (gb in listOf(1, 10, 50, 100)) {
            val b = Button(this).apply {
                text = getString(R.string.preset_gb, gb)
                setOnClickListener { inputGB.setText(gb.toString()) }
            }
            presetRow.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(presetRow)

        mobileOnlyCheck = CheckBox(this).apply {
            text = getString(R.string.mobile_only)
            isChecked = true
            textSize = 15f
        }
        root.addView(mobileOnlyCheck, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        startStopButton = Button(this).apply {
            text = getString(R.string.start)
            textSize = 18f
            setOnClickListener { onStartStop() }
        }
        root.addView(startStopButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        speedTestButton = Button(this).apply {
            text = getString(R.string.speed_test_button, DownloadService.TEST_DURATION_SEC)
            textSize = 16f
            setOnClickListener { onSpeedTest() }
        }
        root.addView(speedTestButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
        }
        root.addView(progressBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        downloadedText = infoText()
        speedText = infoText()
        elapsedText = infoText()
        etaText = infoText()
        statusTextView = infoText().apply { textSize = 16f }
        root.addView(statusTextView)
        root.addView(downloadedText)
        root.addView(speedText)
        root.addView(elapsedText)
        root.addView(etaText)

        // ライブ速度グラフ
        val graphLabel = TextView(this).apply {
            text = getString(R.string.graph_live_label)
            textSize = 15f
        }
        root.addView(graphLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        liveGraph = LiveGraphView(this)
        root.addView(liveGraph, LinearLayout.LayoutParams(-1, dp(130)))

        // 直近結果と共有
        resultText = infoText().apply { textSize = 15f }
        root.addView(resultText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        shareResultButton = Button(this).apply {
            text = getString(R.string.share_result)
            isEnabled = false
            setOnClickListener { shareLastResult() }
        }
        root.addView(shareResultButton)

        val historyButton = Button(this).apply {
            text = getString(R.string.history_button)
            setOnClickListener { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) }
        }
        root.addView(historyButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        liveUpdateText = infoText()
        root.addView(liveUpdateText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        liveUpdateButton = Button(this).apply {
            text = getString(R.string.live_updates_settings)
            setOnClickListener { openPromotedNotificationSettings() }
        }
        root.addView(liveUpdateButton)

        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        refreshUi()
        refreshLiveUpdateStatus()
    }

    /** Live Updates（Android 16以降）の許可状態を表示する。API 35以下では通常通知になる旨を出す。 */
    private fun refreshLiveUpdateStatus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            liveUpdateText.text = getString(R.string.lu_unsupported)
            liveUpdateButton.isEnabled = false
            return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val allowed = try { nm.canPostPromotedNotifications() } catch (e: Exception) { false }
        liveUpdateText.text = if (allowed) {
            getString(R.string.lu_allowed)
        } else {
            getString(R.string.lu_not_allowed)
        }
        liveUpdateButton.isEnabled = true
    }

    /** 昇格通知の設定画面を開く。専用画面が無い環境ではアプリの通知設定へフォールバック。 */
    private fun openPromotedNotificationSettings() {
        // Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS（API 36.1以降の定数のため文字列で指定）
        val promoted = Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS").apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(promoted)
            return
        } catch (e: Exception) {
            // 専用設定画面が無い場合はアプリの通知設定を開く
        }
        try {
            startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
            )
        } catch (e: Exception) {
            // それもダメなら何もしない（状態表示のみ）
        }
    }

    private fun infoText(): TextView = TextView(this).apply { textSize = 15f }

    private fun onStartStop() {
        if (DownloadService.isRunning) {
            val i = Intent(this, DownloadService::class.java).apply { action = DownloadService.ACTION_STOP }
            startService(i)
            return
        }
        val gb = inputGB.text.toString().toDoubleOrNull()
        if (gb == null || gb <= 0) {
            statusTextView.text = getString(R.string.error_invalid_target)
            return
        }
        val target = (gb * 1_000_000_000.0).toLong()
        val i = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START
            putExtra(DownloadService.EXTRA_TARGET_BYTES, target)
            putExtra(DownloadService.EXTRA_MOBILE_ONLY, mobileOnlyCheck.isChecked)
        }
        startForegroundService(i)
    }

    private fun onSpeedTest() {
        if (DownloadService.isRunning) return
        val i = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START_TEST
            putExtra(DownloadService.EXTRA_MOBILE_ONLY, mobileOnlyCheck.isChecked)
        }
        startForegroundService(i)
    }

    private fun shareLastResult() {
        if (!DownloadService.hasLastResult) return
        val r = HistoryStore.Record(
            timestampMs = DownloadService.lastResultTimestampMs,
            type = DownloadService.lastResultType,
            avgMbps = DownloadService.lastResultAvgMbps,
            maxMbps = DownloadService.lastResultMaxMbps,
            bytes = DownloadService.lastResultBytes,
            seconds = DownloadService.lastResultSeconds,
            source = DownloadService.lastResultSource
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, HistoryStore.singleResultText(this@MainActivity, r))
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_result_subject))
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_result)))
    }

    private fun refreshUi() {
        val downloaded = DownloadService.downloadedBytes.get()
        val target = DownloadService.targetBytes
        val running = DownloadService.isRunning
        val isTest = DownloadService.sessionMode == DownloadService.MODE_TEST

        // バーンとスピードテストは同時実行不可：実行中は両ボタンを制御
        startStopButton.text = if (running) getString(R.string.stop) else getString(R.string.start)
        speedTestButton.isEnabled = !running
        inputGB.isEnabled = !running
        mobileOnlyCheck.isEnabled = !running

        val pctPermille = when {
            isTest -> (DownloadService.testElapsedSec * 1000 / DownloadService.TEST_DURATION_SEC)
                .coerceIn(0, 1000)
            target > 0 && target != Long.MAX_VALUE ->
                (downloaded * 1000 / target).toInt().coerceIn(0, 1000)
            else -> 0
        }
        progressBar.progress = pctPermille

        val statusLabel = DownloadService.statusLabel(this)
        statusTextView.text = if (DownloadService.sourceName != "-") {
            getString(
                R.string.status_with_source,
                statusLabel,
                HistoryStore.displaySource(this, DownloadService.sourceName)
            )
        } else {
            getString(R.string.status_plain, statusLabel)
        }
        downloadedText.text = if (isTest) {
            getString(
                R.string.downloaded_test,
                DownloadService.formatGB(downloaded),
                DownloadService.testElapsedSec,
                DownloadService.TEST_DURATION_SEC
            )
        } else if (target > 0 && target != Long.MAX_VALUE) {
            getString(
                R.string.downloaded_burn,
                DownloadService.formatGB(downloaded),
                DownloadService.formatGB(target),
                pctPermille / 10.0
            )
        } else {
            getString(R.string.downloaded_burn_plain, DownloadService.formatGB(downloaded))
        }
        speedText.text = getString(R.string.current_speed, DownloadService.formatMbps(DownloadService.speedBps))

        val elapsedMs = when {
            running && DownloadService.startedAtElapsed > 0 ->
                SystemClock.elapsedRealtime() - DownloadService.startedAtElapsed
            DownloadService.finishedElapsedMs > 0 -> DownloadService.finishedElapsedMs
            else -> 0
        }
        elapsedText.text = getString(R.string.elapsed_label, formatDuration(elapsedMs))

        val speed = DownloadService.speedBps
        etaText.text = when {
            DownloadService.isComplete -> getString(R.string.eta_done)
            running && isTest ->
                getString(R.string.eta_test, DownloadService.TEST_DURATION_SEC - DownloadService.testElapsedSec)
            running && speed > 0 && target > downloaded && target != Long.MAX_VALUE ->
                getString(R.string.eta_burn, formatDuration(((target - downloaded) / speed * 1000).toLong()))
            else -> getString(R.string.eta_none)
        }

        // ライブグラフ（直近60秒）
        liveGraph.update(liveSamples.takeLast(60))

        // 直近結果と共有ボタン
        if (DownloadService.hasLastResult && !running) {
            resultText.text = getString(
                R.string.last_result_line,
                HistoryStore.displayType(this, DownloadService.lastResultType),
                DownloadService.lastResultAvgMbps,
                DownloadService.lastResultMaxMbps,
                DownloadService.formatGB(DownloadService.lastResultBytes),
                DownloadService.lastResultSeconds,
                HistoryStore.displaySource(this, DownloadService.lastResultSource)
            )
            shareResultButton.isEnabled = true
        } else if (DownloadService.hasLastResult && running) {
            resultText.text = getString(R.string.last_result_running)
            shareResultButton.isEnabled = false
        } else {
            resultText.text = getString(R.string.last_result_none)
            shareResultButton.isEnabled = false
        }
    }

    private fun formatDuration(ms: Long): String {
        val sec = ms / 1000
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }
}
