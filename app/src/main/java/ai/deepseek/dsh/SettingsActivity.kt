package ai.deepseek.dsh

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Настройки v2: zen-ключ (ESP), модель, локаль, AMOLED, шрифт, RAM Node,
 * час бэкапа + Backup now, пин-версия (R-01), обновления по кнопке (R-02),
 * отправка лога, wipe & reinstall.
 */
class SettingsActivity : AppCompatActivity() {
    private val models = arrayOf("muse-spark-1.3-contributor-free", "big-pickle", "minimax-m2.1-free")
    private val rams = arrayOf(1024, 2048, 3072)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        fun label(res: Int): TextView = TextView(this).apply { text = getString(res); textSize = 15f }
        fun btn(res: Int, fn: () -> Unit): Button = Button(this).apply {
            text = getString(res)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnClickListener { fn() }
        }

        val zen = EditText(this).apply {
            hint = getString(R.string.set_zen)
            setText(prefs.zenKey())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val model = Spinner(this).apply {
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, models)
            setSelection(models.indexOf(prefs.model()).coerceAtLeast(0))
        }
        val localeSw = Switch(this).apply {
            text = getString(R.string.set_locale)
            isChecked = prefs.locale() == "en"
            setOnCheckedChangeListener { _, en -> prefs.setLocale(if (en) "en" else "ru") }
        }
        val amoledSw = Switch(this).apply {
            text = getString(R.string.set_amoled)
            isChecked = prefs.amoled()
            setOnCheckedChangeListener { _, v -> prefs.setAmoled(v) }
        }
        val fontVal = TextView(this)
        val font = SeekBar(this).apply {
            max = 60
            progress = ((prefs.fontScale() - 0.8f) * 100).toInt().coerceIn(0, 60)
            fontVal.text = getString(R.string.set_font, prefs.fontScale())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    val v = 0.8f + p / 100f
                    fontVal.text = getString(R.string.set_font, v)
                    prefs.setFontScale(v)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        val ram = Spinner(this).apply {
            val labels = rams.map { "${it} MB" }
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(rams.indexOf(prefs.nodeRamMb()).coerceAtLeast(1))
        }
        val hour = EditText(this).apply {
            hint = getString(R.string.set_hour)
            setText(prefs.backupHour().toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }

        col.addView(label(R.string.set_zen)); col.addView(zen)
        col.addView(btn(R.string.set_zen_save) {
            prefs.setZenKey(zen.text.toString().trim())
            Toast.makeText(this, R.string.set_zen_saved, Toast.LENGTH_SHORT).show()
        })
        col.addView(label(R.string.set_model)); col.addView(model)
        col.addView(btn(R.string.set_model_save) {
            prefs.setModel(models[model.selectedItemPosition])
            Toast.makeText(this, R.string.set_zen_saved, Toast.LENGTH_SHORT).show()
        })
        col.addView(localeSw); col.addView(amoledSw)
        col.addView(fontVal); col.addView(font)
        col.addView(label(R.string.set_ram)); col.addView(ram)
        col.addView(btn(R.string.set_ram_save) { prefs.setNodeRamMb(rams[ram.selectedItemPosition]) })
        col.addView(label(R.string.set_hour)); col.addView(hour)
        col.addView(btn(R.string.set_hour_save) {
            hour.text.toString().toIntOrNull()?.coerceIn(0, 23)?.let { prefs.setBackupHour(it) }
        })
        col.addView(btn(R.string.set_backup_now) {
            BackupWorker.runOnce(this)
            Toast.makeText(this, R.string.set_backup_started, Toast.LENGTH_SHORT).show()
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.set_version)
            textSize = 13f
            setPadding(0, 16, 0, 8)
        })
        // R-02: обновление ТОЛЬКО по кнопке. Check ведёт на релизы (список + changelog),
        // apply/rollback — TASK-005 (следующий коммит): кнопки появятся здесь же.
        col.addView(btn(R.string.set_updates) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/Flawl3ssss/dsh-android-v2/releases")
                )
            )
        })
        col.addView(btn(R.string.set_send_log) { InstallLog.share(this) })
        col.addView(btn(R.string.set_wipe) { confirmWipe() })

        val scroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(col)
        }
        setContentView(scroll)
        title = getString(R.string.set_title)
    }

    private fun confirmWipe() {
        AlertDialog.Builder(this)
            .setTitle(R.string.set_wipe_title)
            .setMessage(R.string.set_wipe_msg)
            .setPositiveButton(R.string.set_wipe_yes) { _, _ ->
                stopService(Intent(this, DshService::class.java).setAction(DshService.ACTION_STOP))
                Thread {
                    try {
                        Paths.debianDir(this).deleteRecursively()
                        Paths.payloadDir(this).deleteRecursively()
                    } catch (_: Exception) {
                    }
                    runOnUiThread {
                        startActivity(
                            Intent(this, BootActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        finish()
                    }
                }.start()
            }
            .setNegativeButton(R.string.set_cancel, null)
            .show()
    }
}
