package jp.kota.gigaburn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** 履歴画面：平均Mbpsの推移グラフ＋履歴一覧＋共有（テキスト／CSV）＋消去。 */
class HistoryActivity : Activity() {

    private var records: List<HistoryStore.Record> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        buildUi() // 戻ってきたときに最新状態へ
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        records = HistoryStore.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.history_title)
            textSize = 24f
            gravity = Gravity.CENTER
        })

        // グラフ：直近30件を古い順に
        val chart = HistoryChartView(this)
        val chartRecords = records.take(30).reversed()
        chart.setRecords(chartRecords)
        root.addView(chart, LinearLayout.LayoutParams(-1, dp(190)).apply { topMargin = dp(12) })

        // ボタン行：テキスト共有 / CSV共有 / 消去
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val shareTextBtn = Button(this).apply {
            text = getString(R.string.share)
            isEnabled = records.isNotEmpty()
            setOnClickListener { shareText() }
        }
        val shareCsvBtn = Button(this).apply {
            text = getString(R.string.share_csv)
            isEnabled = records.isNotEmpty()
            setOnClickListener { shareCsv() }
        }
        val clearBtn = Button(this).apply {
            text = getString(R.string.clear_history)
            isEnabled = records.isNotEmpty()
            setOnClickListener { confirmClear() }
        }
        btnRow.addView(shareTextBtn, LinearLayout.LayoutParams(0, -2, 1f))
        btnRow.addView(shareCsvBtn, LinearLayout.LayoutParams(0, -2, 1f))
        btnRow.addView(clearBtn, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(btnRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // 一覧（スクロール）
        val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (records.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = getString(R.string.history_empty)
                textSize = 14f
            })
        } else {
            for (r in records) {
                listLayout.addView(TextView(this).apply {
                    text = HistoryStore.recordLine(this@HistoryActivity, r)
                    textSize = 14f
                    setPadding(0, dp(6), 0, dp(6))
                })
            }
        }
        val scroll = ScrollView(this).apply { addView(listLayout) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        setContentView(root)
    }

    private fun shareText() {
        if (records.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, HistoryStore.summaryText(this@HistoryActivity, records))
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_history_subject))
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_history_chooser)))
    }

    private fun shareCsv() {
        if (records.isEmpty()) return
        try {
            val dir = File(cacheDir, "share").apply { mkdirs() }
            val csvFile = File(dir, "gigaburn_history.csv")
            csvFile.writeText(HistoryStore.csv(records))
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

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_history))
            .setMessage(getString(R.string.clear_dialog_message, records.size))
            .setPositiveButton(getString(R.string.clear_confirm)) { _, _ ->
                HistoryStore.clear(this)
                Toast.makeText(this, getString(R.string.cleared_toast), Toast.LENGTH_SHORT).show()
                buildUi()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
