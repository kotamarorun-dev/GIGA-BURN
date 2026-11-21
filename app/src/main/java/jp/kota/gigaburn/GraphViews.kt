package jp.kota.gigaburn

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 統合速度グラフ（スピードタブ用）：旧LiveGraphViewと旧HistoryChartViewを1つに統合したView。
 * - LIVEモード: セッション実行中。直近60秒の1秒サンプルをローリング表示する。
 * - HISTORYモード: アイドル時。保存済み履歴の平均Mbpsの推移を表示する。
 * モード切替はMainActivity側がDownloadService.isRunningを見て行う。
 */
class SpeedGraphView(ctx: Context) : View(ctx) {

    enum class Mode { LIVE, HISTORY }

    var mode: Mode = Mode.HISTORY
        private set

    /** LIVEモード用：直近のMbpsサンプル */
    private var samples: List<Double> = emptyList()

    /** HISTORYモード用：古い順（左→右）に並んだレコード */
    private var records: List<HistoryStore.Record> = emptyList()

    private val liveLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(33, 150, 243); style = Paint.Style.STROKE; strokeWidth = 2.5f
    }
    private val liveFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 33, 150, 243); style = Paint.Style.FILL
    }
    private val historyLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 175, 80); style = Paint.Style.STROKE; strokeWidth = 2.5f
    }
    private val historyPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 175, 80); style = Paint.Style.FILL
    }
    private val gridPaint = Paint().apply { color = Color.rgb(0x3A, 0x3A, 0x3C); strokeWidth = 1f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(189, 189, 189); textSize = 11f * resources.displayMetrics.scaledDensity
    }

    /**
     * テーマパレットのうちグラフに関わる色（背景・グリッド・軸テキスト）を適用する。
     * 線の色（ライブ=青／履歴=緑）はテーマによらず固定で、両テーマで判別できるようにしてある。
     */
    fun applyPalette(background: Int, grid: Int, axisText: Int) {
        setBackgroundColor(background)
        gridPaint.color = grid
        textPaint.color = axisText
        invalidate()
    }

    /** ライブ表示へ切り替え、サンプルを差し替える */
    fun showLive(newSamples: List<Double>) {
        mode = Mode.LIVE
        samples = newSamples
        invalidate()
    }

    /** 履歴表示へ切り替え、レコードを差し替える */
    fun showHistory(list: List<HistoryStore.Record>) {
        mode = Mode.HISTORY
        records = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (mode) {
            Mode.LIVE -> drawLive(canvas)
            Mode.HISTORY -> drawHistory(canvas)
        }
    }

    // ---------------- LIVEモード（旧LiveGraphView） ----------------

    /** Y軸の天井：最低100Mbps、最大値の1.1倍を50Mbps単位で切り上げ */
    private fun liveYCeil(): Double {
        val m = samples.maxOrNull() ?: 0.0
        val raw = max(100.0, m * 1.1)
        return Math.ceil(raw / 50.0) * 50.0
    }

    private fun drawLive(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val leftPad = 44f * resources.displayMetrics.density / 2 // 左の数値ラベル分
        val topPad = 20f
        val bottomPad = 18f
        val plotW = w - leftPad - 8f
        val plotH = h - topPad - bottomPad
        if (plotW <= 0 || plotH <= 0) return
        val yMax = liveYCeil()

        // グリッドとY軸ラベル（0 / 半分 / 最大）
        for (frac in listOf(0.0, 0.5, 1.0)) {
            val y = topPad + plotH - (plotH * frac).toFloat()
            canvas.drawLine(leftPad, y, leftPad + plotW, y, gridPaint)
            val label = (yMax * frac).toInt().toString()
            canvas.drawText(label, 2f, y + textPaint.textSize / 3, textPaint)
        }
        canvas.drawText("Mbps", leftPad, topPad - 6f, textPaint)
        canvas.drawText(context.getString(R.string.graph_last_60_sec), leftPad + plotW - 70f, h - 4f, textPaint)

        val cur = samples.lastOrNull() ?: 0.0
        val mx = samples.maxOrNull() ?: 0.0
        canvas.drawText(
            context.getString(R.string.graph_current_max, cur, mx),
            leftPad + 50f, topPad - 6f, textPaint
        )

        if (samples.size < 2) return
        val stepX = plotW / 59f // 直近60点で横幅いっぱい
        val startIdx = max(0, samples.size - 60)
        val visible = samples.subList(startIdx, samples.size)
        val path = Path()
        val fill = Path()
        visible.forEachIndexed { i, v ->
            val x = leftPad + stepX * i
            val y = topPad + plotH - (plotH * (v / yMax).coerceIn(0.0, 1.0)).toFloat()
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, topPad + plotH); fill.lineTo(x, y) }
            else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        val lastX = leftPad + stepX * (visible.size - 1)
        fill.lineTo(lastX, topPad + plotH)
        fill.close()
        canvas.drawPath(fill, liveFillPaint)
        canvas.drawPath(path, liveLinePaint)
    }

    // ---------------- HISTORYモード（旧HistoryChartView） ----------------

    private fun drawHistory(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val leftPad = 44f * resources.displayMetrics.density / 2
        val topPad = 20f
        val bottomPad = 30f
        val plotW = w - leftPad - 8f
        val plotH = h - topPad - bottomPad
        if (plotW <= 0 || plotH <= 0) return

        canvas.drawText(context.getString(R.string.graph_avg_trend), leftPad, topPad - 6f, textPaint)
        if (records.isEmpty()) {
            canvas.drawText(context.getString(R.string.graph_no_history), leftPad, topPad + plotH / 2, textPaint)
            return
        }
        val rawMax = max(100.0, (records.maxOf { it.avgMbps }) * 1.1)
        val yMax = Math.ceil(rawMax / 50.0) * 50.0

        for (frac in listOf(0.0, 0.5, 1.0)) {
            val y = topPad + plotH - (plotH * frac).toFloat()
            canvas.drawLine(leftPad, y, leftPad + plotW, y, gridPaint)
            canvas.drawText((yMax * frac).toInt().toString(), 2f, y + textPaint.textSize / 3, textPaint)
        }

        val n = records.size
        val path = Path()
        records.forEachIndexed { i, r ->
            val x = if (n == 1) leftPad + plotW / 2
            else leftPad + plotW * i / (n - 1)
            val y = topPad + plotH - (plotH * (r.avgMbps / yMax).coerceIn(0.0, 1.0)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            canvas.drawCircle(x, y, 3.5f, historyPointPaint)
        }
        if (n > 1) canvas.drawPath(path, historyLinePaint)

        // X軸：最初と最後の日時（ロケール既定の書式）
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        val first = fmt.format(Date(records.first().timestampMs))
        val last = fmt.format(Date(records.last().timestampMs))
        canvas.drawText(first, leftPad, h - 8f, textPaint)
        val lastW = textPaint.measureText(last)
        canvas.drawText(last, leftPad + plotW - lastW, h - 8f, textPaint)
    }
}
