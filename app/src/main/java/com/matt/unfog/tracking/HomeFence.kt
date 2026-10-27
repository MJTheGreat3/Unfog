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
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
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
    private const val CHANNEL = "place_alarm"
    private const val OLD_CHANNEL = "home"
    private const val NOTIFY_LEFT = 2
    private const val NOTIFY_BACK = 3

    fun hasBackgroundLocation(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Re-registers one geofence per home spot that asks on leave. Safe to call any time. */
    @SuppressLint("MissingPermission")
    fun sync(context: Context) {
        val client = LocationServices.getGeofencingClient(context)
        val intent = pendingIntent(context)
        client.removeGeofences(intent)
        val spots = context.homeSpot.spots.value.filter { it.askOnLeave }
        if (spots.isEmpty() || !hasBackgroundLocation(context)) return
        val fences = spots.map { spot ->
            Geofence.Builder()
                .setRequestId(spot.id)
                // Play services is unreliable below ~150 m.
                .setCircularRegion(spot.lat, spot.lng, maxOf(spot.radiusM, 150f))
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofences(fences)
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

    /** Alarm-like channel: alarm sound and vibration, heard even when the phone is on silent. */
    private fun channel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Place alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks to start a trip when you leave a saved place, and to end it when you arrive"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 500, 600)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
        )
    }

    /** Whether alarms can take over the screen; Android 14+ lets the user turn this off. */
    fun canUseFullScreen(context: Context) = Build.VERSION.SDK_INT < 34 ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private fun alarmIntent(context: Context, code: Int, kind: String, place: String, trip: String? = null) =
        PendingIntent.getActivity(
            context, code, PlaceAlarmActivity.intent(context, kind, place, trip),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun notifyLeftHome(context: Context, placeName: String) {
        channel(context)
        val start = PendingIntent.getForegroundService(
            context, 10,
            Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_START),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val alarm = alarmIntent(context, 11, PlaceAlarmActivity.KIND_LEFT, placeName)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_fog)
            .setContentTitle("Heading out?")
            .setContentText("You just left $placeName. Start a trip to record it.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(alarm)
            .setFullScreenIntent(alarm, true)
            .setAutoCancel(true)
            .setTimeoutAfter(30 * 60_000L)
            .addAction(0, "Start trip", start)
            .build()
        notify(context, NOTIFY_LEFT, n)
    }

    /** Asks whether to end the running trip now that you've reached a saved place. */
    fun notifyArrived(context: Context, placeName: String, tripName: String) {
        channel(context)
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFY_LEFT)
        val end = PendingIntent.getService(
            context, 13,
            Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val alarm = alarmIntent(context, 12, PlaceAlarmActivity.KIND_ARRIVED, placeName, tripName)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_fog)
            .setContentTitle(if (placeName == "Home") "Welcome home" else "You're at $placeName")
            .setContentText("End \u201c$tripName\u201d?")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(alarm)
            .setFullScreenIntent(alarm, true)
            .setAutoCancel(true)
            .addAction(0, "End trip", end)
            .build()
        notify(context, NOTIFY_BACK, n)
    }

    fun cancelArrived(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFY_BACK)
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
                val place = event.triggeringGeofences?.firstNotNullOfOrNull { context.homeSpot.get(it.requestId) }
                if (context.repository.activeTrip() == null) HomeFence.notifyLeftHome(context, place?.name ?: "home")
            } finally {
                pending.finish()
            }
        }
    }
}

/** Geofences do not survive a reboot or an app update; put the home fences back. */
class RefenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> HomeFence.sync(context)
        }
    }
}
