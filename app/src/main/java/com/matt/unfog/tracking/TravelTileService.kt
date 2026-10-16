package com.matt.unfog.tracking

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.matt.unfog.MainActivity

class TravelTileService : TileService() {

    override fun onStartListening() {
        render()
    }

    override fun onClick() {
        if (TravelMode.running.value) {
            TravelMode.stop(this)
            render(active = false)
            return
        }
        val started = TravelMode.hasLocationPermission(this) &&
            runCatching { TravelMode.start(this) }.isSuccess
        if (started) {
            render(active = true)
        } else {
            // Background start was refused or permission is missing: open the app to do it.
            val intent = Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_START_TRAVEL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun render(active: Boolean = TravelMode.running.value) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (active) "Recording" else "Off"
        tile.updateTile()
    }
}
