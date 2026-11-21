package jp.kota.gigaburn

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * メイン画面。ボトムバーで「ホーム / スピード / 設定」の3画面を切り替える単一Activity構成。
 * - ホーム: バーンの操作と進捗、直近結果と共有
 * - スピード: スピードテスト、統合グラフ（実行中はライブ／アイドル時は履歴推移）、
 *   履歴の共有（テキスト／CSV）・消去、履歴一覧
 * - 設定: 当面はLive Updatesの許可状態のみ（セクション形式で拡張可能に）
 * ダウンロードはフォアグラウンドサービス側で動くため、タブ切替では中断されない。
 */
class MainActivity : Activity() {

    companion object {
        private const val TAB_HOME = 0
        private const val TAB_SPEED = 1
        private const val TAB_SETTINGS = 2
        private const val STATE_TAB = "selected_tab"

        // ボトムバーの配色（v0.6で最適化）
        private val BAR_SURFACE = Color.rgb(0x1C, 0x1C, 0x1E) // 純黒のコンテンツ領域と分ける面色
        private val TAB_SELECTED = Color.rgb(33, 150, 243) // 選択中タブの青
        private val TAB_UNSELECTED = Color.rgb(189, 189, 189) // #BDBDBD：未選択でも読める明るいグレー
    }

    // ---- ホーム画面のView ----
    private lateinit var inputGB: EditText
    private lateinit var mobileOnlyCheck: CheckBox
    private lateinit var startStopButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var downloadedText: TextView
    private lateinit var speedText: TextView
    private lateinit var elapsedText: TextView
    private lateinit var etaText: TextView
    private lateinit var statusTextView: TextView
    private lateinit var resultText: TextView
    private lateinit var shareResultButton: Button

    // ---- スピード画面のView ----
    private lateinit var speedTestButton: Button
    private lateinit var speedTestResultText: TextView
    private lateinit var graphModeLabel: TextView
    private lateinit var speedGraph: SpeedGraphView
    private lateinit var shareTextBtn: Button
    private lateinit var shareCsvBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var historyListLayout: LinearLayout

    // ---- 設定画面のView ----
    private lateinit var liveUpdateText: TextView
    private lateinit var liveUpdateButton: Button

    // ---- 画面切替まわり ----
    private lateinit var homePage: View
    private lateinit var speedPage: LinearLayout
    private lateinit var settingsPage: View

    /** ボトムバーの1タブ分（クリック可能なコンテナ＋アイコン＋ラベル） */
    private class TabViews(val container: LinearLayout, val icon: TextView, val label: TextView)

    private val tabViews = mutableListOf<TabViews>()
    private var selectedTab = TAB_HOME

    // ---- スピード画面の履歴状態 ----
    private var historyRecords: List<HistoryStore.Record> = emptyList()

