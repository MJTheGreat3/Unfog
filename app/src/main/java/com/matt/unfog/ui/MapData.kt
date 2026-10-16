package com.matt.unfog.ui

import com.matt.unfog.data.TrackPoint
import com.matt.unfog.data.TripPhoto
import com.matt.unfog.geo.Geo
import com.matt.unfog.geo.HexGrid
import com.matt.unfog.geo.TransportMode
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
    private val fogGrid = HexGrid(30.0)
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

    /** Route lines split wherever the inferred transport mode changes. */
    fun routes(points: List<TrackPoint>): FeatureCollection {
        val features = mutableListOf<Feature>()
        var run = mutableListOf<Point>()
        var runMode: TransportMode? = null

        fun flush() {
            if (run.size >= 2 && runMode != null) {
                features += Feature.fromGeometry(LineString.fromLngLats(run)).apply {
                    addStringProperty("color", runMode!!.color)
                    addStringProperty("mode", runMode!!.name)
                }
            }
        }

        for ((a, b) in points.zipWithNext()) {
            if (a.tripId != b.tripId || Geo.isJump(a, b)) {
                flush()
                run = mutableListOf()
                runMode = null
                continue
            }
            val mode = segmentMode(a, b)
            if (mode != runMode) {
                flush()
                run = if (runMode == null) mutableListOf() else mutableListOf(run.last())
                runMode = mode
            }
            if (run.isEmpty()) run += Point.fromLngLat(a.lng, a.lat)
            run += Point.fromLngLat(b.lng, b.lat)
        }
        flush()
        return FeatureCollection.fromFeatures(features)
    }

    private fun segmentMode(a: TrackPoint, b: TrackPoint): TransportMode {
        val dt = (b.timeMs - a.timeMs) / 1000.0
        val speed = if (dt > 0) Geo.distance(a, b) / dt else b.speedMps.toDouble()
        return TransportMode.of(speed)
    }

    fun photos(photos: List<TripPhoto>): FeatureCollection =
        FeatureCollection.fromFeatures(photos.filter { it.lat != null && it.lng != null }.map {
            Feature.fromGeometry(Point.fromLngLat(it.lng!!, it.lat!!)).apply {
                addNumberProperty("id", it.id)
                addNumberProperty("trip", it.tripId)
            }
        })

    /** Centres of small cells you have passed through: holes to punch in the fog. */
    fun fogHoles(points: List<TrackPoint>): List<LatLng> =
        Geo.densify(points, 25.0)
            .map { fogGrid.cellOf(it.lat, it.lng) }
            .distinct()
            .map { fogGrid.center(it).let { (lat, lng) -> LatLng(lat, lng) } }

    class Explored(val areaKm2: Double, val cells: Int, val days: Int, val trips: Int)

    fun explored(points: List<TrackPoint>): Explored {
        val cells = Geo.densify(points, 40.0).map { exploredGrid.cellOf(it.lat, it.lng) }.toSet()
        val area = cells.sumOf { exploredGrid.areaM2(exploredGrid.center(it).first) } / 1e6
        val zone = ZoneId.systemDefault()
        val days = points.map { Instant.ofEpochMilli(it.timeMs).atZone(zone).toLocalDate() }.toSet()
        return Explored(area, cells.size, days.size, points.map { it.tripId }.toSet().size)
    }
}
