package com.matt.unfog.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Climate-style heatmap of where photos were taken, drawn over the map. Each photo adds a soft
 * blob to an offscreen density buffer; the summed density is then coloured blue → green →
 * yellow → red. Rendered at a third of screen resolution and scaled up, which also smooths it.
 */
@Composable
fun HeatOverlay(
    map: MapLibreMap,
    points: List<LatLng>,
    modifier: Modifier = Modifier,
    radiusM: Double = 350.0,
) {
    var frame by remember { mutableIntStateOf(0) }
    DisposableEffect(map) {
        val move = MapLibreMap.OnCameraMoveListener { frame++ }
        val idle = MapLibreMap.OnCameraIdleListener { frame++ }
        map.addOnCameraMoveListener(move)
        map.addOnCameraIdleListener(idle)
        onDispose {
            map.removeOnCameraMoveListener(move)
            map.removeOnCameraIdleListener(idle)
        }
    }
    val renderer = remember { HeatRenderer() }

    Canvas(modifier) {
        if (frame < 0) return@Canvas // read so camera moves trigger a redraw
        if (points.isEmpty()) return@Canvas
        val out = renderer.render(map, points, size.width.roundToInt(), size.height.roundToInt(), radiusM)
            ?: return@Canvas
        // Fade when zoomed right in, so the photo pins underneath stay visible.
        val zoom = map.cameraPosition.zoom
        renderer.scalePaint.alpha = (255 * (1 - ((zoom - 14) / 3).coerceIn(0.0, 0.55))).roundToInt()
        drawIntoCanvas {
            it.nativeCanvas.drawBitmap(
                out, null, Rect(0, 0, size.width.roundToInt(), size.height.roundToInt()), renderer.scalePaint,
            )
        }
    }
}

private class HeatRenderer {
    private val scale = 3
    private var density: Bitmap? = null
    private var colored: Bitmap? = null
    private var bytes: ByteBuffer? = null
    private var pixels: IntArray? = null
    val scalePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Add blobs together so overlapping photos build up density.
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val lut = buildLut()

    fun render(map: MapLibreMap, points: List<LatLng>, width: Int, height: Int, radiusM: Double): Bitmap? {
        val w = (width / scale).coerceAtLeast(1)
        val h = (height / scale).coerceAtLeast(1)
        if (density?.width != w || density?.height != h) {
            density = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
            colored = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bytes = ByteBuffer.allocate(density!!.rowBytes * h)
            pixels = IntArray(w * h)
        }
        val dens = density!!
        dens.eraseColor(0)
        val canvas = AndroidCanvas(dens)

        val projection = map.projection
        val mpp = projection.getMetersPerPixelAtLatitude(map.cameraPosition.target?.latitude ?: 0.0)
        // Radius in full-resolution pixels, kept readable at every zoom, then scaled down.
        val r = ((radiusM / mpp).toFloat().coerceIn(28f, 160f)) / scale
        val bounds = projection.visibleRegion.latLngBounds
        val padLat = bounds.latitudeSpan * 0.25
        val padLng = bounds.longitudeSpan * 0.25

        for (p in points) {
            if (p.latitude < bounds.latitudeSouth - padLat || p.latitude > bounds.latitudeNorth + padLat ||
                p.longitude < bounds.longitudeWest - padLng || p.longitude > bounds.longitudeEast + padLng
            ) continue
            val s = projection.toScreenLocation(p)
            val x = s.x / scale
            val y = s.y / scale
            // Each photo contributes up to ~30% density at its centre, fading to nothing.
            blobPaint.shader = RadialGradient(
                x, y, r, intArrayOf(0x4D000000, 0x26000000, 0x00000000), floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(x, y, r, blobPaint)
        }

        val buf = bytes!!
        buf.rewind()
        dens.copyPixelsToBuffer(buf)
        val px = pixels!!
        val stride = dens.rowBytes
        val arr = buf.array()
        for (row in 0 until h) {
            val base = row * stride
            val outBase = row * w
            for (col in 0 until w) px[outBase + col] = lut[arr[base + col].toInt() and 0xFF]
        }
        colored!!.setPixels(px, 0, w, 0, 0, w, h)
        return colored
    }

    /** Density (0–255) to colour: transparent, then blue, cyan, green, yellow, orange, red. */
    private fun buildLut(): IntArray {
        val stops = listOf(
            0.00f to argb(0, 43, 131, 186),
            0.08f to argb(110, 43, 131, 186),
            0.25f to argb(165, 0, 190, 220),
            0.42f to argb(190, 100, 200, 90),
            0.58f to argb(205, 255, 230, 60),
            0.75f to argb(215, 253, 150, 50),
            1.00f to argb(230, 215, 25, 28),
        )
        return IntArray(256) { i ->
            val t = i / 255f
            val hi = stops.indexOfFirst { it.first >= t }.coerceAtLeast(1)
            val (t0, c0) = stops[hi - 1]
            val (t1, c1) = stops[hi]
            lerp(c0, c1, ((t - t0) / (t1 - t0)).coerceIn(0f, 1f))
        }
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun lerp(c0: Int, c1: Int, f: Float): Int {
        fun ch(c: Int, shift: Int) = (c ushr shift) and 0xFF
        fun mix(shift: Int) = (ch(c0, shift) + (ch(c1, shift) - ch(c0, shift)) * f).roundToInt()
        return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }
}