    // 統合グラフ（ライブ表示用）：1秒ごとに採取したMbpsサンプル（停止後も保持し、開始時にリセット）
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
        if (!running && wasRunning && selectedTab == TAB_SPEED) {
            refreshSpeedPage() // スピードタブ表示中にセッションが終わったら一覧・グラフを最新化
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
        if (savedInstanceState != null) {
            selectedTab = savedInstanceState.getInt(STATE_TAB, TAB_HOME)
        }
        buildUi()
        showTab(selectedTab)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, selectedTab)
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
        if (::liveUpdateText.isInitialized) refreshLiveUpdateStatus()
        if (selectedTab == TAB_SPEED) refreshSpeedPage()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ================================================================
    // 画面構築
    // ================================================================

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // コンテンツ領域（3画面を重ねて表示切替）
        val content = FrameLayout(this)
        homePage = buildHomePage()
        speedPage = buildSpeedPage()
        settingsPage = buildSettingsPage()
        content.addView(homePage)
        content.addView(speedPage)
        content.addView(settingsPage)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // ボトムバー（常に表示）。少し浮かせた濃い面色でコンテンツ領域（純黒）と分離する。
        val divider = View(this).apply { setBackgroundColor(Color.rgb(189, 189, 189)) }
        root.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))

        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BAR_SURFACE)
            // ジェスチャーバーのピルと重ならないよう、ナビゲーションバーの下インセットを
            // バー自身の下パディングにする。バーの背景色はインセット領域まで伸び、
            // ボタン（コンテンツ）だけがインセット分持ち上がる。
            setOnApplyWindowInsetsListener { v, insets ->
                val bottomInset = if (Build.VERSION.SDK_INT >= 30) {
                    insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                } else {
                    @Suppress("DEPRECATION") insets.systemWindowInsetBottom
                }
                v.setPadding(0, 0, 0, bottomInset)
                insets
            }
        }
        val tabs = listOf(
            Triple("⌂", R.string.tab_home, TAB_HOME),
            Triple("⚡︎", R.string.tab_speed, TAB_SPEED),
            Triple("⚙", R.string.tab_settings, TAB_SETTINGS)
        )
        for ((glyph, labelRes, index) in tabs) {
            // アイコンとラベルを別々のTextViewにして縦に並べる（v0.8）。
            // 以前のSpannableStringの2行テキストは固定高さのButton内で2行目が
            // はみ出してラベル下側が見切れたため、この構成に作り替えた。
            val icon = TextView(this).apply {
                text = glyph
                textSize = 22f
                gravity = Gravity.CENTER
                includeFontPadding = false
            }
            val label = TextView(this).apply {
                text = getString(labelRes)
                textSize = 12f
                gravity = Gravity.CENTER
                includeFontPadding = false
                maxLines = 1
            }
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = getString(labelRes)
                addView(icon, LinearLayout.LayoutParams(-2, -2))
                addView(label, LinearLayout.LayoutParams(-2, -2))
                setOnClickListener { showTab(index) }
            }
            tabViews.add(TabViews(tab, icon, label))
            bottomBar.addView(tab, LinearLayout.LayoutParams(0, dp(64), 1f))
        }
        root.addView(bottomBar, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
        bottomBar.requestApplyInsets()
        refreshSpeedPage()
        refreshUi()
        refreshLiveUpdateStatus()
    }

    /** タブの背景：押下時のリップル付き。選択中は青ベタ、未選択はバーの面色が透ける透明 */
    private fun tabBackground(selected: Boolean): RippleDrawable {
        val rippleColor = ColorStateList.valueOf(Color.argb(70, 255, 255, 255))
        val content = ColorDrawable(if (selected) TAB_SELECTED else Color.TRANSPARENT)
        return RippleDrawable(rippleColor, content, null)
    }

    /** タブ切替：対象ページだけを表示し、ボトムバーのハイライトを更新する */
    private fun showTab(index: Int) {
        selectedTab = index
        homePage.visibility = if (index == TAB_HOME) View.VISIBLE else View.GONE
        speedPage.visibility = if (index == TAB_SPEED) View.VISIBLE else View.GONE
        settingsPage.visibility = if (index == TAB_SETTINGS) View.VISIBLE else View.GONE
        tabViews.forEachIndexed { i, t ->
            val selected = i == index
            t.container.background = tabBackground(selected)
            val color = if (selected) Color.WHITE else TAB_UNSELECTED
            t.icon.setTextColor(color)
            t.label.setTextColor(color)
        }
        when (index) {
            TAB_SPEED -> refreshSpeedPage()
            TAB_SETTINGS -> refreshLiveUpdateStatus()
        }
    }

    // ================================================================
    // ホーム画面
    // ================================================================

    private fun buildHomePage(): View {
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

        // 直近結果と共有
        resultText = infoText().apply { textSize = 15f }
        root.addView(resultText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        shareResultButton = Button(this).apply {
            text = getString(R.string.share_result)
            isEnabled = false
            setOnClickListener { shareLastResult() }
        }
        root.addView(shareResultButton)

        return ScrollView(this).apply { addView(root) }
    }

    // ================================================================
    // スピード画面（スピードテスト＋統合グラフ＋履歴）
    // レイアウト順: [スピードテストボタン＋結果] → [統合グラフ＋共有/CSV/消去] → [履歴一覧]
    // ================================================================

    private fun buildSpeedPage(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.tab_speed)
            textSize = 24f
            gravity = Gravity.CENTER
        })

        // --- スピードテスト ---
        speedTestButton = Button(this).apply {
            text = getString(R.string.speed_test_button, DownloadService.TEST_DURATION_SEC)
            textSize = 16f
            setOnClickListener { onSpeedTest() }
        }
        root.addView(speedTestButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })

        speedTestResultText = infoText()
        root.addView(speedTestResultText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // --- 統合グラフ（実行中はライブ表示、アイドル時は履歴推移に自動切替） ---
        graphModeLabel = TextView(this).apply { textSize = 15f }
        root.addView(graphModeLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        speedGraph = SpeedGraphView(this)
        root.addView(speedGraph, LinearLayout.LayoutParams(-1, dp(190)).apply { topMargin = dp(4) })

        // --- グラフに付随する共有アクション：テキスト共有 / CSV共有 / 消去 ---
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shareTextBtn = Button(this).apply {
            text = getString(R.string.share)
            setOnClickListener { shareHistoryText() }
        }
        shareCsvBtn = Button(this).apply {
            text = getString(R.string.share_csv)
            setOnClickListener { shareHistoryCsv() }
        }
        clearBtn = Button(this).apply {
            text = getString(R.string.clear_history)
            setOnClickListener { confirmClearHistory() }
        }
        btnRow.addView(shareTextBtn, LinearLayout.LayoutParams(0, -2, 1f))
        btnRow.addView(shareCsvBtn, LinearLayout.LayoutParams(0, -2, 1f))
        btnRow.addView(clearBtn, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(btnRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // --- 履歴一覧（スクロール） ---
        historyListLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(historyListLayout) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        return root
    }

    /** スピード画面の動的部分（履歴一覧・共有ボタンの有効状態・アイドル時のグラフ）を最新化する */
    private fun refreshSpeedPage() {
        if (!::speedGraph.isInitialized) return
        historyRecords = HistoryStore.load(this)

        val hasRecords = historyRecords.isNotEmpty()
        shareTextBtn.isEnabled = hasRecords
        shareCsvBtn.isEnabled = hasRecords
        clearBtn.isEnabled = hasRecords

        historyListLayout.removeAllViews()
        if (historyRecords.isEmpty()) {
            historyListLayout.addView(TextView(this).apply {
                text = getString(R.string.history_empty)
                textSize = 14f
            })
        } else {
            for (r in historyRecords) {
                historyListLayout.addView(TextView(this).apply {
                    text = HistoryStore.recordLine(this@MainActivity, r)
                    textSize = 14f
                    setPadding(0, dp(6), 0, dp(6))
                })
            }
        }

        // アイドル時はグラフを履歴モードにする（実行中はrefreshUiがライブ表示を維持する）
        if (!DownloadService.isRunning) {
            graphModeLabel.text = getString(R.string.graph_history_label)
            speedGraph.showHistory(historyRecords.take(30).reversed())
        }
    }

    private fun shareHistoryText() {
        if (historyRecords.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, HistoryStore.summaryText(this@MainActivity, historyRecords))
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_history_subject))
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_history_chooser)))
    }

    private fun shareHistoryCsv() {
        if (historyRecords.isEmpty()) return
        try {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val csvFile = File(dir, "gigaburn_history.csv")
            csvFile.writeText(HistoryStore.csv(historyRecords))
            val uri = ShareFileProvider.uriFor(csvFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_history_csv_subject))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.share_history_csv_chooser)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.csv_failed_toast), Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmClearHistory() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_history))
            .setMessage(getString(R.string.clear_dialog_message, historyRecords.size))
            .setPositiveButton(getString(R.string.clear_confirm)) { _, _ ->
                HistoryStore.clear(this)
                Toast.makeText(this, getString(R.string.cleared_toast), Toast.LENGTH_SHORT).show()
                refreshSpeedPage()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    // ================================================================
    // 設定画面（セクション形式。項目は今後ここへ追加する）
    // ================================================================

    private fun buildSettingsPage(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.settings_title)
            textSize = 24f
            gravity = Gravity.CENTER
        })

        // --- セクション: Live Updates ---
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        section.addView(TextView(this).apply {
            text = getString(R.string.settings_section_live_updates)
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        liveUpdateText = infoText()
        section.addView(liveUpdateText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        liveUpdateButton = Button(this).apply {
            text = getString(R.string.live_updates_settings)
            setOnClickListener { openPromotedNotificationSettings() }
        }
        section.addView(liveUpdateButton)
        root.addView(section, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })

        root.addView(TextView(this).apply {
            text = getString(R.string.settings_note)
            textSize = 13f
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })

        return ScrollView(this).apply { addView(root) }
    }

    /** Live Updates（Android 16以降）の許可状態を表示する。API 35以下では通常通知になる旨を出す。 */
    private fun refreshLiveUpdateStatus() {
        if (!::liveUpdateText.isInitialized) return
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

    // ================================================================
    // ホーム画面の操作・表示更新
    // ================================================================

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
        // 「モバイル回線のみ」トグルはホーム画面にあるが、アプリ全体で共有する設定として読む
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

        // 統合グラフ：実行中はライブ（直近60秒）を表示し、終了したら履歴モードへ切り替える
        if (running) {
            graphModeLabel.text = getString(R.string.graph_live_label)
            speedGraph.showLive(liveSamples.takeLast(60))
        } else if (speedGraph.mode == SpeedGraphView.Mode.LIVE) {
            // セッション終了直後の1回だけ履歴モードへ（保存済みの最新履歴を読み直す）
            historyRecords = HistoryStore.load(this)
            graphModeLabel.text = getString(R.string.graph_history_label)
            speedGraph.showHistory(historyRecords.take(30).reversed())
        }

        // スピードテストの結果表示（スピードタブ上部）
        speedTestResultText.text = when {
            running && isTest -> getString(
                R.string.speed_test_running,
                DownloadService.testElapsedSec,
                DownloadService.TEST_DURATION_SEC
            )
            DownloadService.hasLastResult &&
                DownloadService.lastResultType == HistoryStore.TYPE_TEST -> getString(
                R.string.speed_test_result_line,
                DownloadService.lastResultAvgMbps,
                DownloadService.lastResultMaxMbps,
                DownloadService.formatGB(DownloadService.lastResultBytes),
                DownloadService.lastResultSeconds,
                HistoryStore.displaySource(this, DownloadService.lastResultSource)
            )
            else -> getString(R.string.speed_test_result_none)
        }

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
