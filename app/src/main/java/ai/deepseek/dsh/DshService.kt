package ai.deepseek.dsh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File

/**
 * ForegroundService v2 (MOD-01): specialUse, 2 канала, START_STICKY,
 * запуск zen→DSH с health-gate, WakeLock только на задачу.
 * v1.1: watchdog-poll 15с (R-11) — отдельно, не в этом файле.
 */
class DshService : Service() {
    companion object {
        const val CH = "dsh-fgs"
        const val NOTIF_ID = 41
        const val ACTION_STOP = "ai.deepseek.dsh.STOP"
        const val ACTION_TASK_DONE = "ai.deepseek.dsh.TASK_DONE"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SESSION = "session"
        var running = false
    }

    private val binder = LocalBinder()
    private var adapter: Process? = null
    private var dsh: Process? = null
    private var wake: PowerManager.WakeLock? = null

    inner class LocalBinder : Binder() {
        fun service(): DshService = this@DshService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel("dsh-tasks", getString(R.string.notif_tasks), NotificationManager.IMPORTANCE_DEFAULT)
        )
        startForeground(NOTIF_ID, buildNotif(getString(R.string.svc_starting), null))
        running = true
        Thread { boot() }.start()
    }

    private fun boot() {
        val prefs = Prefs(this)
        InstallLog.w(this, "service boot (dsh_pin=0.1.7-alpha.1)")
        if (prefs.zenKey().isBlank()) {
            notifyFail(getString(R.string.need_key))
            updateNotif(getString(R.string.need_key), 0)
            return
        }
        try {
            val logs = Paths.logsDir(this)
            // 1. zen-adapter (первым, health-gate).
            adapter = Proot.startDaemon(
                this, "zen-adapter",
                Proot.full(this, prefs, Proot.zenArgv()),
                File(logs, "adapter.log")
            )
            if (!Proot.waitPort(Proot.ZEN_PORT)) {
                notifyFail("Zen adapter did not start — see Logs")
                InstallLog.w(this, "adapter health check FAILED")
            }
            // 2. dsh web.
            dsh = Proot.startDaemon(
                this, "dsh-web",
                Proot.full(this, prefs, Proot.dshArgv()),
                File(logs, "dsh.log")
            )
            if (Proot.waitPort(Proot.DSH_PORT, 30)) {
                updateNotif(getString(R.string.svc_running), 0)
                TaskReceiver.pingUi(this)
            } else {
                notifyFail("DSH web did not start — see Logs")
            }
        } catch (e: Exception) {
            InstallLog.w(this, "service boot FAILED: ${e.message}")
            notifyFail("Start error: ${e.message}")
        }
    }

    fun setTaskActive(active: Boolean) {
        if (active) {
            if (wake == null) {
                val pm = getSystemService(PowerManager::class.java)
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dsh:task")
                    .apply { acquire(30 * 60 * 1000L) }
            }
            updateNotif(getString(R.string.svc_task), 0)
        } else {
            wake?.let { if (it.isHeld) it.release() }
            wake = null
            updateNotif(getString(R.string.svc_running), 0)
        }
    }

    fun notifyTaskDone(title: String, session: String?) {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "dsh-tasks")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.task_done))
            .setContentText(title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(42, n)
        setTaskActive(false)
    }

    private fun notifyFail(text: String) {
        updateNotif(text, 0)
        val n = NotificationCompat.Builder(this, "dsh-tasks")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(getString(R.string.svc_error))
            .setContentText(text)
            .build()
        getSystemService(NotificationManager::class.java).notify(43, n)
    }

    private fun buildNotif(text: String, progress: Int?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DshService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
        if (progress != null) b.setProgress(100, progress, progress < 0)
        return b.build()
    }

    private fun updateNotif(text: String, progress: Int?) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text, progress))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TASK_DONE -> {
                notifyTaskDone(
                    intent.getStringExtra(EXTRA_TITLE) ?: "",
                    intent.getStringExtra(EXTRA_SESSION)
                )
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { adapter?.destroy() } catch (_: Exception) {
        }
        try { dsh?.destroy() } catch (_: Exception) {
        }
        try { wake?.let { if (it.isHeld) it.release() } } catch (_: Exception) {
        }
        InstallLog.w(this, "service destroyed")
        super.onDestroy()
    }
}
