package com.matt.unfog.tracking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Entry points for turning travel mode on and off from the UI, the tile or the notification. */
object TravelMode {

    private val _running = MutableStateFlow(false)
    /** Whether the tracking service is alive in this process. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    internal fun setRunning(value: Boolean) {
        _running.value = value
    }

    fun hasLocationPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Starts a trip, or resumes the active one. [name] is used only for a new trip. */
    fun start(context: Context, name: String? = null) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, TrackingService::class.java)
                .setAction(TrackingService.ACTION_START)
                .putExtra(TrackingService.EXTRA_NAME, name?.trim()?.takeIf { it.isNotEmpty() }),
        )
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_STOP)
        )
    }
}
