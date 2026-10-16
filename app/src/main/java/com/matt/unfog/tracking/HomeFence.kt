package com.matt.unfog.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.matt.unfog.MainActivity
import com.matt.unfog.R
import com.matt.unfog.homeSpot
import com.matt.unfog.repository
import kotlin.concurrent.thread

/**
 * Watches the home spot with a system geofence, so leaving home is noticed even when Unfog is
 * not running. Geofences need "Allow all the time" location access and are wiped on reboot.
 */
object HomeFence {
    private const val TAG = "HomeFence"
    private const val FENCE_ID = "home"
    private const val CHANNEL = "home"
    private const val NOTIFY_LEFT = 2
    private const val NOTIFY_BACK = 3

    fun hasBackgroundLocation(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Registers or removes the geofence to match the saved home spot. Safe to call any time. */
    @SuppressLint("MissingPermission")
    fun sync(context: Context) {
        val client = LocationServices.getGeofencingClient(context)
        val intent = pendingIntent(context)
        client.removeGeofences(intent)
        val spot = context.homeSpot.spot.value ?: return
        if (!spot.askOnLeave || !hasBackgroundLocation(context)) return
        val fence = Geofence.Builder()
            .setRequestId(FENCE_ID)
            // Play services is unreliable below ~150 m.
            .setCircularRegion(spot.lat, spot.lng, maxOf(spot.radiusM, 150f))
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(fence)
            .build()
        runCatching {
            client.addGeofences(request, intent)
                .addOnFailureListener { Log.w(TAG, "Couldn't register home geofence", it) }
        }.onFailure { Log.w(TAG, "Couldn't register home geofence", it) }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, HomeFenceReceiver::class.java),
        // Play services fills in the event extras, so the intent must be mutable.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private fun channel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Home", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun notifyLeftHome(context: Context) {
        channel(context)
        val start = PendingIntent.getForegroundService(
            context, 10,
            Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_START),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            context, 11, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_fog)
            .setContentTitle("Heading out?")
            .setContentText("You just left home. Start a trip to record it.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(30 * 60_000L)
            .addAction(0, "Start trip", start)
            .build()
        notify(context, NOTIFY_LEFT, n)
    }

    fun notifyBackHome(context: Context, tripName: String) {
        channel(context)
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFY_LEFT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_fog)
            .setContentTitle("Welcome home")
            .setContentText("Trip ended and saved: $tripName")
            .setContentIntent(PendingIntent.getActivity(
                context, 12, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
            ))
            .setAutoCancel(true)
            .build()
        notify(context, NOTIFY_BACK, n)
    }

    fun cancelLeftHome(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFY_LEFT)
    }

    private fun notify(context: Context, id: Int, n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(NotificationManager::class.java).notify(id, n)
    }
}

class HomeFenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        if (TravelMode.running.value) return
        val pending = goAsync()
        thread {
            try {
                if (context.repository.activeTrip() == null) HomeFence.notifyLeftHome(context)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Geofences do not survive a reboot or an app update; put the home fence back. */
class RefenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> HomeFence.sync(context)
        }
    }
}
