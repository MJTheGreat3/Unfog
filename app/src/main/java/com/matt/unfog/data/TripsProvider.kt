package com.matt.unfog.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.matt.unfog.repository

/**
 * Read-only view of trips for other apps (Kaizen shows them as journeys).
 * Reading needs the `READ_TRIPS` permission, which Android asks you to grant.
 *
 * `content://com.matt.unfog.trips/trips?since=<ms>` gives trips that started
 * at or after `since`, newest first: id, name, start_ms, end_ms (null while
 * recording), distance_m and photo_count. Points and photos stay private.
 */
class TripsProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.matt.unfog.trips"
        private const val TRIPS = 1
        val COLUMNS = arrayOf("id", "name", "start_ms", "end_ms", "distance_m", "photo_count")
    }

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply { addURI(AUTHORITY, "trips", TRIPS) }

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (matcher.match(uri) != TRIPS) return null
        val since = uri.getQueryParameter("since")?.toLongOrNull() ?: 0L
        val cursor = MatrixCursor(COLUMNS)
        for (t in context!!.repository.tripsSince(since)) {
            cursor.addRow(arrayOf<Any?>(t.id, t.name, t.startMs, t.endMs, t.distanceM, t.photoCount))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? =
        if (matcher.match(uri) == TRIPS) "vnd.android.cursor.dir/vnd.$AUTHORITY.trip" else null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read only")
}
