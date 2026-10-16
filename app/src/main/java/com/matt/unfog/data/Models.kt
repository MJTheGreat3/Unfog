package com.matt.unfog.data

data class Trip(
    val id: Long,
    val name: String,
    val startMs: Long,
    val endMs: Long?,
    val distanceM: Double,
    val photoCount: Int = 0,
) {
    val isActive get() = endMs == null
}

data class TrackPoint(
    val tripId: Long,
    val timeMs: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val speedMps: Float,
)

data class TripPhoto(
    val id: Long,
    val tripId: Long,
    val uri: String,
    val takenMs: Long,
    val lat: Double?,
    val lng: Double?,
    /** True when the position came from EXIF rather than being matched to the track. */
    val fromExif: Boolean,
    val hidden: Boolean,
)
