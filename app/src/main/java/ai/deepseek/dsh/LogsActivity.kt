package ai.deepseek.dsh

import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Экран логов v2: список, превью хвоста 200 КБ, Share, Refresh, Clear old (R-14). */
class LogsActivity : AppCompatActivity() {
    private lateinit var list: ListView
    private lateinit var preview: TextView
    private var files: List<File> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        fun barBtn(res: Int, fn: () -> Unit): Button = Button(this).apply {
            text = getString(res)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { fn() }
        }
        bar.addView(barBtn(R.string.logs_share) { share() })
        bar.addView(barBtn(R.string.logs_refresh) { refresh() })
        bar.addView(barBtn(R.string.logs_clear) { clearOld() })
        list = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setOnItemClickListener { _, _, pos, _ ->
                if (pos in files.indices) show(files[pos])
            }
        }
        preview = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(16, 8, 16, 8)
        }
        root.addView(bar)
        root.addView(list)
        root.addView(preview)
        setContentView(root)
        title = getString(R.string.logs_title)
        refresh()
    }

    private fun refresh() {
        files = Paths.logsDir(this).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        list.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1,
            if (files.isEmpty()) listOf(getString(R.string.logs_empty))
            else files.map { "${it.name} (${it.length()}b)" }
        )
    }

    private fun show(f: File) {
        try {
            val max = 200 * 1024L
            val text = if (f.length() > max) {
                f.inputStream().use { ins ->
                    ins.skip(f.length() - max)
                    ins.readBytes().toString(Charsets.UTF_8)
                }
            } else f.readText(Charsets.UTF_8)
            preview.text = text
        } catch (e: Exception) {
            preview.text = "ERR: ${e.message}"
        }
    }

    private fun share() {
        try {
            InstallLog.share(this)
        } catch (e: Exception) {
            Toast.makeText(this, "ERR: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun clearOld() {
        val dir = Paths.logsDir(this)
        val installs = dir.listFiles { f -> f.name.startsWith("install-") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        installs.drop(5).forEach { it.delete() }
        refresh()
        Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
    }
}
