package jp.kota.gigaburn

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * DownloadService のセッション状態の置き場（監査D2で分離）。
 * 以前は DownloadService の companion object に @Volatile が約20個並んでいた。
 * 状態の読み書きはここへ集約し、DownloadService はエンジン本体に専念する。
 * 定数（ACTION_* / MODE_* / STATUS_* 等）と表示解決（statusLabel）は DownloadService 側に残す。
 * ライブグラフ用サンプルもここで保持する（監査D1：以前は Activity 側と二重管理だった）。
 */
object SessionState {

    val downloadedBytes = AtomicLong(0)
    @Volatile var targetBytes: Long = 0
    @Volatile var isRunning: Boolean = false
    @Volatile var isComplete: Boolean = false
    @Volatile var isPausedWifi: Boolean = false
    @Volatile var statusCode: Int = DownloadService.STATUS_IDLE
    @Volatile var sourceName: String = "-"
    @Volatile var speedBps: Double = 0.0 // bytes/sec（平滑化済み）
    @Volatile var startedAtElapsed: Long = 0 // SystemClock.elapsedRealtime()
    @Volatile var finishedElapsedMs: Long = 0
    @Volatile var sessionMode: Int = DownloadService.MODE_NONE
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

    // ---- ライブグラフ用サンプル（直近60秒分だけ保持） ----
    private const val LIVE_BUFFER_MAX = 60
    private val liveBuffer = ArrayDeque<Double>()

    /** ライブサンプルを1件追加する */
    @Synchronized
    fun addLiveSample(mbps: Double) {
        liveBuffer.addLast(mbps)
        while (liveBuffer.size > LIVE_BUFFER_MAX) liveBuffer.removeFirst()
    }

    /** ライブサンプルのスナップショット（古い順） */
    @Synchronized
    fun liveSamples(): List<Double> = liveBuffer.toList()

    /** セッション開始時の状態初期化（直近結果は前回分を保持する） */
    fun resetForSession(mode: Int) {
        downloadedBytes.set(0)
        speedBps = 0.0
        isComplete = false
        isPausedWifi = false
        statusCode = DownloadService.STATUS_FETCHING
        sourceName = "-"
        startedAtElapsed = SystemClock.elapsedRealtime()
        finishedElapsedMs = 0
        sessionMode = mode
        testElapsedSec = 0
        synchronized(this) { liveBuffer.clear() }
    }
}
