package com.matt.unfog.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import kotlinx.coroutines.withTimeoutOrNull

/** Saves [spot] and brings the system geofences in line. */
fun saveHomeSpot(context: Context, spot: HomeSpot) {
    context.homeSpot.put(spot)
    HomeFence.sync(context)
}

fun removeHomeSpot(context: Context, id: String) {
    context.homeSpot.remove(id)
    HomeFence.sync(context)
}

/** A fresh spot at the given position, named "Home" if it's the first one. */
fun newHomeSpot(context: Context, lat: Double, lng: Double): HomeSpot {
    val count = context.homeSpot.spots.value.size
    return HomeSpot(lat, lng, name = if (count == 0) "Home" else "Place ${count + 1}")
}

@SuppressLint("MissingPermission")
private suspend fun currentLocation(context: Context): Pair<Double, Double>? {
    if (!TravelMode.hasLocationPermission(context)) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    val loc = withTimeoutOrNull(20_000) {
        runCatching { client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await() }.getOrNull()
    } ?: runCatching { client.lastLocation.await() }.getOrNull()
    return loc?.let { it.latitude to it.longitude }
}

/**
 * Lists the saved places and edits one. [onPickOnMap] hands control to the map to place a spot:
 * with an id to move that spot, or null to add a new one.
 */
@Composable
fun HomeSpotDialog(
    initialEditId: String?,
    onDismiss: () -> Unit,
    onPickOnMap: (String?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spots by context.homeSpot.spots.collectAsStateWithLifecycle()
    var editingId by remember { mutableStateOf(initialEditId) }
    var background by remember { mutableStateOf(HomeFence.hasBackgroundLocation(context)) }
    var fullScreen by remember { mutableStateOf(HomeFence.canUseFullScreen(context)) }
    var locating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // "Allow all the time" is granted on a separate settings screen; re-check when we come back.
    LifecycleResumeEffect(Unit) {
        background = HomeFence.hasBackgroundLocation(context)
        fullScreen = HomeFence.canUseFullScreen(context)
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

    /** Puts the spot with [id] (or a new one, when null) at the current location. */
    fun placeHere(id: String?) {
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
                return@launch
            }
            val spot = id?.let { context.homeSpot.get(it) }?.copy(lat = here.first, lng = here.second)
                ?: newHomeSpot(context, here.first, here.second)
            saveHomeSpot(context, spot)
            editingId = spot.id
        }
    }

    val editing = spots.firstOrNull { it.id == editingId }
    val needsBackground = !background && spots.any { it.askOnLeave }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing == null) "Places" else "Edit place") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (editing == null) {
                    if (spots.isEmpty()) {
                        Text(
                            "Save home, work or anywhere you return to. Unfog can offer to start " +
                                "a trip when you leave one, and to end it when you arrive at one.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    spots.forEach { spot ->
                        Surface(
                            onClick = { editingId = spot.id },
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(spot.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        listOfNotNull(
                                            "${spot.radiusM.toInt()} m",
                                            "asks when leaving".takeIf { spot.askOnLeave },
                                            "asks when arriving".takeIf { spot.autoStop },
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
                            }
                        }
                    }
                    if (needsBackground) BackgroundNotice { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                    if (!fullScreen && spots.any { it.askOnLeave || it.autoStop }) FullScreenNotice {
                        if (Build.VERSION.SDK_INT >= 34) context.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", context.packageName, null))
                        )
                    }
                    FilledTonalButton(
                        onClick = { placeHere(null) }, enabled = !locating,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (locating) "Locating…" else "Add my current location") }
                    OutlinedButton(onClick = { onPickOnMap(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Add by tapping the map")
                    }
                } else {
                    var name by remember(editing.id) { mutableStateOf(editing.name) }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // Saved when leaving the editor, so typing doesn't rewrite prefs per keystroke.
                    val id = editing.id
                    fun commitName() {
                        // Read the stored spot, not [editing], which may predate other changes.
                        val stored = context.homeSpot.get(id) ?: return
                        if (name.isNotBlank() && name.trim() != stored.name) {
                            saveHomeSpot(context, stored.copy(name = name.trim()))
                        }
                    }
                    var radius by remember(editing.id, editing.radiusM) { mutableFloatStateOf(editing.radiusM) }
                    Text("Radius: ${radius.toInt()} m", style = MaterialTheme.typography.labelLarge)
                    Slider(
                        value = radius,
                        onValueChange = { radius = it },
                        onValueChangeFinished = { saveHomeSpot(context, editing.copy(radiusM = radius)) },
                        valueRange = 100f..600f,
                        steps = 9,
                    )
                    ToggleRow("Ask to start a trip when I leave", editing.askOnLeave) {
                        saveHomeSpot(context, editing.copy(askOnLeave = it))
                        if (it && !background) backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    if (editing.askOnLeave && !background) {
                        BackgroundNotice { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
                    }
                    ToggleRow("Ask to end the trip when I arrive", editing.autoStop) {
                        saveHomeSpot(context, editing.copy(autoStop = it))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { commitName(); placeHere(editing.id) }, enabled = !locating) {
                            Text(if (locating) "Locating…" else "Move here")
                        }
                        OutlinedButton(onClick = { commitName(); onPickOnMap(editing.id) }) { Text("Move on map") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { commitName(); editingId = null }) { Text("All places") }
                        TextButton(onClick = { removeHomeSpot(context, editing.id); editingId = null }) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    // Persist the name if the dialog is closed from here.
                    androidx.compose.runtime.DisposableEffect(editing.id) { onDispose { commitName() } }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun FullScreenNotice(onAllow: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Place alarms can only show as a notification. Allow full-screen alerts so they " +
                "ring over the lock screen like an alarm.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        OutlinedButton(onClick = onAllow) { Text("Allow full-screen alerts") }
    }
}

@Composable
private fun BackgroundNotice(onAllow: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "To notice you leaving while Unfog is closed, location access must be set to " +
                "\"Allow all the time\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        OutlinedButton(onClick = onAllow) { Text("Allow all the time") }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
