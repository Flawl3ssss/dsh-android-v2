package ai.deepseek.dsh

import android.content.Context
import java.io.File

/**
 * Единая схема файловой системы v2 (с нуля).
 *
 * Приват (filesDir, только приложение):
 *   debian/    — гостевой rootfs (Debian bookworm arm64)
 *   payload/   — payload-3: profiles/{web,node_modules}, bin/zen-adapter.mjs, payload.json
 *   dsh-home/  — DSH_HOME (профили, sessions, settings, credentials, zen-sessions.json, logs)
 *   workspace/ — приватный workspace (cwd агента вкупе с /root/phone)
 *   proot      — бинарь proot (fallback, если нет bundled lib)
 *
 * Общее (getExternalFilesDir/DSH, видно пользователю):
 *   workspace/ inbox/ outbox/ downloads/ backups/
 */
object Paths {
    fun debianDir(c: Context) = File(c.filesDir, "debian")
    fun payloadDir(c: Context) = File(c.filesDir, "payload")
    fun dshHome(c: Context) = File(c.filesDir, "dsh-home")
    fun workspace(c: Context) = File(c.filesDir, "workspace")

    /** proot: сначала bundled native lib (правильный SELinux-контекст), иначе filesDir/proot. */
    fun prootLib(c: Context) = File(c.applicationInfo.nativeLibraryDir, "libproot.so")
    fun prootBin(c: Context): File {
        val lib = prootLib(c)
        if (lib.exists() && lib.length() > 100000) return lib
        return File(c.filesDir, "proot")
    }

    fun sharedRoot(c: Context): File {
        val f = File(c.getExternalFilesDir(null), "DSH")
        f.mkdirs()
        return f
    }
    fun inbox(c: Context) = File(sharedRoot(c), "inbox").apply { mkdirs() }
    fun downloads(c: Context) = File(sharedRoot(c), "downloads").apply { mkdirs() }
    fun backups(c: Context) = File(sharedRoot(c), "backups").apply { mkdirs() }
    fun sharedWorkspace(c: Context) = File(sharedRoot(c), "workspace").apply { mkdirs() }

    fun logsDir(c: Context) = File(dshHome(c), "logs").apply { mkdirs() }

    fun isInstalled(c: Context): Boolean {
        if (!File(debianDir(c), "etc/debian_version").exists()) return false
        val pj = File(payloadDir(c), "payload.json")
        if (!pj.exists()) return false
        // Проверяем пин payload (R-01): принимаем payloadVersion >= 2.
        return try {
            val t = pj.readText()
            t.contains("\"payloadVersion\"") && prootBin(c).exists()
        } catch (_: Exception) {
            false
        }
    }
}
