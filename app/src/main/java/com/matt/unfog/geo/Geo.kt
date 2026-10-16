package com.matt.unfog.geo

import com.matt.unfog.data.TrackPoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

object Geo {
    private const val EARTH_R = 6_371_008.8
    private const val MERC_R = 6_378_137.0

    fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_R * asin(sqrt(min(1.0, a)))
    }

    fun distance(a: TrackPoint, b: TrackPoint) = distance(a.lat, a.lng, b.lat, b.lng)

    fun pathLength(points: List<TrackPoint>): Double =
        points.zipWithNext().sumOf { (a, b) -> if (isJump(a, b)) 0.0 else distance(a, b) }

    /**
     * A segment we should not draw or count as explored ground: a long gap in the signal or a
     * flight. Everything between the two ends would otherwise be "unfogged".
     */
    fun isJump(a: TrackPoint, b: TrackPoint): Boolean {
        val d = distance(a, b)
        val dt = (b.timeMs - a.timeMs).coerceAtLeast(1) / 1000.0
        return d > 5_000 || (d > 1_000 && d / dt > 70) // > 250 km/h
    }

    // ---- Web Mercator ----

    fun mercX(lng: Double) = MERC_R * Math.toRadians(lng)
    fun mercY(lat: Double) = MERC_R * ln(tan(PI / 4 + Math.toRadians(lat.coerceIn(-85.0, 85.0)) / 2))
    fun lngOf(x: Double) = Math.toDegrees(x / MERC_R)
    fun latOf(y: Double) = Math.toDegrees(2 * atan(exp(y / MERC_R)) - PI / 2)

    // ---- simplification ----

    /** Douglas-Peucker on a local equirectangular projection. [tolerance] is in metres. */
    fun simplify(points: List<TrackPoint>, tolerance: Double = 8.0): List<TrackPoint> {
        if (points.size < 3) return points
        val lat0 = Math.toRadians(points.first().lat)
        val xs = DoubleArray(points.size) { Math.toRadians(points[it].lng) * cos(lat0) * EARTH_R }
        val ys = DoubleArray(points.size) { Math.toRadians(points[it].lat) * EARTH_R }
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var maxD = 0.0
            var idx = -1
            for (i in s + 1 until e) {
                val d = segmentDistance(xs[i], ys[i], xs[s], ys[s], xs[e], ys[e])
                if (d > maxD) {
                    maxD = d
                    idx = i
                }
            }
            if (idx != -1 && maxD > tolerance) {
                keep[idx] = true
                stack.addLast(s to idx)
                stack.addLast(idx to e)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    private fun segmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return sqrt(cx * cx + cy * cy)
    }

    /**
     * Fills in points along each segment so no two consecutive points are more than [step]
     * metres apart. Used for the fog and hex maps, where a fast car with sparse fixes would
     * otherwise leave dotted holes.
     */
    fun densify(points: List<TrackPoint>, step: Double = 50.0): List<TrackPoint> {
        if (points.size < 2) return points
        val out = ArrayList<TrackPoint>(points.size * 2)
        out += points.first()
        for ((a, b) in points.zipWithNext()) {
            if (a.tripId == b.tripId && !isJump(a, b)) {
                val d = distance(a, b)
                val n = (d / step).toInt()
                for (i in 1..n) {
                    val f = i.toDouble() / (n + 1)
                    out += a.copy(
                        lat = a.lat + (b.lat - a.lat) * f,
                        lng = a.lng + (b.lng - a.lng) * f,
                        timeMs = a.timeMs + ((b.timeMs - a.timeMs) * f).roundToLong(),
                    )
                }
            }
            out += b
        }
        return out
    }

    /** Point on the track nearest in time to [timeMs], or null if the gap is over [maxGapMs]. */
    fun positionAt(points: List<TrackPoint>, timeMs: Long, maxGapMs: Long = 30 * 60_000L): TrackPoint? {
        if (points.isEmpty()) return null
        var lo = 0
        var hi = points.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (points[mid].timeMs < timeMs) lo = mid + 1 else hi = mid
        }
        val best = listOfNotNull(points.getOrNull(lo - 1), points.getOrNull(lo))
            .minBy { abs(it.timeMs - timeMs) }
        return best.takeIf { abs(it.timeMs - timeMs) <= maxGapMs }
    }

    fun bounds(points: List<Pair<Double, Double>>): DoubleArray? {
        if (points.isEmpty()) return null
        var minLat = 90.0; var maxLat = -90.0; var minLng = 180.0; var maxLng = -180.0
        for ((lat, lng) in points) {
            minLat = min(minLat, lat); maxLat = max(maxLat, lat)
            minLng = min(minLng, lng); maxLng = max(maxLng, lng)
        }
        return doubleArrayOf(minLat, minLng, maxLat, maxLng)
    }
}

enum class TransportMode(val label: String, val color: String) {
    Still("Still", "#9AA3B5"),
    Walk("Walk", "#4CC38A"),
    Bike("Bike", "#3FB8E8"),
    Road("Road", "#FFB13D"),
    Fast("Train / highway", "#F0567A"),
    Flight("Flight", "#B07CFF");

    companion object {
        fun of(speedMps: Double): TransportMode = when {
            speedMps < 0.5 -> Still
            speedMps < 2.5 -> Walk
            speedMps < 7 -> Bike
            speedMps < 28 -> Road
            speedMps < 70 -> Fast
            else -> Flight
        }
    }
}
