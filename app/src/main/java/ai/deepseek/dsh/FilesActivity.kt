package ai.deepseek.dsh

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/**
 * Файловый менеджер моста v2: вкладки Workspace / Inbox / Outbox / Downloads.
 * Копировать/переместить (буфер+вставка), переименовать, удалить, новая папка,
 * поделиться, открыть в редакторе, прикрепить в чат (копия в inbox).
 */
class FilesActivity : AppCompatActivity() {
    private lateinit var title: TextView
    private lateinit var list: ListView
    private lateinit var status: TextView
    private var tab = 0
    private var dir: File? = null
    private var items: List<File> = emptyList()
    private var clip: File? = null
    private var clipMove = false

    private fun tabDir(i: Int): File = when (i) {
        0 -> Paths.sharedWorkspace(this)
        1 -> Paths.inbox(this)
        2 -> File(Paths.sharedRoot(this), "outbox").apply { mkdirs() }
        else -> Paths.downloads(this)
    }

    private fun tabName(i: Int): String = when (i) {
        0 -> getString(R.string.fm_tab_ws)
        1 -> getString(R.string.fm_tab_inbox)
        2 -> getString(R.string.fm_tab_outbox)
        else -> getString(R.string.fm_tab_dl)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        title = TextView(this).apply { textSize = 20f }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in 0..3) {
            val b = Button(this).apply {
                text = tabName(i)
                setOnClickListener { tab = i; reload() }
            }
            tabs.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        list = ListView(this)
        status = TextView(this).apply { textSize = 12f }
        val ops = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun opBtn(t: String, fn: (File) -> Unit): Button {
            return Button(this).apply {
                text = t
                setOnClickListener {
                    val pos = list.checkedItemPosition
                    if (pos < 0 || pos >= items.size) {
                        Toast.makeText(this@FilesActivity, getString(R.string.fm_pick), Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    fn(items[pos])
                }
            }.also { ops.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }
        opBtn(getString(R.string.fm_open)) { open(it) }
        opBtn(getString(R.string.fm_copy)) { clip = it; clipMove = false; toast(getString(R.string.fm_buffered)) }
        opBtn(getString(R.string.fm_move)) { clip = it; clipMove = true; toast(getString(R.string.fm_buffered)) }
        root.addView(title)
        root.addView(tabs)
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val paste = Button(this@FilesActivity).apply {
                text = getString(R.string.fm_paste)
                setOnClickListener { paste() }
            }
            val mk = Button(this@FilesActivity).apply {
                text = getString(R.string.fm_mkdir)
                setOnClickListener { mkdir() }
            }
            val rn = Button(this@FilesActivity).apply {
                text = getString(R.string.fm_rename)
                setOnClickListener {
                    val pos = list.checkedItemPosition
                    if (pos in items.indices) rename(items[pos])
                }
            }
            val del = Button(this@FilesActivity).apply {
                text = getString(R.string.fm_delete)
                setOnClickListener {
                    val pos = list.checkedItemPosition
                    if (pos in items.indices) delete(items[pos])
                }
            }
            addView(paste, LinearLayout.LayoutParams(0, -2, 1f))
            addView(mk, LinearLayout.LayoutParams(0, -2, 1f))
            addView(rn, LinearLayout.LayoutParams(0, -2, 1f))
            addView(del, LinearLayout.LayoutParams(0, -2, 1f))
        })
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(status)
        root.addView(ops)
        setContentView(root)
        list.choiceMode = ListView.CHOICE_MODE_SINGLE
        list.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos in items.indices) shareSheet(items[pos])
            true
        }
        reload()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun reload() {
        dir = tabDir(tab)
        title.text = "${tabName(tab)} — ${dir!!.absolutePath}"
        showDir(dir!!)
    }

    private fun showDir(d: File) {
        items = (d.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList())
        title.text = d.absolutePath
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, items.map {
            (if (it.isDirectory) "📁 " else "📄 ") + it.name
        })
        val used = try {
            d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) {
            0L
        }
        status.text = getString(R.string.fm_status, items.size, used / 1024)
    }

    private fun open(f: File) {
        if (f.isDirectory) {
            showDir(f)
            return
        }
        val ext = f.extension.lowercase()
        if (ext in listOf("txt", "md", "json", "xml", "yml", "yaml", "log", "sh", "py", "kt", "js", "ts")) {
            startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_PATH, f.absolutePath))
            return
        }
        share(f, Intent.ACTION_VIEW)
    }

    private fun paste() {
        val src = clip ?: return
        val targetDir = dir ?: return
        var dst = File(targetDir, src.name)
        if (dst.exists()) dst = File(targetDir, src.nameWithoutExtension + "-copy." + src.extension)
        try {
            if (src.isDirectory) src.copyRecursively(dst) else src.copyTo(dst)
            if (clipMove) {
                if (src.isDirectory) src.deleteRecursively() else src.delete()
                clip = null
            }
            reload()
        } catch (e: Exception) {
            toast(e.message ?: "error")
        }
    }

    private fun mkdir() {
        val input = EditText(this)
        AlertDialog.Builder(this).setTitle(getString(R.string.fm_mkdir))
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                File(dir, input.text.toString()).mkdirs()
                reload()
            }
            .setNegativeButton(getString(R.string.set_cancel), null).show()
    }

    private fun rename(f: File) {
        val input = EditText(this).apply { setText(f.name) }
        AlertDialog.Builder(this).setTitle(getString(R.string.fm_rename))
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                f.renameTo(File(f.parent, input.text.toString()))
                reload()
            }
            .setNegativeButton(getString(R.string.set_cancel), null).show()
    }

    private fun delete(f: File) {
        AlertDialog.Builder(this).setMessage(getString(R.string.fm_confirm_del, f.name))
            .setPositiveButton(getString(R.string.fm_delete)) { _, _ ->
                if (f.isDirectory) f.deleteRecursively() else f.delete()
                reload()
            }
            .setNegativeButton(getString(R.string.set_cancel), null).show()
    }

    private fun shareSheet(f: File) {
        AlertDialog.Builder(this).setTitle(f.name)
            .setItems(arrayOf(getString(R.string.fm_share), getString(R.string.fm_attach))) { _, which ->
                if (which == 0) share(f, Intent.ACTION_SEND)
                else {
                    val dst = File(Paths.inbox(this), f.name)
                    if (f.isFile) f.copyTo(dst, overwrite = true)
                    toast(getString(R.string.fm_attached))
                }
            }.show()
    }

    private fun share(f: File, action: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "ai.deepseek.dsh.fileprovider", f)
            val i = Intent(action).apply {
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (action == Intent.ACTION_SEND) type = contentResolver.getType(uri) ?: "*/*"
            }
            startActivity(Intent.createChooser(i, f.name))
        } catch (e: Exception) {
            toast(e.message ?: "error")
        }
    }
}
