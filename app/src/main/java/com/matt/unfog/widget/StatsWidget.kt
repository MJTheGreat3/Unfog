package com.matt.unfog.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.matt.unfog.MainActivity
import com.matt.unfog.R
import com.matt.unfog.repository
import com.matt.unfog.tracking.TrackingService
import com.matt.unfog.ui.MapData
import com.matt.unfog.ui.formatTime
import kotlin.concurrent.thread

/**
 * Home-screen widget: overall travel stats, or a live view of the trip in progress with a
 * ticking timer. Refreshed whenever the trip data changes (see [UnfogApp]).
 */
class StatsWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        thread {
            try {
                update(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Redraws every placed widget. Reads the database, so call it off the main thread. */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, StatsWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context): RemoteViews {
            val repo = context.repository
            val views = RemoteViews(context.packageName, R.layout.widget_stats)
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(
                    context, 20, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                ),
            )

            val active = repo.activeTrip()
            if (active != null) {
                views.setViewVisibility(R.id.idle, View.GONE)
                views.setViewVisibility(R.id.active, View.VISIBLE)
                views.setViewVisibility(R.id.rec_dot, View.VISIBLE)
                views.setTextViewText(R.id.header, active.name)
                val (value, unit) = distanceParts(active.distanceM)
                views.setTextViewText(R.id.distance, value)
                views.setTextViewText(R.id.distance_unit, "$unit so far")
                // Chronometer bases are on the elapsed-realtime clock, not wall time.
                val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - active.startMs)
                views.setChronometer(R.id.elapsed, base, null, true)
                views.setTextViewText(R.id.trip_photos, active.photoCount.toString())
                views.setTextViewText(R.id.trip_photos_label, if (active.photoCount == 1) "photo" else "photos")
                views.setTextViewText(R.id.trip_started, formatTime(active.startMs))
                views.setTextViewText(R.id.action, "End trip")
                views.setTextColor(R.id.action, 0xFFFF8A8A.toInt())
                views.setInt(R.id.action, "setBackgroundResource", R.drawable.widget_button_end)
                views.setOnClickPendingIntent(
                    R.id.action,
                    PendingIntent.getService(
                        context, 21,
                        Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            } else {
                val trips = repo.trips()
                val explored = MapData.explored(repo.allPoints())
                views.setViewVisibility(R.id.idle, View.VISIBLE)
                views.setViewVisibility(R.id.active, View.GONE)
                views.setViewVisibility(R.id.rec_dot, View.GONE)
                views.setTextViewText(R.id.header, "Unfog")
                views.setTextViewText(R.id.area, formatArea(explored.areaKm2))
                views.setTextViewText(R.id.stat_trips, trips.size.toString())
                views.setTextViewText(R.id.stat_trips_label, if (trips.size == 1) "trip" else "trips")
                views.setTextViewText(R.id.stat_km, formatWholeKm(trips.sumOf { it.distanceM }))
                views.setTextViewText(R.id.stat_days, explored.days.toString())
                views.setTextViewText(R.id.stat_days_label, if (explored.days == 1) "day" else "days")
                val photos = repo.allPhotos().size
                views.setTextViewText(R.id.stat_photos, photos.toString())
                views.setTextViewText(R.id.stat_photos_label, if (photos == 1) "photo" else "photos")
                views.setTextViewText(R.id.action, "Start trip")
                views.setTextColor(R.id.action, 0xFF2A1100.toInt())
                views.setInt(R.id.action, "setBackgroundResource", R.drawable.widget_button_start)
                // Through the app, which asks for permissions and offers to name the trip.
                views.setOnClickPendingIntent(
                    R.id.action,
                    PendingIntent.getActivity(
                        context, 22,
                        Intent(context, MainActivity::class.java)
                            .setAction(MainActivity.ACTION_START_TRAVEL)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            return views
        }

        private fun distanceParts(m: Double): Pair<String, String> = when {
            m < 1_000 -> "%.0f".format(m) to "m"
            m < 100_000 -> "%.1f".format(m / 1_000) to "km"
            else -> "%,.0f".format(m / 1_000) to "km"
        }

        private fun formatWholeKm(m: Double): String = when {
            m < 10_000 -> "%.1f".format(m / 1_000)
            else -> "%,.0f".format(m / 1_000)
        }

        private fun formatArea(km2: Double): String = when {
            km2 < 10 -> "%.2f".format(km2)
            km2 < 100 -> "%.1f".format(km2)
            else -> "%,.0f".format(km2)
        }
    }
}
