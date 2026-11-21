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

/** ライブ速度グラフ：直近のMbpsサンプルを折れ線で描く（MainActivity用）。 */
class LiveGraphView(ctx: Context) : View(ctx) {

    private var samples: List<Double> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(33, 150, 243); style = Paint.Style.STROKE; strokeWidth = 2.5f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 33, 150, 243); style = Paint.Style.FILL
    }
    private val gridPaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY; textSize = 11f * resources.displayMetrics.scaledDensity
    }

    fun update(newSamples: List<Double>) {
        samples = newSamples
        invalidate()
    }

    /** Y軸の天井：最低100Mbps、最大値の1.1倍を50Mbps単位で切り上げ */
    private fun yCeil(): Double {
        val m = samples.maxOrNull() ?: 0.0
        val raw = max(100.0, m * 1.1)
        return Math.ceil(raw / 50.0) * 50.0
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val leftPad = 44f * resources.displayMetrics.density / 2 // 左の数値ラベル分
        val topPad = 20f
        val bottomPad = 18f
        val plotW = w - leftPad - 8f
        val plotH = h - topPad - bottomPad
        if (plotW <= 0 || plotH <= 0) return
        val yMax = yCeil()

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
        canvas.drawPath(fill, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}

/** 履歴グラフ：保存済み履歴の平均Mbpsを時系列の折れ線で描く（HistoryActivity用）。 */
class HistoryChartView(ctx: Context) : View(ctx) {

    /** 古い順（左→右）に並んだレコード */
    private var records: List<HistoryStore.Record> = emptyList()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 175, 80); style = Paint.Style.STROKE; strokeWidth = 2.5f
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 175, 80); style = Paint.Style.FILL
    }
    private val gridPaint = Paint().apply { color = Color.LTGRAY; strokeWidth = 1f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY; textSize = 11f * resources.displayMetrics.scaledDensity
    }

    fun setRecords(list: List<HistoryStore.Record>) {
        records = list
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
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
            canvas.drawCircle(x, y, 3.5f, pointPaint)
        }
        if (n > 1) canvas.drawPath(path, linePaint)

        // X軸：最初と最後の日時（ロケール既定の書式）
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        val first = fmt.format(Date(records.first().timestampMs))
        val last = fmt.format(Date(records.last().timestampMs))
        canvas.drawText(first, leftPad, h - 8f, textPaint)
        val lastW = textPaint.measureText(last)
        canvas.drawText(last, leftPad + plotW - lastW, h - 8f, textPaint)
    }
}
