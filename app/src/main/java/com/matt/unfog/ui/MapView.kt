package com.matt.unfog.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.matt.unfog.tracking.TravelMode
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"

/** A loaded map and its style. Both are only valid while the composable is on screen. */
class MapHandle(val map: MapLibreMap, val style: Style) {
    fun enableLocation(context: Context) = enableLocation(context, map, style)

    fun lastKnown(): org.maplibre.android.geometry.LatLng? = runCatching {
        if (!map.locationComponent.isLocationComponentActivated) return null
        map.locationComponent.lastKnownLocation?.let { org.maplibre.android.geometry.LatLng(it.latitude, it.longitude) }
    }.getOrNull()
}

/**
 * MapLibre MapView hosted in Compose. [onReady] runs once the style has loaded; the returned
 * handle is null until then.
 */
@Composable
fun rememberMap(modifier: Modifier = Modifier, onReady: (MapHandle) -> Unit = {}): MapHandle? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var handle by remember { mutableStateOf<MapHandle?>(null) }
    val mapView = remember {
        MapView(context).apply {
            onCreate(Bundle())
            getMapAsync { map ->
                map.uiSettings.isRotateGesturesEnabled = false
                map.uiSettings.isTiltGesturesEnabled = false
                map.uiSettings.isAttributionEnabled = true
                map.uiSettings.isLogoEnabled = false
                map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                    MapLayers.install(style)
                    val h = MapHandle(map, style)
                    handle = h
                    onReady(h)
                }
            }
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
    return handle
}

@SuppressLint("MissingPermission")
private fun enableLocation(context: Context, map: MapLibreMap, style: Style) {
    if (!TravelMode.hasLocationPermission(context)) return
    if (map.locationComponent.isLocationComponentActivated) return
    runCatching {
        map.locationComponent.apply {
            activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .useDefaultLocationEngine(true)
                    .build()
            )
            isLocationComponentEnabled = true
            cameraMode = CameraMode.NONE
            renderMode = RenderMode.COMPASS
        }
    }
}
