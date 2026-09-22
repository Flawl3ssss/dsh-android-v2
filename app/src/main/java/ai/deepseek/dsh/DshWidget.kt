package ai.deepseek.dsh

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.app.PendingIntent

/** Виджет: статус + последняя задача + кнопки Open/Stop. */
class DshWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, am: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateOne(c, am, id)
    }

    companion object {
        fun updateOne(c: Context, am: AppWidgetManager, id: Int) {
            val running = DshService.running
            val last = c.getSharedPreferences("dsh", Context.MODE_PRIVATE)
                .getString("last_task", null)
            val status = if (running) c.getString(R.string.widget_running)
            else c.getString(R.string.widget_stopped)
            val task = last ?: c.getString(R.string.widget_task_none)
            val v = RemoteViews(c.packageName, R.layout.widget_layout)
            v.setTextViewText(R.id.widget_status, status)
            v.setTextViewText(R.id.widget_task, task)
            val open = PendingIntent.getActivity(
                c, 10, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.widget_open, open)
            val stop = PendingIntent.getService(
                c, 11, Intent(c, DshService::class.java).setAction(DshService.ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.widget_stop, stop)
            am.updateAppWidget(id, v)
        }
    }
}
