package com.matt.unfog.tracking

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.service.quicksettings.TileService
import android.content.ComponentName
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.matt.unfog.MainActivity
import com.matt.unfog.R
import com.matt.unfog.data.HomeSpot
import com.matt.unfog.data.TrackPoint
import com.matt.unfog.data.Trip
import com.matt.unfog.geo.Geo
import com.matt.unfog.photos.PhotoImporter
import com.matt.unfog.homeSpot
import com.matt.unfog.repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Foreground service that records the active trip. Points go straight to SQLite from here,
 * so the track survives even if the UI process state is torn down.
 */
class TrackingService : Service() {

    companion object {
        const val ACTION_START = "com.matt.unfog.START"
        const val ACTION_STOP = "com.matt.unfog.STOP"
        private const val CHANNEL = "trip"
        private const val NOTIFICATION_ID = 1

        /** Fixes worse than this are dropped. */
        private const val MAX_ACCURACY_M = 40f
        /** Fixes closer than this to the previous point are dropped. */
        private const val MIN_STEP_M = 12.0
        /** How far past the home radius counts as having left, so GPS jitter at the edge doesn't. */
        private const val HOME_MARGIN_M = 150.0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var trip: Trip? = null
    private var last: TrackPoint? = null
    private var distance = 0.0
    private var photoJob: Job? = null
    private var stopping = false
    /** Set once the trip has gone clearly outside the home spot, so it can end on return. */
    private var leftHome = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val locations = result.locations.toList()
            scope.launch { locations.forEach(::record) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTrip()
            // A null intent means the system restarted us after killing the process.
            else -> startTrip()
        }
        return START_STICKY
    }

    private fun startTrip() {
        if (trip != null || stopping) return
        if (!TravelMode.hasLocationPermission(this)) {
            stopSelf()
            return
        }
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        TravelMode.setRunning(true)
        refreshTile()

        scope.launch {
            val repo = repository
            val active = repo.activeTrip() ?: run {
                val now = System.currentTimeMillis()
                val name = "Trip · " + SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(now))
                repo.trip(repo.startTrip(now, name))!!
            }
            trip = active
            val pts = repo.points(active.id)
            last = pts.lastOrNull()
            distance = Geo.pathLength(pts)
            leftHome = homeSpot.spot.value?.let { home -> pts.any { outsideHome(home, it) } } ?: false
            HomeFence.cancelLeftHome(this@TrackingService)
            updateNotification()
            requestUpdates()
            photoJob = launch {
                while (isActive) {
                    delay(2 * 60_000L)
                    runCatching { PhotoImporter.import(this@TrackingService, active) }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 10_000L)
            .setMinUpdateIntervalMillis(5_000L)
            .setMinUpdateDistanceMeters(MIN_STEP_M.toFloat())
            .setWaitForAccurateLocation(true)
            .build()
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun record(loc: Location) {
        val t = trip ?: return
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return
        val p = TrackPoint(
            tripId = t.id,
            timeMs = loc.time,
            lat = loc.latitude,
            lng = loc.longitude,
            accuracyM = loc.accuracy,
            speedMps = if (loc.hasSpeed()) loc.speed else -1f,
        )
        val prev = last
        if (prev != null) {
            val d = Geo.distance(prev, p)
            if (d < MIN_STEP_M) return
            if (!Geo.isJump(prev, p)) distance += d
        }
        last = p
        repository.addPoint(p)
        repository.updateDistance(t.id, distance)
        updateNotification()
        checkHome(p)
    }

    private fun outsideHome(home: HomeSpot, p: TrackPoint) =
        Geo.distance(home.lat, home.lng, p.lat, p.lng) > home.radiusM + HOME_MARGIN_M

    /** Ends the trip when you come back inside the home spot after having left it. */
    private fun checkHome(p: TrackPoint) {
        val home = homeSpot.spot.value ?: return
        if (outsideHome(home, p)) leftHome = true
        if (!home.autoStop || !leftHome || stopping) return
        if (Geo.distance(home.lat, home.lng, p.lat, p.lng) <= home.radiusM) stopTrip(arrivedHome = true)
    }

    private fun stopTrip(arrivedHome: Boolean = false) {
        if (stopping) return
        stopping = true
        fused.removeLocationUpdates(callback)
        photoJob?.cancel()
        scope.launch {
            val repo = repository
            val t = trip ?: repo.activeTrip()
            if (t != null) {
                val end = System.currentTimeMillis()
                val raw = repo.points(t.id)
                val simplified = Geo.simplify(raw)
                if (simplified.size < raw.size) repo.replacePoints(t.id, simplified)
                repo.finishTrip(t.id, end, Geo.pathLength(raw))
                autoName(t, raw)?.let { repo.renameTrip(t.id, it) }
                runCatching { PhotoImporter.import(this@TrackingService, repo.trip(t.id)!!) }
                if (arrivedHome) HomeFence.notifyBackHome(this@TrackingService, repo.trip(t.id)?.name ?: t.name)
            }
            trip = null
            TravelMode.setRunning(false)
            refreshTile()
            ServiceCompat.stopForeground(this@TrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun autoName(t: Trip, points: List<TrackPoint>): String? {
        if (!t.name.startsWith("Trip · ") || points.isEmpty() || !Geocoder.isPresent()) return null
        val from = placeName(points.first())
        val to = placeName(points.last())
        return when {
            from == null && to == null -> null
            from == null || to == null || from == to -> from ?: to
            else -> "$from → $to"
        }
    }

    private suspend fun placeName(p: TrackPoint): String? = withTimeoutOrNull(5_000) {
        val geocoder = Geocoder(this@TrackingService, Locale.getDefault())
        val address = if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(p.lat, p.lng, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) {
                        cont.resume(addresses.firstOrNull())
                    }

                    override fun onError(errorMessage: String?) = cont.resume(null)
                })
            }
        } else {
            @Suppress("DEPRECATION")
            runCatching { geocoder.getFromLocation(p.lat, p.lng, 1)?.firstOrNull() }.getOrNull()
        }
        address?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
    }

    // ---- notification ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Trip in progress", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private var lastNotifiedMs = 0L

    private fun updateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotifiedMs < 5_000) return
        lastNotifiedMs = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(trip))
    }

    private fun buildNotification(t: Trip?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val camera = PendingIntent.getActivity(
            this, 2, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (t == null) "Starting…" else "%.1f km".format(distance / 1000)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_fog)
            .setContentTitle(t?.name ?: "Travel mode")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(t != null)
            .setWhen(t?.startMs ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(open)
            .addAction(0, "Camera", camera)
            .addAction(0, "End trip", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun refreshTile() {
        TileService.requestListeningState(this, ComponentName(this, TravelTileService::class.java))
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        TravelMode.setRunning(false)
        scope.cancel()
        super.onDestroy()
    }
}
