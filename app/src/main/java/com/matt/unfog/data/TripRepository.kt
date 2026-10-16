package com.matt.unfog.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.matt.unfog.geo.Geo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Single source of truth for trips, points and photos. Every write bumps [version], so
 * readers can re-query by mapping over it. The tracking service and UI share one process,
 * so an in-memory signal is enough.
 */
class TripRepository(context: Context) {

    private val db = UnfogDb(context.applicationContext)
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private fun changed() {
        _version.value++
    }

    private fun <T> observe(query: () -> T): Flow<T> =
        version.map { query() }.flowOn(Dispatchers.IO)

    // ---- trips ----

    fun startTrip(startMs: Long, name: String): Long {
        val id = db.writableDatabase.insertOrThrow("trips", null, ContentValues().apply {
            put("name", name)
            put("start_ms", startMs)
        })
        changed()
        return id
    }

    fun finishTrip(id: Long, endMs: Long, distanceM: Double) {
        db.writableDatabase.update("trips", ContentValues().apply {
            put("end_ms", endMs)
            put("distance_m", distanceM)
        }, "id = ?", arrayOf(id.toString()))
        changed()
    }

    fun updateDistance(id: Long, distanceM: Double) {
        db.writableDatabase.update("trips", ContentValues().apply {
            put("distance_m", distanceM)
        }, "id = ?", arrayOf(id.toString()))
        changed()
    }

    fun renameTrip(id: Long, name: String) {
        db.writableDatabase.update("trips", ContentValues().apply { put("name", name) },
            "id = ?", arrayOf(id.toString()))
        changed()
    }

    fun deleteTrip(id: Long) {
        db.writableDatabase.delete("trips", "id = ?", arrayOf(id.toString()))
        changed()
    }

    fun activeTrip(): Trip? = queryTrips("WHERE t.end_ms IS NULL").firstOrNull()

    fun trip(id: Long): Trip? = queryTrips("WHERE t.id = $id").firstOrNull()

    fun trips(): List<Trip> = queryTrips("")

    fun observeTrips(): Flow<List<Trip>> = observe { trips() }

    fun observeTrip(id: Long): Flow<Trip?> = observe { trip(id) }

    fun observeActiveTrip(): Flow<Trip?> = observe { activeTrip() }

    private fun queryTrips(where: String): List<Trip> =
        db.readableDatabase.rawQuery(
            """
            SELECT t.id, t.name, t.start_ms, t.end_ms, t.distance_m,
                   (SELECT COUNT(*) FROM photos p WHERE p.trip_id = t.id AND p.hidden = 0)
            FROM trips t $where
            ORDER BY t.start_ms DESC
            """, null
        ).use { c ->
            c.map {
                Trip(
                    id = c.getLong(0),
                    name = c.getString(1),
                    startMs = c.getLong(2),
                    endMs = if (c.isNull(3)) null else c.getLong(3),
                    distanceM = c.getDouble(4),
                    photoCount = c.getInt(5),
                )
            }
        }

    // ---- points ----

    fun addPoint(p: TrackPoint) {
        db.writableDatabase.insert("points", null, ContentValues().apply {
            put("trip_id", p.tripId)
            put("t_ms", p.timeMs)
            put("lat", p.lat)
            put("lng", p.lng)
            put("acc", p.accuracyM)
            put("speed", p.speedMps)
        })
        changed()
    }

    fun points(tripId: Long): List<TrackPoint> = queryPoints("WHERE trip_id = $tripId")

    fun allPoints(): List<TrackPoint> = queryPoints("")

    fun observePoints(tripId: Long): Flow<List<TrackPoint>> = observe { points(tripId) }

    fun observeAllPoints(): Flow<List<TrackPoint>> = observe { allPoints() }

    private fun queryPoints(where: String): List<TrackPoint> =
        db.readableDatabase.rawQuery(
            "SELECT trip_id, t_ms, lat, lng, acc, speed FROM points $where ORDER BY trip_id, t_ms",
            null
        ).use { c ->
            c.map {
                TrackPoint(c.getLong(0), c.getLong(1), c.getDouble(2), c.getDouble(3),
                    c.getFloat(4), c.getFloat(5))
            }
        }

    /** Replaces a finished trip's raw points with a simplified track. */
    fun replacePoints(tripId: Long, points: List<TrackPoint>) {
        val w = db.writableDatabase
        w.transaction {
            delete("points", "trip_id = ?", arrayOf(tripId.toString()))
            for (p in points) {
                insert("points", null, ContentValues().apply {
                    put("trip_id", p.tripId)
                    put("t_ms", p.timeMs)
                    put("lat", p.lat)
                    put("lng", p.lng)
                    put("acc", p.accuracyM)
                    put("speed", p.speedMps)
                })
            }
        }
        changed()
    }

    // ---- photos ----

    /** Inserts photos not already attached to the trip. Returns how many were new. */
    fun addPhotos(photos: List<TripPhoto>): Int {
        var added = 0
        db.writableDatabase.transaction {
            for (p in photos) {
                val id = insertWithOnConflict("photos", null, ContentValues().apply {
                    put("trip_id", p.tripId)
                    put("uri", p.uri)
                    put("taken_ms", p.takenMs)
                    if (p.lat != null && p.lng != null) {
                        put("lat", p.lat)
                        put("lng", p.lng)
                    }
                    put("from_exif", if (p.fromExif) 1 else 0)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if (id != -1L) added++
            }
        }
        if (added > 0) changed()
        return added
    }

    fun setPhotoHidden(id: Long, hidden: Boolean) {
        db.writableDatabase.update("photos", ContentValues().apply {
            put("hidden", if (hidden) 1 else 0)
        }, "id = ?", arrayOf(id.toString()))
        changed()
    }

    fun photos(tripId: Long, includeHidden: Boolean = false): List<TripPhoto> =
        queryPhotos("WHERE trip_id = $tripId" + if (includeHidden) "" else " AND hidden = 0")

    fun allPhotos(): List<TripPhoto> = queryPhotos("WHERE hidden = 0")

    fun observePhotos(tripId: Long, includeHidden: Boolean = false): Flow<List<TripPhoto>> =
        observe { photos(tripId, includeHidden) }

    fun observeAllPhotos(): Flow<List<TripPhoto>> = observe { allPhotos() }

    private fun queryPhotos(where: String): List<TripPhoto> =
        db.readableDatabase.rawQuery(
            "SELECT id, trip_id, uri, taken_ms, lat, lng, from_exif, hidden FROM photos $where ORDER BY taken_ms",
            null
        ).use { c ->
            c.map {
                TripPhoto(
                    id = c.getLong(0),
                    tripId = c.getLong(1),
                    uri = c.getString(2),
                    takenMs = c.getLong(3),
                    lat = if (c.isNull(4)) null else c.getDouble(4),
                    lng = if (c.isNull(5)) null else c.getDouble(5),
                    fromExif = c.getInt(6) == 1,
                    hidden = c.getInt(7) == 1,
                )
            }
        }

    fun trackDistance(tripId: Long): Double = Geo.pathLength(points(tripId))
}

private inline fun <T> Cursor.map(row: () -> T): List<T> {
    val out = ArrayList<T>(count)
    while (moveToNext()) out += row()
    return out
}

private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try {
        block()
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}
