package ai.deepseek.dsh

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** Текстовый редактор моста (открытие из FilesActivity по расширению). */
class EditorActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_PATH = "path"
    }

    private lateinit var body: EditText
    private lateinit var info: TextView
    private var file: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: ""
        file = if (path.isNotEmpty()) File(path) else null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        info = TextView(this).apply {
            setPadding(16, 16, 16, 8)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        body = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(16, 8, 16, 8)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 13f
            isSingleLine = false
            gravity = android.view.Gravity.TOP
            setHorizontallyScrolling(false)
        }
        val save = Button(this).apply {
            text = getString(R.string.ed_save)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { save() }
        }
        root.addView(info)
        root.addView(body)
        root.addView(save)
        setContentView(root)
        title = getString(R.string.ed_title)
        load()
    }

    private fun load() {
        val f = file
        if (f == null) {
            info.text = getString(R.string.ed_empty_path)
            return
        }
        if (!f.exists()) {
            info.text = f.absolutePath + " (new)"
            return
        }
        if (f.length() > 2 * 1024 * 1024) {
            info.text = f.absolutePath + " — too big for editor"
            return
        }
        try {
            body.setText(f.readText(Charsets.UTF_8))
            info.text = f.absolutePath + " — " + getString(R.string.ed_size, f.length())
        } catch (e: Exception) {
            info.text = "ERR: ${e.message}"
        }
    }

    private fun save() {
        val f = file ?: return
        try {
            f.parentFile?.mkdirs()
            f.writeText(body.text.toString(), Charsets.UTF_8)
            info.text = f.absolutePath + " — " + getString(R.string.ed_size, f.length())
            Toast.makeText(this, R.string.ed_saved, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "ERR: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
