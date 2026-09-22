package ai.deepseek.dsh

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/** Minimal tar writer (ustar, files+dirs). */
object TarWriter {
    private fun header(name: String, size: Long, dir: Boolean): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, len: Int, s: String) {
            val b = s.toByteArray(Charsets.US_ASCII)
            System.arraycopy(b, 0, h, off, minOf(b.size, len))
        }
        put(0, 100, name.take(100))
        put(100, 8, "0000777\u0000")
        put(108, 8, "0000000\u0000")
        put(116, 8, "0000000\u0000")
        put(124, 12, "%011o\u0000".format(if (dir) 0 else size))
        put(136, 12, "%011o\u0000".format(System.currentTimeMillis() / 1000))
        h[156] = if (dir) '5'.code.toByte() else '0'.code.toByte()
        put(257, 6, "ustar\u0000")
        put(265, 2, "00")
        var sum = 0
        for (i in 0 until 512) sum += if (i in 148 until 156) 32 else h[i].toInt() and 0xFF
        put(148, 8, "%06o\u0000 ".format(sum))
        return h
    }

    fun pack(files: List<Pair<File, String>>, out: java.io.OutputStream) {
        for ((f, name) in files) {
            if (f.isDirectory) {
                out.write(header("$name/", 0, true))
            } else {
                out.write(header(name, f.length(), false))
                FileInputStream(f).use { it.copyTo(out) }
                val pad = (512 - f.length() % 512) % 512
                if (pad > 0) out.write(ByteArray(pad.toInt()))
            }
        }
        out.write(ByteArray(1024))
    }

    fun collect(root: File, arcBase: String, acc: MutableList<Pair<File, String>>) {
        val kids = root.listFiles() ?: return
        for (k in kids) {
            val arc = "$arcBase/${k.name}"
            if (k.isDirectory) {
                acc.add(k to arc)
                collect(k, arc, acc)
            } else acc.add(k to arc)
        }
    }
}

/**
 * BackupWorker v2 (MOD-09): расширенный список R-08 — sessions, settings,
 * .credentials.yaml, profiles/*/cordis.patch.yml, storages, attachments,
 * zen-sessions.json, payload.json. Manifest + версия. Ротация 7 daily + 4 weekly.
 */
class BackupWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    companion object {
        private const val UNIQUE = "dsh-backup-daily"

        fun schedule(c: Context) {
            val hour = Prefs(c).backupHour().coerceIn(0, 23)
            val now = Calendar.getInstance()
            val delay = ((hour - now.get(Calendar.HOUR_OF_DAY) + 24) % 24).toLong()
            val req = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delay, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(c).enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun runOnce(c: Context) {
            WorkManager.getInstance(c).enqueue(OneTimeWorkRequestBuilder<BackupWorker>().build())
        }
    }

    override fun doWork(): Result {
        return try {
            val c = applicationContext
            val home = Paths.dshHome(c)
            val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val out = File(Paths.backups(c), "dsh-backup-$ts.tar.gz")
            out.parentFile?.mkdirs()
            val acc = mutableListOf<Pair<File, String>>()
            val sessions = File(home, "sessions")
            if (sessions.exists()) TarWriter.collect(sessions, "sessions", acc)
            // R-08: полный набор (раньше были только sessions/settings/storages/patch).
            for (n in listOf("settings.yaml", ".credentials.yaml", "zen-sessions.json", "cordis.patch.yml")) {
                val f = File(home, n)
                if (f.exists()) acc.add(f to n)
            }
            val stor = File(home, "storages")
            if (stor.exists()) TarWriter.collect(stor, "storages", acc)
            val att = File(home, "attachments")
            if (att.exists()) TarWriter.collect(att, "attachments", acc)
            for (prof in File(home, "profiles").listFiles() ?: emptyArray()) {
                val patch = File(prof, "cordis.patch.yml")
                if (patch.exists()) acc.add(patch to "profiles/${prof.name}/cordis.patch.yml")
            }
            val pj = File(Paths.payloadDir(c), "payload.json")
            if (pj.exists()) acc.add(pj to "payload.json")
            GZIPOutputStream(BufferedOutputStream(FileOutputStream(out))).use { gz ->
                TarWriter.pack(acc, gz)
            }
            val manifest = File(out.parent, out.nameWithoutExtension + ".json")
            manifest.writeText(
                """{"apk":"${BuildConfig.VERSION_NAME}","dsh":"0.1.7-alpha.1","date":"$ts","files":${acc.size}}""",
                Charsets.UTF_8
            )
            rotate()
            InstallLog.w(c, "backup done: ${out.name} (${acc.size} entries)")
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun rotate() {
        val dir = Paths.backups(applicationContext)
        val tars = dir.listFiles { f -> f.name.startsWith("dsh-backup-") && f.name.endsWith(".tar.gz") }
            ?.sortedByDescending { it.name } ?: return
        val daily = tars.take(7).toSet()
        val weekly = mutableListOf<File>()
        val seenWeeks = mutableSetOf<String>()
        for (f in tars.drop(7)) {
            val week = f.name.take(12)
            if (seenWeeks.add(week) && weekly.size < 4) weekly.add(f)
        }
        val keep = daily + weekly
        for (f in tars) {
            if (f !in keep) {
                f.delete()
                File(f.parent, f.nameWithoutExtension + ".json").delete()
            }
        }
    }
}
