package jp.kota.gigaburn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * セッション履歴の保存（内部ストレージの JSON ファイル、依存ライブラリなし）。
 * 保存先: filesDir/history.json（新しい順に最大100件）
 */
object HistoryStore {

    const val TYPE_TEST = "スピードテスト"
    const val TYPE_BURN = "バーン"

    data class Record(
        val timestampMs: Long,
        val type: String,
        val avgMbps: Double,
        val maxMbps: Double,
        val bytes: Long,
        val seconds: Long,
        val source: String
    )

    private const val FILE_NAME = "history.json"
    private const val MAX_RECORDS = 100

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    @Synchronized
    fun load(ctx: Context): List<Record> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<Record>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Record(
                        timestampMs = o.optLong("timestampMs"),
                        type = o.optString("type"),
                        avgMbps = o.optDouble("avgMbps"),
                        maxMbps = o.optDouble("maxMbps"),
                        bytes = o.optLong("bytes"),
                        seconds = o.optLong("seconds"),
                        source = o.optString("source")
                    )
                )
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun add(ctx: Context, record: Record) {
        val list = load(ctx).toMutableList()
        list.add(0, record) // 新しい順
        val capped = list.take(MAX_RECORDS)
        val arr = JSONArray()
        for (r in capped) {
            arr.put(
                JSONObject()
                    .put("timestampMs", r.timestampMs)
                    .put("type", r.type)
                    .put("avgMbps", r.avgMbps)
                    .put("maxMbps", r.maxMbps)
                    .put("bytes", r.bytes)
                    .put("seconds", r.seconds)
                    .put("source", r.source)
            )
        }
        try {
            file(ctx).writeText(arr.toString())
        } catch (e: Exception) {
            // 保存に失敗してもアプリ本体は止めない
        }
    }

    @Synchronized
    fun clear(ctx: Context) {
        try {
            file(ctx).delete()
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------------- 表示・共有用フォーマット ----------------

    fun formatDateTime(ms: Long): String =
        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(ms))

    fun formatGb(bytes: Long): String = String.format("%.3f GB", bytes / 1_000_000_000.0)

    /** 履歴リスト表示・テキスト共有共通の1行表記 */
    fun recordLine(r: Record): String =
        "${formatDateTime(r.timestampMs)} ${r.type} " +
            "平均 ${String.format("%.1f", r.avgMbps)} Mbps / 最大 ${String.format("%.1f", r.maxMbps)} Mbps " +
            "消費 ${formatGb(r.bytes)}（${r.seconds}秒）${r.source}"

    /** テキスト共有用サマリ（チャットにそのまま貼れる形） */
    fun summaryText(records: List<Record>): String {
        val sb = StringBuilder()
        sb.append("ギガバーン履歴（${records.size}件）\n")
        for (r in records) sb.append(recordLine(r)).append("\n")
        return sb.toString().trimEnd()
    }

    /** 1件分の共有テキスト（やや詳しい版） */
    fun singleResultText(r: Record): String =
        "ギガバーン結果\n" +
            "日時：${formatDateTime(r.timestampMs)}\n" +
            "種別：${r.type}\n" +
            "平均：${String.format("%.1f", r.avgMbps)} Mbps\n" +
            "最大：${String.format("%.1f", r.maxMbps)} Mbps\n" +
            "消費：${formatGb(r.bytes)}\n" +
            "時間：${r.seconds}秒\n" +
            "接続先：${r.source}"

    /** CSV（ヘッダ: timestamp,type,avg_mbps,max_mbps,gb,seconds,source） */
    fun csv(records: List<Record>): String {
        val sb = StringBuilder()
        sb.append("timestamp,type,avg_mbps,max_mbps,gb,seconds,source\n")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        for (r in records) {
            val typeEn = if (r.type == TYPE_TEST) "speedtest" else "burn"
            sb.append(iso.format(Date(r.timestampMs))).append(',')
                .append(typeEn).append(',')
                .append(String.format(Locale.US, "%.1f", r.avgMbps)).append(',')
                .append(String.format(Locale.US, "%.1f", r.maxMbps)).append(',')
                .append(String.format(Locale.US, "%.3f", r.bytes / 1_000_000_000.0)).append(',')
                .append(r.seconds).append(',')
                .append(r.source).append("\n")
        }
        return sb.toString()
    }
}
