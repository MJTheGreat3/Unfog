package com.matt.unfog.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.matt.unfog.data.MapTheme
import com.matt.unfog.mapTheme
import com.matt.unfog.tracking.TravelMode
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style


/**
 * A loaded map and its style. Both are only valid while the composable is on screen; switching
 * the map theme loads a new style and produces a new handle.
 */
class MapHandle(val map: MapLibreMap, val style: Style, val theme: MapTheme) {
    fun enableLocation(context: Context) = enableLocation(context, map, style)

    fun lastKnown(): org.maplibre.android.geometry.LatLng? = runCatching {
        if (!map.locationComponent.isLocationComponentActivated) return null
        map.locationComponent.lastKnownLocation?.let { org.maplibre.android.geometry.LatLng(it.latitude, it.longitude) }
    }.getOrNull()
}

/**
 * MapLibre MapView hosted in Compose, styled by the chosen map theme. [onReady] runs once,
 * when the first style has loaded; the returned handle is null until then.
 */
@Composable
fun rememberMap(modifier: Modifier = Modifier, onReady: (MapHandle) -> Unit = {}): MapHandle? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val theme by context.mapTheme.theme.collectAsStateWithLifecycle()
    var handle by remember { mutableStateOf<MapHandle?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    val mapView = remember {
        MapView(context).apply {
            onCreate(Bundle())
            getMapAsync { m ->
                m.uiSettings.isRotateGesturesEnabled = false
                m.uiSettings.isTiltGesturesEnabled = false
                m.uiSettings.isAttributionEnabled = true
                m.uiSettings.isLogoEnabled = false
                map = m
            }
        }
    }
    LaunchedEffect(map, theme) {
        val m = map ?: return@LaunchedEffect
        val first = handle == null
        m.setStyle(Style.Builder().fromUri(theme.styleUrl)) { style ->
            MapLayers.install(style)
            val h = MapHandle(m, style, theme)
            handle = h
            if (first) onReady(h)
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
