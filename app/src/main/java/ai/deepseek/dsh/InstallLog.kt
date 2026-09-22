package ai.deepseek.dsh

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * LogBus v2 (MOD-08): install-*.log с 30-минутным окном, device-info блок,
 * FileProvider-share одного файла, копия в общую папку, crash-catcher маркер.
 * Секретов не пишет: ключ zen нигде не логируется (R-07).
 */
object InstallLog {
    private const val TAG = "dsh-install"
    private val dateFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun currentFile(c: Context): File {
        val dir = Paths.logsDir(c)
        val existing = dir.listFiles { f -> f.name.startsWith("install-") && f.name.endsWith(".log") }
            ?.maxByOrNull { it.lastModified() }
        if (existing != null && System.currentTimeMillis() - existing.lastModified() < 30 * 60 * 1000) {
            return existing
        }
        return File(dir, "install-${dateFmt.format(Date())}.log")
    }

    @Synchronized
    fun w(c: Context, msg: String) {
        try {
            currentFile(c).appendText("${tsFmt.format(Date())} $msg\n")
            android.util.Log.i(TAG, msg)
        } catch (_: Exception) {
        }
    }

    fun writeDeviceInfo(c: Context) {
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val free = c.filesDir.freeSpace / 1024 / 1024
        w(c, "=== device info ===")
        w(c, "model=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
        w(c, "ram_total_mb=${mi.totalMem / 1024 / 1024} ram_avail_mb=${mi.availMem / 1024 / 1024}")
        w(c, "files_free_mb=$free abi=${Build.SUPPORTED_ABIS.joinToString(",")}")
        w(c, "app_version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        try {
            val ps = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            w(c, "kernel_pagesize=$ps")
        } catch (e: Exception) {
            w(c, "kernel_pagesize=unknown (${e.message})")
        }
        w(c, "dsh_pin=0.1.7-alpha.1 zen_port=8787 dsh_port=8081")
        w(c, "===================")
    }

    /** Копия текущего лога в общую папку DSH/logs (переживает смерть процесса). */
    fun exportToShared(c: Context): File? {
        return try {
            val src = currentFile(c)
            val dst = File(File(c.getExternalFilesDir(null), "DSH/logs"), "dsh-install-${src.name}")
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            dst
        } catch (_: Exception) {
            null
        }
    }

    /** Поделиться логом через системный chooser (один файл — максимально легко, R-14). */
    fun share(c: Context, file: File = currentFile(c)) {
        val uri = FileProvider.getUriForFile(c, "ai.deepseek.dsh.fileprovider", file)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "DSH install log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        c.startActivity(Intent.createChooser(i, "Send log"))
    }
}
