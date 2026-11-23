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
 * モード切替はMainActivity側がDownloadServiceの実行状態を見て行う。
 * 余白・グリッド・Y軸まわりは両モード共通の実装に寄せてある（監査C1）。
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

    // ---------------- 共通（余白・グリッド・Y軸） ----------------

    /** プロット領域とY軸スケール */
    private class Plot(val leftX: Float, val topY: Float, val width: Float, val height: Float, val yAxisMaxMbps: Double)

    /** Y軸の天井：最低100Mbps、最大値の1.1倍を50Mbps単位で切り上げ */
    private fun yCeil(rawMaxMbps: Double): Double {
        val maxWithHeadroom = max(100.0, rawMaxMbps * 1.1)
        return Math.ceil(maxWithHeadroom / 50.0) * 50.0
    }

    /**
     * 余白を差し引いたプロット領域を計算する。描画できない大きさなら null。
     * 左余白（yAxisLabelWidth）は左の数値ラベル分、上余白・下余白は呼び出し側が決める
     * （タイトル帯や日付ラベルの有無で変わるため）。
     */
    private fun computePlot(topMargin: Float, bottomMargin: Float, rawMaxMbps: Double): Plot? {
        val yAxisLabelWidth = 44f * resources.displayMetrics.density / 2
        val plotWidth = width.toFloat() - yAxisLabelWidth - 8f
        val plotHeight = height.toFloat() - topMargin - bottomMargin
        if (plotWidth <= 0 || plotHeight <= 0) return null
        return Plot(yAxisLabelWidth, topMargin, plotWidth, plotHeight, yCeil(rawMaxMbps))
    }

    /** グリッド3本（0 / 半分 / 最大）とY軸ラベルを描く */
    private fun drawGridAndLabels(canvas: Canvas, plot: Plot) {
        for (gridFraction in listOf(0.0, 0.5, 1.0)) {
            val gridY = plot.topY + plot.height - (plot.height * gridFraction).toFloat()
            canvas.drawLine(plot.leftX, gridY, plot.leftX + plot.width, gridY, gridPaint)
            val yAxisLabel = (plot.yAxisMaxMbps * gridFraction).toInt().toString()
            canvas.drawText(yAxisLabel, 2f, gridY + textPaint.textSize / 3, textPaint)
        }
    }

    /** 値 → プロット内のY座標 */
    private fun plotY(plot: Plot, mbps: Double): Float =
        plot.topY + plot.height - (plot.height * (mbps / plot.yAxisMaxMbps).coerceIn(0.0, 1.0)).toFloat()

    // ---------------- LIVEモード ----------------

    private fun drawLive(canvas: Canvas) {
        // タイトル帯（現在／最大）はテキスト実寸基準で確保する（固定pxだと上端で見切れる）
        val titleBaselineY = textPaint.textSize
        val titleBandHeight = titleBaselineY + 10f
        val bottomLabelHeight = textPaint.textSize + 8f
        val plot = computePlot(titleBandHeight, bottomLabelHeight, samples.maxOrNull() ?: 0.0) ?: return

        drawGridAndLabels(canvas, plot)

        val currentMbps = samples.lastOrNull() ?: 0.0
        val sessionMaxMbps = samples.maxOrNull() ?: 0.0
        canvas.drawText(
            context.getString(R.string.graph_current_max, currentMbps, sessionMaxMbps),
            plot.leftX, titleBaselineY, textPaint
        )
        // 右下の「直近60秒」は実幅を測って右寄せする（固定オフセットだとロケールでずれる：監査A2）
        val rangeLabel = context.getString(R.string.graph_last_60_sec)
        canvas.drawText(rangeLabel, plot.leftX + plot.width - textPaint.measureText(rangeLabel), height - 4f, textPaint)

        if (samples.size < 2) return
        val sampleStepX = plot.width / 59f // 直近60点で横幅いっぱい
        val visibleStartIndex = max(0, samples.size - 60)
        val visibleSamples = samples.subList(visibleStartIndex, samples.size)
        val linePath = Path()
        val fillPath = Path()
        visibleSamples.forEachIndexed { i, sampleMbps ->
            val pointX = plot.leftX + sampleStepX * i
            val pointY = plotY(plot, sampleMbps)
            if (i == 0) { linePath.moveTo(pointX, pointY); fillPath.moveTo(pointX, plot.topY + plot.height); fillPath.lineTo(pointX, pointY) }
            else { linePath.lineTo(pointX, pointY); fillPath.lineTo(pointX, pointY) }
        }
        val lastPointX = plot.leftX + sampleStepX * (visibleSamples.size - 1)
        fillPath.lineTo(lastPointX, plot.topY + plot.height)
        fillPath.close()
        canvas.drawPath(fillPath, liveFillPaint)
        canvas.drawPath(linePath, liveLinePaint)
    }

    // ---------------- HISTORYモード ----------------

    private fun drawHistory(canvas: Canvas) {
        // キャンバス内のタイトルは廃止（上のモードラベルと冗長なため）。
        // 上余白は最大値ラベルが切れない分だけ確保する
        val topLabelMargin = textPaint.textSize * 0.9f
        val dateLabelHeight = textPaint.textSize + 10f
        val rawMaxMbps = if (records.isEmpty()) 0.0 else records.maxOf { it.avgMbps }
        val plot = computePlot(topLabelMargin, dateLabelHeight, rawMaxMbps) ?: return

        if (records.isEmpty()) {
            canvas.drawText(context.getString(R.string.graph_no_history), plot.leftX, topLabelMargin + plot.height / 2, textPaint)
            return
        }

        drawGridAndLabels(canvas, plot)

        val recordCount = records.size
        val linePath = Path()
        records.forEachIndexed { i, record ->
            val pointX = if (recordCount == 1) plot.leftX + plot.width / 2
            else plot.leftX + plot.width * i / (recordCount - 1)
            val pointY = plotY(plot, record.avgMbps)
            if (i == 0) linePath.moveTo(pointX, pointY) else linePath.lineTo(pointX, pointY)
            canvas.drawCircle(pointX, pointY, 3.5f, historyPointPaint)
        }
        if (recordCount > 1) canvas.drawPath(linePath, historyLinePaint)

        // X軸：最初と最後の日時（ロケール既定の書式）
        val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        val firstDateLabel = dateFormat.format(Date(records.first().timestampMs))
        val lastDateLabel = dateFormat.format(Date(records.last().timestampMs))
        canvas.drawText(firstDateLabel, plot.leftX, height - 8f, textPaint)
        val lastLabelWidth = textPaint.measureText(lastDateLabel)
        canvas.drawText(lastDateLabel, plot.leftX + plot.width - lastLabelWidth, height - 8f, textPaint)
    }
}
