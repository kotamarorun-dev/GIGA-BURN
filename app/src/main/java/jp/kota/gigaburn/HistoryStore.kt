package jp.kota.gigaburn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * セッション履歴の保存（内部ストレージの JSON ファイル、依存ライブラリなし）。
 * 保存先: filesDir/history.json（新しい順に最大100件）
 *
 * type / source は保存データとしては言語中立ではない旧来の文字列（日本語）をそのまま保持し、
 * 表示時にロケールへ合わせて変換する（既存の保存データとの互換のため）。
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

    // ---------------- 表示用ローカライズ ----------------

    /** 保存値（種別）を現在のロケールの表示名へ変換する */
    fun displayType(ctx: Context, type: String): String = when (type) {
        TYPE_TEST, "speedtest" -> ctx.getString(R.string.type_speed_test)
        TYPE_BURN, "burn" -> ctx.getString(R.string.type_burn)
        else -> type
    }

    /** 保存値（接続先）を現在のロケールの表示名へ変換する。"Fast.com" はそのまま */
    fun displaySource(ctx: Context, source: String): String =
        if (source == DownloadService.SOURCE_FALLBACK) ctx.getString(R.string.source_fallback)
        else source

    // ---------------- 表示・共有用フォーマット ----------------

    /** ロケールに合わせた日時表記 */
    fun formatDateTime(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

    fun formatGb(bytes: Long): String = String.format("%.3f GB", bytes / 1_000_000_000.0)

    /** 履歴リスト表示・テキスト共有共通の1行表記 */
    fun recordLine(ctx: Context, r: Record): String = ctx.getString(
        R.string.record_line,
        formatDateTime(r.timestampMs),
        displayType(ctx, r.type),
        r.avgMbps,
        r.maxMbps,
        formatGb(r.bytes),
        r.seconds,
        displaySource(ctx, r.source)
    )

    /** テキスト共有用サマリ（チャットにそのまま貼れる形） */
    fun summaryText(ctx: Context, records: List<Record>): String {
        val sb = StringBuilder()
        sb.append(ctx.getString(R.string.summary_header, records.size)).append("\n")
        for (r in records) sb.append(recordLine(ctx, r)).append("\n")
        return sb.toString().trimEnd()
    }

    /** 1件分の共有テキスト（やや詳しい版） */
    fun singleResultText(ctx: Context, r: Record): String = ctx.getString(
        R.string.single_result_text,
        formatDateTime(r.timestampMs),
        displayType(ctx, r.type),
        r.avgMbps,
        r.maxMbps,
        formatGb(r.bytes),
        r.seconds,
        displaySource(ctx, r.source)
    )

    /** CSV（ヘッダ: timestamp,type,avg_mbps,max_mbps,gb,seconds,source）。言語中立フォーマット */
    fun csv(records: List<Record>): String {
        val sb = StringBuilder()
        sb.append("timestamp,type,avg_mbps,max_mbps,gb,seconds,source\n")
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        for (r in records) {
            // 未知の種別は displayType と揃えて原文のまま出す（監査B3）
            val typeEn = when (r.type) {
                TYPE_TEST, "speedtest" -> "speedtest"
                TYPE_BURN, "burn" -> "burn"
                else -> r.type
            }
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
