package com.matt.unfog.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.matt.unfog.data.Trip
import com.matt.unfog.data.TripPhoto
import com.matt.unfog.geo.Geo
import com.matt.unfog.repository

/**
 * Pulls photos taken during a trip out of MediaStore. Photos stay where the camera put them;
 * we only keep their content URI and position.
 */
object PhotoImporter {

    fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
        else -> arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        )
    }

    fun canRead(context: Context): Boolean {
        val read = when {
            Build.VERSION.SDK_INT >= 34 -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= 33 -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return read.any { granted(context, it) }
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Returns the number of newly attached photos. Must run off the main thread. */
    fun import(context: Context, trip: Trip): Int {
        if (!canRead(context)) return 0
        val repo = context.repository
        val track = repo.points(trip.id)
        val end = trip.endMs ?: System.currentTimeMillis()
        val canReadGps = granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)

        val found = mutableListOf<TripPhoto>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val selection = "${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ?"
        val args = arrayOf(trip.startMs.toString(), end.toString())
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args,
            "${MediaStore.Images.Media.DATE_TAKEN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(2).orEmpty()
                if (path.contains("Screenshot", ignoreCase = true)) continue
                val id = c.getLong(0)
                val taken = c.getLong(1)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                val exif = if (canReadGps) readExifLatLng(context, uri) else null
                val pos = exif ?: Geo.positionAt(track, taken)?.let { it.lat to it.lng }
                found += TripPhoto(
                    id = 0,
                    tripId = trip.id,
                    uri = uri.toString(),
                    takenMs = taken,
                    lat = pos?.first,
                    lng = pos?.second,
                    fromExif = exif != null,
                    hidden = false,
                )
            }
        }
        return repo.addPhotos(found)
    }

    private fun readExifLatLng(context: Context, uri: android.net.Uri): Pair<Double, Double>? =
        runCatching {
            context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))?.use {
                ExifInterface(it).latLong?.let { ll -> ll[0] to ll[1] }
            }
        }.getOrNull()?.takeIf { (lat, lng) -> lat != 0.0 || lng != 0.0 }
}
