package com.matt.unfog.ui

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import org.maplibre.android.maps.MapLibreMap

/**
 * Fog of war drawn over the map: an opaque layer with a wide, soft-edged corridor erased along
 * every track. Redrawn on every camera move.
 */
@Composable
fun FogOverlay(
    map: MapLibreMap,
    paths: List<MapData.FogPath>,
    modifier: Modifier = Modifier,
    radiusM: Double = 260.0,
    fog: Color = Color(0xE6161A23),
) {
    var frame by remember { mutableIntStateOf(0) }
    DisposableEffect(map) {
        val listener = MapLibreMap.OnCameraMoveListener { frame++ }
        val idle = MapLibreMap.OnCameraIdleListener { frame++ }
        map.addOnCameraMoveListener(listener)
        map.addOnCameraIdleListener(idle)
        onDispose {
            map.removeOnCameraMoveListener(listener)
            map.removeOnCameraIdleListener(idle)
        }
    }
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = android.graphics.Color.BLACK
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
    }
    val path = remember { Path() }

    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        if (frame < 0) return@Canvas // read so camera moves trigger a redraw
        drawRect(fog)
        val projection = map.projection
        val bounds = projection.visibleRegion.latLngBounds
        val mpp = projection.getMetersPerPixelAtLatitude(map.cameraPosition.target?.latitude ?: 0.0)
        // Keep the corridor visible when zoomed out to country level.
        val r = (radiusM / mpp).toFloat().coerceAtLeast(6f)
        // Margin in degrees so corridors just off-screen still bleed in at the edges.
        val marginLat = bounds.latitudeSpan * r * 2 / size.height
        val marginLng = bounds.longitudeSpan * r * 2 / size.width

        // Solid core plus a blurred halo gives a hard-ish centre that feathers out smoothly.
        val blur = (r * 0.6f).coerceAtLeast(1f)
        paint.strokeWidth = r * 1.6f
        paint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)

        drawIntoCanvas { canvas ->
            for (p in paths) {
                if (p.north < bounds.latitudeSouth - marginLat || p.south > bounds.latitudeNorth + marginLat ||
                    p.east < bounds.longitudeWest - marginLng || p.west > bounds.longitudeEast + marginLng
                ) continue
                path.rewind()
                var lastX = Float.NaN
                var lastY = Float.NaN
                for ((i, ll) in p.points.withIndex()) {
                    val s = projection.toScreenLocation(ll)
                    if (i == 0) {
                        path.moveTo(s.x, s.y)
                    } else if (kotlin.math.abs(s.x - lastX) + kotlin.math.abs(s.y - lastY) >= 2f) {
                        path.lineTo(s.x, s.y)
                    } else {
                        continue
                    }
                    lastX = s.x
                    lastY = s.y
                }
                // A path with a single point still needs to clear a round spot.
                if (p.points.size == 1) path.lineTo(lastX + 0.1f, lastY)
                canvas.nativeCanvas.drawPath(path, paint)
            }
        }
    }
}
