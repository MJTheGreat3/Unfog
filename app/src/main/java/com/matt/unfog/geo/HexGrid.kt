package com.matt.unfog.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pointy-top hexagonal grid laid over Web Mercator metres. Cells look regular on the map at
 * any latitude; their ground size shrinks towards the poles, which is fine for a personal map.
 */
class HexGrid(private val size: Double) {

    data class Cell(val q: Int, val r: Int)

    fun cellOf(lat: Double, lng: Double): Cell {
        val x = Geo.mercX(lng)
        val y = Geo.mercY(lat)
        val q = (sqrt(3.0) / 3 * x - y / 3) / size
        val r = (2.0 / 3 * y) / size
        return round(q, r)
    }

    fun center(cell: Cell): Pair<Double, Double> {
        val x = size * (sqrt(3.0) * cell.q + sqrt(3.0) / 2 * cell.r)
        val y = size * (1.5 * cell.r)
        return Geo.latOf(y) to Geo.lngOf(x)
    }

    /** Corners as (lng, lat), closed ring, ready for GeoJSON. */
    fun ring(cell: Cell): List<Pair<Double, Double>> {
        val cx = size * (sqrt(3.0) * cell.q + sqrt(3.0) / 2 * cell.r)
        val cy = size * (1.5 * cell.r)
        val corners = (0..5).map { i ->
            val a = Math.toRadians(60.0 * i - 30)
            Geo.lngOf(cx + size * cos(a)) to Geo.latOf(cy + size * sin(a))
        }
        return corners + corners.first()
    }

    /** Approximate ground area of a cell at [lat], in square metres. */
    fun areaM2(lat: Double): Double {
        val ground = size * cos(Math.toRadians(lat))
        return 1.5 * sqrt(3.0) * ground * ground
    }

    private fun round(q: Double, r: Double): Cell {
        val s = -q - r
        var rq = q.roundToInt()
        var rr = r.roundToInt()
        val rs = s.roundToInt()
        val dq = abs(rq - q)
        val dr = abs(rr - r)
        val ds = abs(rs - s)
        if (dq > dr && dq > ds) rq = -rr - rs else if (dr > ds) rr = -rq - rs
        return Cell(rq, rr)
    }
}
