package ai.deepseek.dsh

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Мост «задача завершена»: пишет last_task, будит сервис, обновляет виджет. */
class TaskReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "ai.deepseek.dsh.TASK_DONE"

        fun pingUi(c: Context) {
            try {
                val am = AppWidgetManager.getInstance(c)
                val ids = am.getAppWidgetIds(ComponentName(c, DshWidget::class.java))
                val i = Intent(c, DshWidget::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                }
                c.sendBroadcast(i)
            } catch (_: Exception) {
            }
        }
    }

    override fun onReceive(c: Context, intent: Intent?) {
        val a = intent?.action ?: return
        if (a != ACTION && a != DshService.ACTION_TASK_DONE) return
        val title = intent.getStringExtra(DshService.EXTRA_TITLE)
            ?: intent.getStringExtra("title") ?: ""
        val session = intent.getStringExtra(DshService.EXTRA_SESSION)
            ?: intent.getStringExtra("session")
        try {
            c.getSharedPreferences("dsh", Context.MODE_PRIVATE)
                .edit().putString("last_task", title).apply()
        } catch (_: Exception) {
        }
        try {
            val fwd = Intent(c, DshService::class.java).apply {
                action = DshService.ACTION_TASK_DONE
                putExtra(DshService.EXTRA_TITLE, title)
                putExtra(DshService.EXTRA_SESSION, session)
            }
            ContextCompat.startForegroundService(c, fwd)
        } catch (e: Exception) {
            InstallLog.w(c, "TaskReceiver FAILED: ${e.message}")
        }
        pingUi(c)
    }
}
