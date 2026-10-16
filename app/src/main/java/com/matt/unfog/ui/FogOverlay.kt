package com.matt.unfog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

/**
 * Fog of war drawn over the map: an opaque layer with soft holes punched out wherever you
 * have been. Redrawn on every camera move.
 */
@Composable
fun FogOverlay(
    map: MapLibreMap,
    holes: List<LatLng>,
    modifier: Modifier = Modifier,
    radiusM: Double = 140.0,
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

    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        if (frame < 0) return@Canvas // read so camera moves trigger a redraw
        drawRect(fog)
        val projection = map.projection
        val bounds = projection.visibleRegion.latLngBounds
        val mpp = projection.getMetersPerPixelAtLatitude(map.cameraPosition.target?.latitude ?: 0.0)
        // Never shrink holes below a few pixels, or the explored area vanishes when zoomed out.
        val r = (radiusM / mpp).toFloat().coerceAtLeast(5f)
        val padLat = (bounds.latitudeSpan * 0.1)
        val padLng = (bounds.longitudeSpan * 0.1)
        for (h in holes) {
            if (h.latitude < bounds.latitudeSouth - padLat || h.latitude > bounds.latitudeNorth + padLat ||
                h.longitude < bounds.longitudeWest - padLng || h.longitude > bounds.longitudeEast + padLng
            ) continue
            val p = projection.toScreenLocation(h)
            val c = Offset(p.x, p.y)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent,
                    center = c, radius = r,
                ),
                radius = r, center = c, blendMode = BlendMode.DstOut,
            )
        }
    }
}
