package com.matt.unfog.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.matt.unfog.data.HomeSpot
import com.matt.unfog.homeSpot
import com.matt.unfog.tracking.HomeFence
import com.matt.unfog.tracking.TravelMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Saves [spot] and brings the system geofence in line with it. */
fun saveHomeSpot(context: Context, spot: HomeSpot?) {
    context.homeSpot.set(spot)
    HomeFence.sync(context)
}

@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Pair<Double, Double>? {
    if (!TravelMode.hasLocationPermission(context)) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    val loc = runCatching { client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await() }
        .getOrNull() ?: runCatching { client.lastLocation.await() }.getOrNull()
    return loc?.let { it.latitude to it.longitude }
}

@Composable
fun HomeSpotDialog(onDismiss: () -> Unit, onPickOnMap: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spot by context.homeSpot.spot.collectAsStateWithLifecycle()
    var background by remember { mutableStateOf(HomeFence.hasBackgroundLocation(context)) }
    var locating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // "Allow all the time" is granted on a separate settings screen; re-check when we come back.
    LifecycleResumeEffect(Unit) {
        background = HomeFence.hasBackgroundLocation(context)
        if (background) HomeFence.sync(context)
        onPauseOrDispose { }
    }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        background = granted
        HomeFence.sync(context)
    }
    val fineLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    fun useMyLocation() {
        if (!TravelMode.hasLocationPermission(context)) {
            fineLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            ))
            return
        }
        locating = true
        error = null
        scope.launch {
            val here = currentLocation(context)
            locating = false
            if (here == null) {
                error = "Couldn't get your location. Try again outdoors, or pick on the map."
            } else {
                val base = spot ?: HomeSpot(here.first, here.second)
                saveHomeSpot(context, base.copy(lat = here.first, lng = here.second))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Home spot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val s = spot
                if (s == null) {
                    Text(
                        "Set where home is. Unfog can offer to start a trip when you leave, " +
                            "and end it when you get back.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    var radius by remember(s.radiusM) { mutableFloatStateOf(s.radiusM) }
                    Text("Radius: ${radius.toInt()} m", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = radius,
                        onValueChange = { radius = it },
                        onValueChangeFinished = { saveHomeSpot(context, s.copy(radiusM = radius)) },
                        valueRange = 100f..600f,
                        steps = 9,
                    )
                    ToggleRow("Ask to start a trip when I leave", s.askOnLeave) {
                        saveHomeSpot(context, s.copy(askOnLeave = it))
                        if (it && !background) backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    if (s.askOnLeave && !background) {
                        Text(
                            "Needs location access set to \"Allow all the time\" so Unfog notices " +
                                "you leaving while it's closed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        OutlinedButton(onClick = {
                            backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }) { Text("Allow all the time") }
                    }
                    ToggleRow("End the trip when I get home", s.autoStop) {
                        saveHomeSpot(context, s.copy(autoStop = it))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = ::useMyLocation, enabled = !locating) {
                        Text(if (locating) "Locating…" else if (s == null) "Use my location" else "Move here")
                    }
                    OutlinedButton(onClick = onPickOnMap) { Text("Pick on map") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            if (spot != null) {
                TextButton(onClick = { saveHomeSpot(context, null) }) { Text("Remove home") }
            }
        },
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
