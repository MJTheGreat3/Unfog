package com.matt.unfog.ui

import com.matt.unfog.data.HomeSpot
import com.matt.unfog.data.TrackPoint
import com.matt.unfog.data.Trip
import com.matt.unfog.data.TripPhoto
import com.matt.unfog.geo.Geo
import com.matt.unfog.geo.HexGrid
import org.maplibre.android.geometry.LatLng
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.time.Instant
import java.time.ZoneId

/** Turns stored tracks and photos into what each map mode draws. Pure; run off the main thread. */
object MapData {

    private val fineGrid = HexGrid(180.0)
    private val coarseGrid = HexGrid(2_000.0)
    private val exploredGrid = HexGrid(60.0)

    class Frequency(val fine: FeatureCollection, val coarse: FeatureCollection)

    /** Hexes coloured by the number of distinct days you were in them. */
    fun frequency(points: List<TrackPoint>): Frequency {
        val dense = Geo.densify(points, 60.0)
        val zone = ZoneId.systemDefault()
        fun build(grid: HexGrid): FeatureCollection {
            val days = HashMap<HexGrid.Cell, MutableSet<Long>>()
            for (p in dense) {
                val day = Instant.ofEpochMilli(p.timeMs).atZone(zone).toLocalDate().toEpochDay()
                days.getOrPut(grid.cellOf(p.lat, p.lng)) { HashSet() } += day
            }
            return FeatureCollection.fromFeatures(days.map { (cell, d) ->
                val ring = grid.ring(cell).map { (lng, lat) -> Point.fromLngLat(lng, lat) }
                Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
                    addNumberProperty("days", d.size)
                }
            })
        }
        return Frequency(build(fineGrid), build(coarseGrid))
    }

    /** Route lines, split wherever the trip changes or the signal jumps. */
    fun routes(points: List<TrackPoint>): FeatureCollection =
        FeatureCollection.fromFeatures(runs(points).map { run ->
            Feature.fromGeometry(LineString.fromLngLats(run.map { Point.fromLngLat(it.lng, it.lat) })).apply {
                addNumberProperty("trip", run.first().tripId)
            }
        })

    private fun runs(points: List<TrackPoint>): List<List<TrackPoint>> {
        val out = mutableListOf<List<TrackPoint>>()
        var run = mutableListOf<TrackPoint>()
        for (p in points) {
            val prev = run.lastOrNull()
            if (prev != null && (prev.tripId != p.tripId || Geo.isJump(prev, p))) {
                if (run.size >= 2) out += run
                run = mutableListOf()
            }
            run += p
        }
        if (run.size >= 2) out += run
        return out
    }

    /** A trip as it appears on one day: its colour that day and its display name. */
    class DayTrip(val id: Long, val name: String, val color: String)

    /** One day with travel in it, for the Days view. */
    class Day(
        val epochDay: Long,
        val trips: List<DayTrip>,
        val distanceM: Double,
        val routes: FeatureCollection,
        val bounds: DoubleArray,
        /** The day's track, in time order across trips, for replay. */
        val points: List<TrackPoint>,
        val photos: List<TripPhoto>,
    ) {
        fun colorOf(tripId: Long) = trips.firstOrNull { it.id == tripId }?.color ?: dayColors.first()
    }

    /** Colours that tell a day's trips apart. */
    val dayColors = listOf("#FF8A3D", "#3F7FE8", "#2EAA6E", "#E0218A", "#8A5CF6", "#E8B321")

    /**
     * Travel grouped by local calendar day, newest first. Each trip on a day gets its own colour,
     * in the order the trips started.
     */
    fun days(points: List<TrackPoint>, trips: List<Trip>, photos: List<TripPhoto>): List<Day> {
        val zone = ZoneId.systemDefault()
        fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()
        val names = trips.associate { it.id to it.name }
        val photosByDay = photos.groupBy { dayOf(it.takenMs) }
        return points
            .groupBy { dayOf(it.timeMs) }
            .map { (day, raw) ->
                val pts = raw.sortedBy { it.timeMs }
                val dayTrips = pts.map { it.tripId }.distinct().mapIndexed { i, id ->
                    DayTrip(id, names[id] ?: "Trip", dayColors[i % dayColors.size])
                }
                val colors = dayTrips.associate { it.id to it.color }
                val features = runs(raw).map { run ->
                    Feature.fromGeometry(LineString.fromLngLats(run.map { Point.fromLngLat(it.lng, it.lat) })).apply {
                        addStringProperty("color", colors.getValue(run.first().tripId))
                    }
                }
                val ids = colors.keys
                Day(
                    epochDay = day,
                    trips = dayTrips,
                    distanceM = Geo.pathLength(raw),
                    routes = FeatureCollection.fromFeatures(features),
                    bounds = Geo.bounds(pts.map { it.lat to it.lng })!!,
                    points = pts,
                    photos = photosByDay[day].orEmpty().filter { it.tripId in ids }.sortedBy { it.takenMs },
                )
            }
            .sortedByDescending { it.epochDay }
    }

    /** Photo positions for the heatmap. */
    fun photoHeat(photos: List<TripPhoto>): List<LatLng> =
        photos.filter { it.lat != null && it.lng != null }.map { LatLng(it.lat!!, it.lng!!) }

    fun photos(photos: List<TripPhoto>): FeatureCollection =
        FeatureCollection.fromFeatures(photos.filter { it.lat != null && it.lng != null }.map {
            Feature.fromGeometry(Point.fromLngLat(it.lng!!, it.lat!!)).apply {
                addNumberProperty("id", it.id)
                addNumberProperty("trip", it.tripId)
            }
        })

    /**
     * One pin per place in a trip, numbered by time order so the labels match the photo strip
     * and the replay. Consecutive photos within [mergeM] of each other share a pin ("4–7").
     */
    fun numberedPhotos(photos: List<TripPhoto>, mergeM: Double = 40.0): FeatureCollection {
        val features = mutableListOf<Feature>()
        var groupStart = -1
        fun close(end: Int) {
            if (groupStart < 0) return
            val first = photos[groupStart]
            val label = if (end == groupStart) "${groupStart + 1}" else "${groupStart + 1}–${end + 1}"
            features += Feature.fromGeometry(Point.fromLngLat(first.lng!!, first.lat!!)).apply {
                addStringProperty("label", label)
                addNumberProperty("index", groupStart)
            }
            groupStart = -1
        }
        for (i in photos.indices) {
            val p = photos[i]
            if (p.lat == null || p.lng == null) {
                close(i - 1)
                continue
            }
            val start = photos.getOrNull(groupStart)
            if (start != null && Geo.distance(start.lat!!, start.lng!!, p.lat, p.lng) > mergeM) close(i - 1)
            if (groupStart < 0) groupStart = i
        }
        close(photos.lastIndex)
        return FeatureCollection.fromFeatures(features)
    }

    /** Each home spot as a filled circle plus a labelled centre point. */
    fun homes(spots: List<HomeSpot>): FeatureCollection =
        FeatureCollection.fromFeatures(spots.flatMap { spot -> homeFeatures(spot) })

    private fun homeFeatures(spot: HomeSpot): List<Feature> {
        val dLat = Math.toDegrees(spot.radiusM / 6_371_008.8)
        val dLng = dLat / kotlin.math.cos(Math.toRadians(spot.lat))
        val ring = (0..64).map { i ->
            val a = 2 * Math.PI * i / 64
            Point.fromLngLat(spot.lng + dLng * kotlin.math.cos(a), spot.lat + dLat * kotlin.math.sin(a))
        }
        return listOf(
            Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))),
            Feature.fromGeometry(Point.fromLngLat(spot.lng, spot.lat)).apply {
                addStringProperty("name", spot.name)
                addStringProperty("id", spot.id)
            },
        )
    }

    /** A continuous stretch of track, with its bounding box for cheap culling. */
    class FogPath(
        val points: List<LatLng>,
        val south: Double, val west: Double, val north: Double, val east: Double,
    )

    /** Tracks split wherever the trip changes or the signal jumps: what the fog is cleared along. */
    fun fogPaths(points: List<TrackPoint>): List<FogPath> {
        val out = mutableListOf<FogPath>()
        var run = mutableListOf<TrackPoint>()
        fun flush() {
            if (run.isEmpty()) return
            out += FogPath(
                run.map { LatLng(it.lat, it.lng) },
                run.minOf { it.lat }, run.minOf { it.lng }, run.maxOf { it.lat }, run.maxOf { it.lng },
            )
            run = mutableListOf()
        }
        for (p in points) {
            val prev = run.lastOrNull()
            if (prev != null && (prev.tripId != p.tripId || Geo.isJump(prev, p))) flush()
            run += p
        }
        flush()
        return out
    }

    class Explored(val areaKm2: Double, val cells: Int, val days: Int, val trips: Int)

    fun explored(points: List<TrackPoint>): Explored {
        val cells = Geo.densify(points, 40.0).map { exploredGrid.cellOf(it.lat, it.lng) }.toSet()
        val area = cells.sumOf { exploredGrid.areaM2(exploredGrid.center(it).first) } / 1e6
        val zone = ZoneId.systemDefault()
        val days = points.map { Instant.ofEpochMilli(it.timeMs).atZone(zone).toLocalDate() }.toSet()
        return Explored(area, cells.size, days.size, points.map { it.tripId }.toSet().size)
    }
}
