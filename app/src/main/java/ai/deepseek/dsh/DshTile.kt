package ai.deepseek.dsh

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/** Quick Settings tile: старт/стоп сервиса. */
@RequiresApi(Build.VERSION_CODES.N)
class DshTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.state = if (DshService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.label = getString(R.string.tile_label)
        qsTile?.updateTile()
    }

    override fun onClick() {
        super.onClick()
        try {
            if (DshService.running) {
                startService(Intent(this, DshService::class.java).setAction(DshService.ACTION_STOP))
            } else {
                ContextCompat.startForegroundService(this, Intent(this, DshService::class.java))
            }
        } catch (_: Exception) {
        }
    }
}
