package ai.deepseek.dsh

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Мастер первого запуска v2 (с нуля).
 * Качает rootfs + proot + payload-3, проверяет sha256, распаковывает,
 * сидит dsh-home (zen-провайдер + дефолтная модель), smoke-тестит proot,
 * просит zen-ключ. Всё пишется в install-*.log (R-14).
 */
class BootActivity : Activity() {
    private lateinit var stepViews: List<TextView>
    private lateinit var bar: ProgressBar
    private lateinit var pct: TextView
    private lateinit var log: TextView
    private lateinit var retry: Button
    private lateinit var sendLog: Button

    private val steps = listOf(
        "Rootfs Debian", "Node внутри", "proot", "Payload DSH",
        "Распаковка", "Проверка", "Запуск"
    )

    companion object {
        const val DL_BASE = "https://github.com/Flawl3ssss/dsh-android-v2/releases/download"
        const val ROOTFS_TAG = "rootfs-bookworm-2"
        const val PAYLOAD_TAG = "payload-3"
        const val EXPECTED_DSH = "0.1.7-alpha.1"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashCatcher()
        if (Paths.isInstalled(this)) {
            startMain()
            return
        }
        val bg = 0xFF0B0E14.toInt()
        val card = 0xFF151B26.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(48, 64, 48, 32)
        }
        val title = TextView(this).apply {
            text = "DSH"
            textSize = 34f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFE8ECF3.toInt())
        }
        val sub = TextView(this).apply {
            text = getString(R.string.boot_subtitle)
            textSize = 15f
            setTextColor(0xFF9AA3B5.toInt())
        }
        root.addView(title)
        root.addView(sub)
        root.addView(gap(28))
        val cardBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(card)
            setPadding(32, 28, 32, 28)
        }
        stepViews = steps.map { name ->
            TextView(this).apply {
                text = "○  $name"
                textSize = 15f
                setTextColor(0xFF9AA3B5.toInt())
                setPadding(0, 8, 0, 8)
            }.also { cardBox.addView(it) }
        }
        root.addView(cardBox)
        root.addView(gap(24))
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        pct = TextView(this).apply {
            text = "0%"
            textSize = 13f
            setTextColor(0xFF9AA3B5.toInt())
            gravity = Gravity.END
        }
        root.addView(bar)
        root.addView(pct)
        root.addView(gap(16))
        log = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF9AA3B5.toInt())
        }
        val sv = ScrollView(this).apply { addView(log) }
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(gap(16))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        retry = Button(this).apply {
            text = getString(R.string.action_retry)
            isEnabled = false
            setOnClickListener { Thread { runInstall() }.start() }
        }
        sendLog = Button(this).apply {
            text = getString(R.string.action_send_log)
            setOnClickListener { InstallLog.share(this@BootActivity) }
        }
        row.addView(retry, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(gap(16, true))
        row.addView(sendLog, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        setContentView(root)
        InstallLog.writeDeviceInfo(this)
        Thread { runInstall() }.start()
    }

    private fun installCrashCatcher() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                InstallLog.w(this, "CRASH [${t.name}]: $e\n$sw")
                InstallLog.exportToShared(this)
            } catch (_: Exception) {
            }
            prev?.uncaughtException(t, e)
        }
        try {
            val marker = File(Paths.logsDir(this), "CRASH.pending")
            if (marker.exists()) {
                marker.delete()
                android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.crash_title))
                    .setMessage(getString(R.string.crash_msg))
                    .setPositiveButton(getString(R.string.action_send_log)) { _, _ -> InstallLog.share(this) }
                    .setNeutralButton(getString(R.string.action_copy_log)) { _, _ -> InstallLog.exportToShared(this) }
                    .setNegativeButton(getString(R.string.key_later), null)
                    .show()
            }
        } catch (_: Exception) {
        }
    }

    private fun markCrashPending() {
        try {
            File(Paths.logsDir(this), "CRASH.pending").createNewFile()
        } catch (_: Exception) {
        }
    }

    private fun gap(px: Int, h: Boolean = false) = LinearLayout(this).apply {
        layoutParams = if (h) LinearLayout.LayoutParams(px, -2)
        else LinearLayout.LayoutParams(-1, px)
    }

    private fun ui(msg: String) {
        InstallLog.w(this, msg)
        if (!isFinishing) runOnUiThread { log.append(msg + "\n") }
    }

    private fun step(i: Int, state: Int) {
        if (isFinishing) return
        runOnUiThread {
            val v = stepViews[i]
            v.text = when (state) {
                1 -> "◌  ${steps[i]}…"
                2 -> "✓  ${steps[i]}"
                3 -> "✗  ${steps[i]}"
                else -> "○  ${steps[i]}"
            }
            v.setTextColor(
                when (state) {
                    2 -> 0xFF34D399.toInt()
                    3 -> 0xFFF87171.toInt()
                    1 -> 0xFFE8ECF3.toInt()
                    else -> 0xFF9AA3B5.toInt()
                }
            )
        }
    }

    private fun prog(p: Int) {
        if (isFinishing) return
        runOnUiThread {
            bar.progress = p.coerceIn(0, 100)
            pct.text = "${p.coerceIn(0, 100)}%"
        }
    }

    private fun runInstall() {
        markCrashPending()
        if (!isFinishing) runOnUiThread { retry.isEnabled = false }
        steps.indices.forEach { step(it, 0) }
        try {
            if (filesDir.freeSpace < 1_200_000_000L) {
                throw IllegalStateException("Need ~1.2 GB, free ${filesDir.freeSpace / 1024 / 1024} MB")
            }
            val dl = File(cacheDir, "dl").apply { mkdirs() }
            // 0. rootfs
            step(0, 1)
            val rootfs = File(dl, "debian-rootfs.tar.xz")
            downloadResume("$DL_BASE/$ROOTFS_TAG/debian-rootfs.tar.xz", rootfs) { d, t ->
                prog((d * 40 / (t.takeIf { it > 0 } ?: d + 1)).toInt())
            }
            verifySha(rootfs, "$DL_BASE/$ROOTFS_TAG/debian-rootfs.tar.xz.sha256")
            step(0, 2)
            // 2. proot
            step(2, 1)
            val prootDl = File(dl, "proot")
            downloadResume("$DL_BASE/$ROOTFS_TAG/proot", prootDl) { _, _ -> }
            step(2, 2)
            // 3. payload-3
            step(3, 1)
            val payload = File(dl, "dsh-payload.tar.xz")
            downloadResume("$DL_BASE/$PAYLOAD_TAG/dsh-payload.tar.xz", payload) { d, t ->
                prog(40 + (d * 20 / (t.takeIf { it > 0 } ?: d + 1)).toInt())
            }
            verifySha(payload, "$DL_BASE/$PAYLOAD_TAG/dsh-payload.tar.xz.sha256")
            step(3, 2)
            // 1/4. unpack
            step(1, 1)
            step(4, 1)
            ui("extract rootfs…")
            untar(rootfs, Paths.debianDir(this))
            prog(72)
            ui("node check…")
            val guestNode = File(Paths.debianDir(this), "opt/node/bin/node")
            ui("guest node present=${guestNode.exists()}")
            if (!guestNode.exists()) throw IllegalStateException("/opt/node/bin/node missing in rootfs")
            step(1, 2)
            installProot(prootDl)
            ui("extract payload…")
            installPayload(payload)
            prog(88)
            step(4, 2)
            // 5. verify + seed
            step(5, 1)
            seedDshHome()
            Paths.workspace(this).mkdirs()
            Paths.sharedWorkspace(this)
            diagExec()
            probeProot()
            smokeProot()
            step(5, 2)
            // 6. done
            step(6, 1)
            File(Paths.logsDir(this), "CRASH.pending").delete()
            ui("INSTALL OK")
            prog(100)
            step(6, 2)
            if (!isFinishing) runOnUiThread { gateKeyThenStart() }
        } catch (e: Exception) {
            File(Paths.logsDir(this), "CRASH.pending").delete()
            ui("INSTALL FAILED: ${e.message}")
            steps.indices.forEach { if (!isFinishing) step(it, 3) }
            if (!isFinishing) runOnUiThread { retry.isEnabled = true }
        }
    }

    /** Распаковка payload в payloadDir; терпит и плоский tar, и с одной верхней папкой. */
    private fun installPayload(archive: File) {
        val dest = Paths.payloadDir(this)
        val tmp = File(filesDir, "payload-tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        untar(archive, tmp)
        val kids = tmp.listFiles()?.toList() ?: emptyList()
        val src: File = if (kids.size == 1 && kids[0].isDirectory &&
            File(kids[0], "payload.json").exists()
        ) {
            ui("payload wrapped in ${kids[0].name}/ — flattening")
            kids[0]
        } else tmp
        // Проверка пина до замены.
        val pj = File(src, "payload.json")
        if (!pj.exists()) throw IllegalStateException("payload.json missing in payload")
        val dshV = Regex("\"dshVersion\"\\s*:\\s*\"([^\"]+)\"").find(pj.readText())?.groupValues?.get(1) ?: "?"
        ui("payload dshVersion=$dshV (expected $EXPECTED_DSH)")
        if (dshV != EXPECTED_DSH) throw IllegalStateException("payload pin mismatch: $dshV != $EXPECTED_DSH")
        dest.deleteRecursively()
        dest.mkdirs()
        for (k in src.listFiles() ?: emptyArray()) {
            val dst = File(dest, k.name)
            if (k.isDirectory) k.copyRecursively(dst) else k.copyTo(dst, overwrite = true)
        }
        tmp.deleteRecursively()
        ui("payload installed: ${dest.list()?.size} top entries")
    }

    /** Сид dsh-home: zen-провайдер (со списком моделей!) + дефолтная модель. */
    private fun seedDshHome() {
        val home = Paths.dshHome(this).apply { mkdirs() }
        File(home, "cordis.patch.yml").writeText(
            "# Home patch layer: default model -> zen adapter route.\n" +
                "- id: agent-default-model\n  config:\n    provider: zen\n    model: ${Prefs(this).model()}\n"
        )
        // ВАЖНО: hand-declared route без models отклоняется валидацией (llm-pi-ai README),
        // поэтому модели перечислены явно, а не пустым списком.
        File(home, "settings.yaml").writeText(
            "llm-pi-ai:\n  providers:\n    zen:\n" +
                "      displayName: Zen via local adapter\n" +
                "      api: openai-responses\n" +
                "      baseURL: http://127.0.0.1:8787/v1\n" +
                "      apiKeyEnv: ZEN_API_KEY\n" +
                "      models:\n" +
                "        - id: muse-spark-1.3-contributor-free\n" +
                "          contextWindow: 200000\n" +
                "        - id: big-pickle\n" +
                "          contextWindow: 200000\n" +
                "        - id: minimax-m2.1-free\n" +
                "          contextWindow: 200000\n"
        )
        ui("dsh-home seeded (zen provider + default model)")
    }

    private fun verifySha(file: File, shaUrl: String) {
        ui("sha check ${file.name}…")
        val conn = (URL(shaUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 30000
            setRequestProperty("User-Agent", "DSH-Android/1.0")
            instanceFollowRedirects = true
        }
        val expected = conn.inputStream.bufferedReader().readText().trim().split(Regex("\\s+"))[0]
        conn.disconnect()
        val md = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { ins ->
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        val actual = md.digest().joinToString("") { "%02x".format(it) }
        ui("sha expected=${expected.take(16)}… actual=${actual.take(16)}…")
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            throw IllegalStateException("SHA-256 mismatch for ${file.name}, deleted, retry install")
        }
    }

    private fun downloadResume(url: String, out: File, cb: (Long, Long) -> Unit) {
        var done = if (out.exists()) out.length() else 0L
        var lastErr = ""
        repeat(8) { attempt ->
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000
                    readTimeout = 60000
                    setRequestProperty("User-Agent", "DSH-Android/1.0")
                    if (done > 0) setRequestProperty("Range", "bytes=$done-")
                    instanceFollowRedirects = true
                }
                when (conn.responseCode) {
                    416 -> return
                    206 -> { /* resume */ }
                    200 -> { done = 0 }
                    404 -> throw IllegalStateException("Not found (404): $url")
                    else -> throw IllegalStateException("HTTP ${conn.responseCode}: $url")
                }
                val total = if (conn.responseCode == 206) {
                    done + (conn.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L)
                } else {
                    conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                }
                conn.inputStream.use { ins ->
                    (if (done > 0 && conn.responseCode == 206) FileOutputStream(out, true) else out.outputStream()).use { os ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            done += n
                            cb(done, total)
                        }
                    }
                }
                if (total < 0 || done >= total) return
                lastErr = "incomplete ($done/$total)"
            } catch (e: Exception) {
                lastErr = e.message ?: e.toString()
                ui("retry $attempt: $lastErr")
                Thread.sleep(3000L * (attempt + 1))
            } finally {
                conn?.disconnect()
            }
        }
        throw IllegalStateException("Download failed after 8 tries: $url ($lastErr)")
    }

    private fun untar(archive: File, dest: File) {
        dest.mkdirs()
        var n = 0
        var skipped = 0
        FileInputStream(archive).use { fis ->
            org.apache.commons.compress.compressors.xz.XZCompressorInputStream(fis).use { xz ->
                org.apache.commons.compress.archivers.tar.TarArchiveInputStream(xz).use { tar ->
                    while (true) {
                        val e = try {
                            tar.nextEntry ?: break
                        } catch (ex: Exception) {
                            ui("tar entry read failed: ${ex.message}")
                            break
                        }
                        try {
                            val out = File(dest, e.name)
                            if (!out.canonicalPath.startsWith(dest.canonicalPath)) {
                                skipped++
                                continue
                            }
                            when {
                                e.isDirectory -> out.mkdirs()
                                e.isSymbolicLink -> {
                                    try {
                                        java.nio.file.Files.deleteIfExists(out.toPath())
                                    } catch (_: Exception) {
                                    }
                                    out.parentFile?.mkdirs()
                                    try {
                                        java.nio.file.Files.createSymbolicLink(
                                            out.toPath(), java.nio.file.Paths.get(e.linkName)
                                        )
                                    } catch (ex: Exception) {
                                        ui("symlink skip ${e.name}: ${ex.message}")
                                        skipped++
                                    }
                                }
                                e.isLink -> {
                                    val target = File(dest, e.linkName)
                                    out.parentFile?.mkdirs()
                                    if (target.isFile) target.copyTo(out, overwrite = true)
                                    else {
                                        ui("hardlink skip ${e.name}")
                                        skipped++
                                    }
                                }
                                else -> {
                                    var p = out.parentFile
                                    while (p != null && p.canonicalPath.startsWith(dest.canonicalPath)) {
                                        if (p.exists() && !p.isDirectory) {
                                            ui("cleanup stray file: ${p.name}")
                                            p.delete()
                                            break
                                        }
                                        p = p.parentFile
                                    }
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { tar.copyTo(it) }
                                }
                            }
                            if (!e.isSymbolicLink && e.mode and 0b001001001 != 0) {
                                try {
                                    out.setExecutable(true, false)
                                } catch (_: Exception) {
                                }
                            }
                        } catch (ex: Exception) {
                            ui("entry skip ${e.name}: ${ex.message}")
                            skipped++
                        }
                        n++
                        if (n % 1000 == 0) ui("extract… $n (skip $skipped)")
                    }
                }
            }
        }
        ui("extract done: $n entries, skipped $skipped")
    }

    private fun installProot(downloaded: File) {
        val bundled = File(applicationInfo.nativeLibraryDir, "libproot.so")
        ui("bundled proot: exists=${bundled.exists()} size=${if (bundled.exists()) bundled.length() else 0}")
        val src = if (bundled.exists() && bundled.length() > 100000) bundled else {
            ui("bundled proot missing — fallback to downloaded")
            if (!downloaded.exists() || downloaded.length() < 100000) {
                throw IllegalStateException("proot missing (bundled + downloaded)")
            }
            downloaded
        }
        val dst = Paths.prootBin(this)
        if (src.absolutePath != dst.absolutePath) src.copyTo(dst, overwrite = true)
        else ui("proot used in place, no copy")
        makeExecutable(dst)
    }

    private fun probeProot() {
        val bin = Paths.prootBin(this).absolutePath
        val root = Paths.debianDir(this).absolutePath
        for (n in arrayOf("bin/echo", "bin/true", "bin/bash", "opt/node/bin/node")) {
            ui("rootfs check $n: ${File(root, n).exists()}")
        }
        runProbe("version", listOf(bin, "--version"))
        runProbe("true", listOf(bin, "-r", root, "/bin/true"))
    }

    private fun runProbe(tag: String, cmd: List<String>) {
        try {
            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            ui("probe [$tag] exit=$code out=${out.take(300).replace("\n", "|")}")
        } catch (e: Exception) {
            ui("probe [$tag] START FAILED: ${e.message}")
        }
    }

    private fun diagExec() {
        try {
            val e = ProcessBuilder("/system/bin/echo", "sys-ok").start()
            val o = e.inputStream.bufferedReader().readText().trim()
            ui("baseline /system/bin/echo: $o (exit ${e.waitFor()})")
        } catch (ex: Exception) {
            ui("baseline echo FAILED: ${ex.message}")
        }
    }

    private fun smokeProot() {
        val bin = Paths.prootBin(this).absolutePath
        val root = Paths.debianDir(this).absolutePath
        val argv = if (bin.endsWith(".so") || File(applicationInfo.nativeLibraryDir, "libproot.so").absolutePath == bin) {
            listOf("/system/bin/linker64", bin, "-r", root, "/bin/echo", "proot-ok")
        } else listOf(bin, "-r", root, "/bin/echo", "proot-ok")
        val p = ProcessBuilder(argv).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        val code = p.waitFor()
        ui("smoke exit=$code out=${out.take(200)}")
        if (code != 0 || out != "proot-ok") throw IllegalStateException("proot smoke failed (exit $code): $out")
    }

    private fun makeExecutable(f: File) {
        try {
            f.setExecutable(true, false)
        } catch (e: Exception) {
            ui("setExecutable failed: ${e.message}")
        }
        try {
            android.system.Os.chmod(f.absolutePath, 448)
        } catch (e: Exception) {
            ui("Os.chmod failed: ${e.message}")
        }
        var mode = -1
        try {
            mode = android.system.Os.stat(f.absolutePath).st_mode and 511
        } catch (_: Exception) {
        }
        ui("proot mode=${mode.toString(8)} executable=${f.canExecute()} size=${f.length()}")
        if (!f.canExecute()) throw IllegalStateException("proot not executable (mode ${mode.toString(8)}).")
    }

    /** Zen-ключ обязателен (встроенного нет, R-07): ввод при первом запуске. */
    private fun gateKeyThenStart() {
        if (Prefs(this).zenKey().isNotBlank()) {
            startService()
            startMain()
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = "sk-..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.key_title))
            .setMessage(getString(R.string.key_msg))
            .setView(input)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.action_save)) { _, _ ->
                val k = input.text.toString().trim()
                if (k.isNotBlank()) {
                    Prefs(this).setZenKey(k)
                    InstallLog.w(this, "zen key saved")
                }
                startService()
                startMain()
            }
            .setNegativeButton(getString(R.string.key_later)) { _, _ ->
                startService()
                startMain()
            }
            .show()
    }

    private fun startService() {
        val i = Intent(this, DshService::class.java)
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
    }

    private fun startMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
