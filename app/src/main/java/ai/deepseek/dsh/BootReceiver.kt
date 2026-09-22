package ai.deepseek.dsh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Автозапуск сервиса после перезагрузки (только если установлен и включён). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            if (Prefs(c).autoStart() && Paths.isInstalled(c)) {
                ContextCompat.startForegroundService(c, Intent(c, DshService::class.java))
            }
        } catch (e: Exception) {
            InstallLog.w(c, "BootReceiver FAILED: ${e.message}")
        }
    }
}
