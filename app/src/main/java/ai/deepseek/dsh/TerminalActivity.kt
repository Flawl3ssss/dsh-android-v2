package ai.deepseek.dsh

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.OutputStream

/** Сессия терминала переживает ротацию (синглтон). */
object TerminalHolder {
    var process: Process? = null
    var out: OutputStream? = null
    val lines = StringBuilder()
    var pumpStarted = false
}

/**
 * Терминал v2: прямой proot /bin/bash -l (без Shizuku/a11y — R-03/R-04).
 * v1.1: audit строк в InstallLog (TASK-104).
 */
class TerminalActivity : AppCompatActivity() {
    private lateinit var output: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        output = TextView(this).apply {
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setPadding(16, 16, 16, 16)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(output)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        input = EditText(this).apply {
            hint = getString(R.string.term_hint)
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val send = Button(this).apply {
            text = getString(R.string.term_send)
            setOnClickListener { sendLine() }
        }
        row.addView(input)
        row.addView(send)
        root.addView(scroll)
        root.addView(row)
        setContentView(root)
        title = getString(R.string.term_title)
        ensureProcess()
        output.text = TerminalHolder.lines.toString()
    }

    private fun ensureProcess() {
        if (TerminalHolder.process?.isAlive == true) return
        try {
            val argv = Proot.full(this, Prefs(this), listOf("/bin/bash", "-l"), "/root")
            val p = ProcessBuilder(argv).redirectErrorStream(true).start()
            TerminalHolder.process = p
            TerminalHolder.out = p.outputStream
            TerminalHolder.lines.append(getString(R.string.term_starting)).append("\n")
            if (!TerminalHolder.pumpStarted) {
                TerminalHolder.pumpStarted = true
                Thread {
                    try {
                        val buf = ByteArray(4096)
                        val ins = p.inputStream
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            val s = String(buf, 0, n, Charsets.UTF_8)
                            synchronized(TerminalHolder.lines) { TerminalHolder.lines.append(s) }
                            runOnUiThread {
                                try {
                                    output.append(s)
                                    (output.parent as? android.view.View)?.post {
                                        (output.parent as android.view.View).scrollTo(0, output.bottom)
                                    }
                                } catch (_: Exception) {
                                }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        try {
                            runOnUiThread { output.append("\n" + getString(R.string.term_dead) + "\n") }
                        } catch (_: Exception) {
                        }
                        synchronized(TerminalHolder.lines) { TerminalHolder.lines.append("\n") }
                        TerminalHolder.pumpStarted = false
                    }
                }.apply { isDaemon = true; start() }
            }
        } catch (e: Exception) {
            TerminalHolder.lines.append("ERR: ${e.message}\n")
            output.text = TerminalHolder.lines.toString()
        }
    }

    private fun sendLine() {
        val line = input.text.toString()
        input.text.clear()
        try {
            TerminalHolder.out?.write((line + "\n").toByteArray(Charsets.UTF_8))
            TerminalHolder.out?.flush()
        } catch (e: Exception) {
            output.append("ERR: ${e.message}\n")
        }
    }
}
