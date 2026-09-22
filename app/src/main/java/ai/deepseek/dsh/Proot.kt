package ai.deepseek.dsh

import android.content.Context
import java.io.File

/**
 * Proot-harness v2 (MOD-02).
 * Запуск: linker64 + libproot.so (nativeLibraryDir, $ORIGIN с libtalloc/libandroid-shmem).
 * Loader proot (libexec) распаковывается из assets в proot-tmp и указывается через
 * PROOT_LOADER/PROOT_LOADER_32 + PROOT_TMP_DIR (иначе Termux-пути по умолчанию отсутствуют
 * и execve гостевых бинарей падает). env -i (герметично), DSH_HOME=/root/dsh-home.
 * Порты: zen 8787, DSH 8081 (R-05). Запуск DSH — node --expose-internals (R-06, F-08).
 */
object Proot {
    const val ZEN_PORT = 8787
    const val DSH_PORT = 8081

    /** Каталог proot-tmp (loader + PROOT_TMP_DIR). Исполняемый — cacheDir, не filesDir. */
    fun tmpDir(c: Context): File = File(c.cacheDir, "proot-tmp").apply { mkdirs() }

    /** Распаковать loader/loader32 из assets в tmpDir (идемпотентно, с проверкой размера). */
    fun ensureLoader(c: Context): File {
        val dir = tmpDir(c)
        val loader = File(dir, "loader")
        val loader32 = File(dir, "loader32")
        try {
            val am = c.assets
            if (!loader.exists() || loader.length() < 10000) {
                am.open("proot-loader/loader").use { ins ->
                    loader.outputStream().use { ins.copyTo(it) }
                }
            }
            if (!loader32.exists() || loader32.length() < 1000) {
                am.open("proot-loader/loader32").use { ins ->
                    loader32.outputStream().use { ins.copyTo(it) }
                }
            }
            try {
                android.system.Os.chmod(loader.absolutePath, 493) // 0755
                android.system.Os.chmod(loader32.absolutePath, 493)
            } catch (_: Exception) {
                loader.setExecutable(true, false)
                loader32.setExecutable(true, false)
            }
        } catch (e: Exception) {
            InstallLog.w(c, "ensureLoader FAILED: ${e.message}")
        }
        return dir
    }

    private fun prefix(c: Context, workdir: String = "/root"): List<String> {
        val root = Paths.debianDir(c).absolutePath
        val shared = Paths.sharedRoot(c).absolutePath
        val home = Paths.dshHome(c).absolutePath
        val ws = Paths.workspace(c).absolutePath
        val payload = Paths.payloadDir(c).absolutePath
        // Bundled .so исполняется только через linker64 прямо из nativeLibraryDir.
        // Копия в filesDir неработоспособна (noexec) — см. BootActivity.installProot.
        val head = if (Paths.hasBundledProot(c)) {
            listOf("/system/bin/linker64", Paths.prootLib(c).absolutePath)
        } else listOf(Paths.prootBin(c).absolutePath)
        return head + listOf(
            "-r", root,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "$shared:$shared",
            "-b", "${Paths.sharedWorkspace(c).absolutePath}:/root/phone",
            "-b", "$home:/root/dsh-home",
            "-b", "$ws:/root/workspace",
            "-b", "$payload:/opt/dsh",
            "-w", workdir
        )
    }

    private fun baseEnv(): List<String> = listOf(
        "HOME=/root", "TERM=xterm-256color", "LANG=C.UTF-8",
        "PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "DSH_HOME=/root/dsh-home"
    )

    /** Полная команда: proot ... /usr/bin/env -i <env> <cmd>. Секреты — только env (R-07). */
    fun full(c: Context, prefs: Prefs, cmd: List<String>, workdir: String = "/root"): List<String> {
        // Loader обязан существовать ДО prefix: probe/smoke идут через full().
        val tmp = ensureLoader(c).absolutePath
        val loader = "$tmp/loader"
        val loader32 = "$tmp/loader32"
        val base = mutableListOf(
            "HOME=/root", "TERM=xterm-256color", "LANG=C.UTF-8",
            "PATH=/opt/node/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "DSH_HOME=/root/dsh-home",
            "PROOT_TMP_DIR=$tmp",
            "TMPDIR=$tmp"
        )
        if (File(loader).exists()) base.add("PROOT_LOADER=$loader")
        if (File(loader32).exists()) base.add("PROOT_LOADER_32=$loader32")
        val extra = listOf(
            "ZEN_API_KEY=${prefs.zenKey()}",
            "ZEN_ADAPTER_PORT=$ZEN_PORT",
            "NODE_OPTIONS=--max-old-space-size=${prefs.nodeRamMb()}"
        )
        return prefix(c, workdir) + listOf("/usr/bin/env", "-i") + base + extra + cmd
    }

    /** argv запуска zen-адаптера (R-09: sessions-file на устойчивом пути, не /tmp). */
    fun zenArgv(): List<String> = listOf(
        "/opt/node/bin/node",
        "/opt/dsh/bin/zen-adapter.mjs",
        "--port", ZEN_PORT.toString(),
        "--sessions-file", "/root/dsh-home/zen-sessions.json"
    )

    /** argv запуска DSH web (R-06: expose-internals обязателен для HMR web-профиля). */
    fun dshArgv(): List<String> = listOf(
        "/opt/node/bin/node", "--expose-internals",
        "/opt/dsh/profiles/node_modules/@deepseek-ai/dsh/lib/bin.js",
        "--profile", "web", "--port", DSH_PORT.toString(), "--no-open",
        "--trusted-host", "127.0.0.1:$DSH_PORT"
    )

    fun startDaemon(c: Context, tag: String, inner: List<String>, log: File): Process {
        log.parentFile?.mkdirs()
        val pb = ProcessBuilder(inner)
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
        pb.redirectError(ProcessBuilder.Redirect.appendTo(log))
        InstallLog.w(c, "start $tag: ${inner.joinToString(" ")}")
        return pb.start()
    }

    fun waitPort(port: Int, tries: Int = 15): Boolean {
        repeat(tries) {
            try {
                java.net.Socket("127.0.0.1", port).close()
                return true
            } catch (_: Exception) {
                Thread.sleep(1000)
            }
        }
        return false
    }
}
