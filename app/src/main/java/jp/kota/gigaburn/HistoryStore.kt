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
        val historyFile = file(ctx)
        if (!historyFile.exists()) return emptyList()
        return try {
            val jsonArray = JSONArray(historyFile.readText())
            val loadedRecords = mutableListOf<Record>()
            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.optJSONObject(i) ?: continue
                loadedRecords.add(
                    Record(
                        timestampMs = jsonObject.optLong("timestampMs"),
                        type = jsonObject.optString("type"),
                        avgMbps = jsonObject.optDouble("avgMbps"),
                        maxMbps = jsonObject.optDouble("maxMbps"),
                        bytes = jsonObject.optLong("bytes"),
                        seconds = jsonObject.optLong("seconds"),
                        source = jsonObject.optString("source")
                    )
                )
            }
            loadedRecords
        } catch (e: Exception) {
            // 壊れたファイルを残すと、次回の add が空リストから書き直して
            // 履歴が丸ごと消える。退避してから空として扱う。
            try {
                historyFile.renameTo(File(ctx.filesDir, "$FILE_NAME.corrupt-${System.currentTimeMillis()}"))
            } catch (e2: Exception) {
                // ignore
            }
            emptyList()
        }
    }

    @Synchronized
    fun add(ctx: Context, newRecord: Record) {
        val currentRecords = load(ctx).toMutableList()
        currentRecords.add(0, newRecord) // 新しい順
        val capped = currentRecords.take(MAX_RECORDS)
        val jsonArray = JSONArray()
        for (record in capped) {
            jsonArray.put(
                JSONObject()
                    .put("timestampMs", record.timestampMs)
                    .put("type", record.type)
                    .put("avgMbps", record.avgMbps)
                    .put("maxMbps", record.maxMbps)
                    .put("bytes", record.bytes)
                    .put("seconds", record.seconds)
                    .put("source", record.source)
            )
        }
        try {
            file(ctx).writeText(jsonArray.toString())
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

    /** 保存値（種別）を言語中立キーへ正規化する。表示変換とCSV出力で共用する */
    fun typeKey(type: String): String = when (type) {
        TYPE_TEST, "speedtest" -> "speedtest"
        TYPE_BURN, "burn" -> "burn"
        else -> type
    }

    /** 保存値（接続先）を言語中立キーへ正規化する（CSV用）。"Fast.com" はそのまま */
    fun sourceKey(source: String): String =
        if (source == DownloadService.SOURCE_FALLBACK) "fallback" else source

    /** 保存値（種別）を現在のロケールの表示名へ変換する */
    fun displayType(ctx: Context, type: String): String = when (typeKey(type)) {
        "speedtest" -> ctx.getString(R.string.type_speed_test)
        "burn" -> ctx.getString(R.string.type_burn)
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
    fun recordLine(ctx: Context, record: Record): String = ctx.getString(
        R.string.record_line,
        formatDateTime(record.timestampMs),
        displayType(ctx, record.type),
        record.avgMbps,
        record.maxMbps,
        formatGb(record.bytes),
        record.seconds,
        displaySource(ctx, record.source)
    )

    /** テキスト共有用サマリ（チャットにそのまま貼れる形） */
    fun summaryText(ctx: Context, records: List<Record>): String {
        val summaryBuilder = StringBuilder()
        summaryBuilder.append(ctx.getString(R.string.summary_header, records.size)).append("\n")
        for (record in records) summaryBuilder.append(recordLine(ctx, record)).append("\n")
        return summaryBuilder.toString().trimEnd()
    }

    /** 1件分の共有テキスト（やや詳しい版） */
    fun singleResultText(ctx: Context, record: Record): String = ctx.getString(
        R.string.single_result_text,
        formatDateTime(record.timestampMs),
        displayType(ctx, record.type),
        record.avgMbps,
        record.maxMbps,
        formatGb(record.bytes),
        record.seconds,
        displaySource(ctx, record.source)
    )

    /** CSV（ヘッダ: timestamp,type,avg_mbps,max_mbps,gb,seconds,source）。言語中立フォーマット */
    fun csv(records: List<Record>): String {
        val csvBuilder = StringBuilder()
        csvBuilder.append("timestamp,type,avg_mbps,max_mbps,gb,seconds,source\n")
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        for (record in records) {
            // 種別・接続先とも言語中立キーで出す（未知の値は原文のまま：監査B3）。
            // 以前は接続先だけ保存値（日本語）がそのまま出ており、ヘッダの
            // 「言語中立フォーマット」という約束と食い違っていた。
            csvBuilder.append(isoFormat.format(Date(record.timestampMs))).append(',')
                .append(typeKey(record.type)).append(',')
                .append(String.format(Locale.US, "%.1f", record.avgMbps)).append(',')
                .append(String.format(Locale.US, "%.1f", record.maxMbps)).append(',')
                .append(String.format(Locale.US, "%.3f", record.bytes / 1_000_000_000.0)).append(',')
                .append(record.seconds).append(',')
                .append(sourceKey(record.source)).append("\n")
        }
        return csvBuilder.toString()
    }
}
