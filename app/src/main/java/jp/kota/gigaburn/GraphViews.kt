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
    private class Plot(val left: Float, val top: Float, val w: Float, val h: Float, val yMax: Double)

    /** Y軸の天井：最低100Mbps、最大値の1.1倍を50Mbps単位で切り上げ */
    private fun yCeil(rawMax: Double): Double {
        val raw = max(100.0, rawMax * 1.1)
        return Math.ceil(raw / 50.0) * 50.0
    }

    /**
     * 余白を差し引いたプロット領域を計算する。描画できない大きさなら null。
     * leftPad は左の数値ラベル分、topPad / bottomPad は呼び出し側が決める
     * （タイトル帯や日付ラベルの有無で変わるため）。
     */
    private fun computePlot(topPad: Float, bottomPad: Float, rawMax: Double): Plot? {
        val leftPad = 44f * resources.displayMetrics.density / 2
        val plotW = width.toFloat() - leftPad - 8f
        val plotH = height.toFloat() - topPad - bottomPad
        if (plotW <= 0 || plotH <= 0) return null
        return Plot(leftPad, topPad, plotW, plotH, yCeil(rawMax))
    }

    /** グリッド3本（0 / 半分 / 最大）とY軸ラベルを描く */
    private fun drawGridAndLabels(canvas: Canvas, plot: Plot) {
        for (frac in listOf(0.0, 0.5, 1.0)) {
            val y = plot.top + plot.h - (plot.h * frac).toFloat()
            canvas.drawLine(plot.left, y, plot.left + plot.w, y, gridPaint)
            val label = (plot.yMax * frac).toInt().toString()
            canvas.drawText(label, 2f, y + textPaint.textSize / 3, textPaint)
        }
    }

    /** 値 → プロット内のY座標 */
    private fun plotY(plot: Plot, v: Double): Float =
        plot.top + plot.h - (plot.h * (v / plot.yMax).coerceIn(0.0, 1.0)).toFloat()

    // ---------------- LIVEモード ----------------

    private fun drawLive(canvas: Canvas) {
        // タイトル帯（現在／最大）はテキスト実寸基準で確保する（固定pxだと上端で見切れる）
        val titleBaseline = textPaint.textSize
        val topPad = titleBaseline + 10f
        val bottomPad = textPaint.textSize + 8f
        val plot = computePlot(topPad, bottomPad, samples.maxOrNull() ?: 0.0) ?: return

        drawGridAndLabels(canvas, plot)

        val cur = samples.lastOrNull() ?: 0.0
        val mx = samples.maxOrNull() ?: 0.0
        canvas.drawText(
            context.getString(R.string.graph_current_max, cur, mx),
            plot.left, titleBaseline, textPaint
        )
        // 右下の「直近60秒」は実幅を測って右寄せする（固定オフセットだとロケールでずれる：監査A2）
        val rangeLabel = context.getString(R.string.graph_last_60_sec)
        canvas.drawText(rangeLabel, plot.left + plot.w - textPaint.measureText(rangeLabel), height - 4f, textPaint)

        if (samples.size < 2) return
        val stepX = plot.w / 59f // 直近60点で横幅いっぱい
        val startIdx = max(0, samples.size - 60)
        val visible = samples.subList(startIdx, samples.size)
        val path = Path()
        val fill = Path()
        visible.forEachIndexed { i, v ->
            val x = plot.left + stepX * i
            val y = plotY(plot, v)
            if (i == 0) { path.moveTo(x, y); fill.moveTo(x, plot.top + plot.h); fill.lineTo(x, y) }
            else { path.lineTo(x, y); fill.lineTo(x, y) }
        }
        val lastX = plot.left + stepX * (visible.size - 1)
        fill.lineTo(lastX, plot.top + plot.h)
        fill.close()
        canvas.drawPath(fill, liveFillPaint)
        canvas.drawPath(path, liveLinePaint)
    }

    // ---------------- HISTORYモード ----------------

    private fun drawHistory(canvas: Canvas) {
        // キャンバス内のタイトルは廃止（上のモードラベルと冗長なため）。
        // 上余白は最大値ラベルが切れない分だけ確保する
        val topPad = textPaint.textSize * 0.9f
        val bottomPad = textPaint.textSize + 10f
        val rawMax = if (records.isEmpty()) 0.0 else records.maxOf { it.avgMbps }
        val plot = computePlot(topPad, bottomPad, rawMax) ?: return

        if (records.isEmpty()) {
            canvas.drawText(context.getString(R.string.graph_no_history), plot.left, topPad + plot.h / 2, textPaint)
            return
        }

        drawGridAndLabels(canvas, plot)

        val n = records.size
        val path = Path()
        records.forEachIndexed { i, r ->
            val x = if (n == 1) plot.left + plot.w / 2
            else plot.left + plot.w * i / (n - 1)
            val y = plotY(plot, r.avgMbps)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            canvas.drawCircle(x, y, 3.5f, historyPointPaint)
        }
        if (n > 1) canvas.drawPath(path, historyLinePaint)

        // X軸：最初と最後の日時（ロケール既定の書式）
        val fmt = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
        val first = fmt.format(Date(records.first().timestampMs))
        val last = fmt.format(Date(records.last().timestampMs))
        canvas.drawText(first, plot.left, height - 8f, textPaint)
        val lastW = textPaint.measureText(last)
        canvas.drawText(last, plot.left + plot.w - lastW, height - 8f, textPaint)
    }
}
