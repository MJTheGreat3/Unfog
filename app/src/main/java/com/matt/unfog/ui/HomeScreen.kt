package com.matt.unfog.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.matt.unfog.data.Trip
import com.matt.unfog.data.MapTheme
import com.matt.unfog.homeSpot
import com.matt.unfog.mapTheme
import java.time.LocalDate
import com.matt.unfog.photos.PhotoImporter
import com.matt.unfog.tracking.TravelMode
import kotlinx.coroutines.delay
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds

@Composable
fun HomeScreen(
    startRequested: Boolean,
    onStartHandled: () -> Unit,
    onOpenTrips: () -> Unit,
    onOpenTrip: (Long) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by vm.map.collectAsStateWithLifecycle()
    val active by vm.activeTrip.collectAsStateWithLifecycle()
    val running by TravelMode.running.collectAsStateWithLifecycle()
    val tripCount by vm.tripCount.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(MapMode.Explored) }
    // Index into the newest-first day list shown in Days mode.
    var dayIndex by rememberSaveable { mutableStateOf(0) }
    var fitted by remember { mutableStateOf(false) }
    val homes by context.homeSpot.spots.collectAsStateWithLifecycle()
    var homeDialog by remember { mutableStateOf(false) }
    // Spot to open in the editor when the dialog shows.
    var editHomeId by remember { mutableStateOf<String?>(null) }
    var pickingHome by remember { mutableStateOf(false) }
    // Spot being moved by tapping the map; null while picking means adding a new one.
    var pickTarget by remember { mutableStateOf<String?>(null) }
    val picking by rememberUpdatedState(pickingHome)
    val pickingId by rememberUpdatedState(pickTarget)
    val currentMode by rememberUpdatedState(mode)
    val openTrip by rememberUpdatedState(onOpenTrip)
    var locationGranted by remember { mutableStateOf(TravelMode.hasLocationPermission(context)) }
    val theme by context.mapTheme.theme.collectAsStateWithLifecycle()
    var namingTrip by remember { mutableStateOf(false) }
    // Name typed in the start dialog, held across the permission prompt.
    var pendingName by rememberSaveable { mutableStateOf<String?>(null) }

    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }
    val travelLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            locationGranted = true
            TravelMode.start(context, pendingName)
            pendingName = null
            if (!PhotoImporter.canRead(context)) mediaLauncher.launch(PhotoImporter.mediaPermissions())
        }
    }
    fun startTravel(name: String? = null) {
        pendingName = name
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        travelLauncher.launch(perms.toTypedArray())
    }

    LaunchedEffect(startRequested) {
        if (startRequested) {
            startTravel()
            onStartHandled()
        }
    }
    // The process was killed mid-trip and the service did not come back: resume it.
    LaunchedEffect(active, running) {
        if (active != null && !running && TravelMode.hasLocationPermission(context)) {
            delay(1_500)
            if (!TravelMode.running.value) TravelMode.start(context)
        }
    }

    Box(Modifier.fillMaxSize()) {
        val handle = rememberMap(Modifier.fillMaxSize()) { h ->
            h.map.addOnMapClickListener { latLng ->
                if (picking) {
                    val spot = pickingId?.let { context.homeSpot.get(it) }
                        ?.copy(lat = latLng.latitude, lng = latLng.longitude)
                        ?: newHomeSpot(context, latLng.latitude, latLng.longitude)
                    saveHomeSpot(context, spot)
                    pickingHome = false
                    editHomeId = spot.id
                    homeDialog = true
                    return@addOnMapClickListener true
                }
                if (currentMode != MapMode.Photos) return@addOnMapClickListener false
                val pt = h.map.projection.toScreenLocation(latLng)
                val hits = h.map.queryRenderedFeatures(
                    pt, MapLayers.LAYER_PHOTO_CLUSTERS, MapLayers.LAYER_PHOTO_POINTS,
                )
                val f = hits.firstOrNull() ?: return@addOnMapClickListener false
                if (f.hasProperty("point_count")) {
                    h.map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, h.map.cameraPosition.zoom + 2))
                } else {
                    f.getNumberProperty("trip")?.toLong()?.let(openTrip)
                }
                true
            }
        }

        if (handle != null) {
            LaunchedEffect(handle, locationGranted) { if (locationGranted) handle.enableLocation(context) }
            LaunchedEffect(handle, state) {
                val s = state ?: return@LaunchedEffect
                MapLayers.set(handle.style, MapLayers.HEX_FINE, s.frequency.fine)
                MapLayers.set(handle.style, MapLayers.HEX_COARSE, s.frequency.coarse)
                MapLayers.set(handle.style, MapLayers.ROUTES, s.routes)
                MapLayers.set(handle.style, MapLayers.PHOTOS, s.photos)
                if (!fitted) {
                    val b = s.bounds
                    if (b != null) {
                        fitted = true
                        runCatching {
                            handle.map.moveCamera(CameraUpdateFactory.newLatLngBounds(
                                LatLngBounds.from(b[2], b[3], b[0], b[1]), 120,
                            ))
                        }
                        if (handle.map.cameraPosition.zoom > 15) {
                            handle.map.moveCamera(CameraUpdateFactory.zoomTo(15.0))
                        }
                    } else {
                        handle.lastKnown()?.let {
                            fitted = true
                            handle.map.moveCamera(CameraUpdateFactory.newLatLngZoom(it, 13.0))
                        }
                    }
                }
            }
            LaunchedEffect(handle, homes) {
                MapLayers.set(handle.style, MapLayers.HOME, MapData.homes(homes))
                MapLayers.show(handle.style, MapLayers.HOME, homes.isNotEmpty())
            }
            LaunchedEffect(handle, mode) {
                MapLayers.show(handle.style, MapLayers.HEX_FINE, mode == MapMode.Frequency)
                MapLayers.show(handle.style, MapLayers.HEX_COARSE, mode == MapMode.Frequency)
                MapLayers.show(handle.style, MapLayers.ROUTES, mode == MapMode.Routes)
                MapLayers.show(handle.style, MapLayers.PHOTOS, mode == MapMode.Photos)
                MapLayers.show(handle.style, MapLayers.DAY_ROUTES, mode == MapMode.Days)
            }
            val days = state?.days.orEmpty()
            val day = days.getOrNull(dayIndex.coerceAtMost(days.lastIndex).coerceAtLeast(0))
            LaunchedEffect(handle, mode, day?.epochDay, day?.routes) {
                if (mode != MapMode.Days || day == null) return@LaunchedEffect
                MapLayers.set(handle.style, MapLayers.DAY_ROUTES, day.routes)
            }
            LaunchedEffect(handle, mode, day?.epochDay) {
                if (mode != MapMode.Days || day == null) return@LaunchedEffect
                val b = day.bounds
                runCatching {
                    handle.map.animateCamera(CameraUpdateFactory.newLatLngBounds(
                        LatLngBounds.from(b[2], b[3], b[0], b[1]), 140, 420, 140, 360,
                    ), 600)
                }
            }
            if (mode == MapMode.Explored) {
                FogOverlay(
                    handle.map, state?.fog.orEmpty(), Modifier.fillMaxSize(),
                    // On the dark map, light mist makes the cleared (dark) ground stand out.
                    fog = if (handle.theme == MapTheme.Dark) Color(0xD9C9D1E0) else Color(0xE6161A23),
                )
            }
            if (mode == MapMode.Photos) {
                HeatOverlay(handle.map, state?.photoHeat.orEmpty(), Modifier.fillMaxSize())
            }
        }

        Column(
            Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    Text(
                        "Unfog", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(onClick = {
                    handle?.lastKnown()?.let {
                        handle.map.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 14.0))
                    }
                }) { Icon(Icons.Rounded.MyLocation, "My location") }
                FilledTonalIconButton(onClick = {
                    context.mapTheme.set(if (theme == MapTheme.Dark) MapTheme.Light else MapTheme.Dark)
                }) {
                    Icon(
                        if (theme == MapTheme.Dark) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                        if (theme == MapTheme.Dark) "Light map" else "Dark map",
                    )
                }
                FilledTonalIconButton(onClick = { editHomeId = null; homeDialog = true }) {
                    Icon(Icons.Rounded.Home, "Places",
                        tint = if (homes.isNotEmpty()) Color(0xFF2EAA6E) else LocalContentColor.current)
                }
                FilledTonalIconButton(onClick = onOpenTrips) {
                    Icon(Icons.AutoMirrored.Rounded.List, "Trips")
                }
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MapMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = { Text(m.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (pickingHome) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF2EAA6E),
                    contentColor = Color.White,
                ) {
                    Row(Modifier.padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val what = pickTarget?.let { context.homeSpot.get(it)?.name } ?: "the new place"
                        Text("Tap the map to put $what", style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f, fill = false))
                        TextButton(onClick = { pickingHome = false; editHomeId = pickTarget; homeDialog = true }) {
                            Text("Cancel", color = Color.White)
                        }
                    }
                }
            } else {
                ModeInfo(mode, state, tripCount, dayIndex) { dayIndex = it }
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
        ) {
            TravelCard(active, running, onStart = { namingTrip = true }, onStop = { TravelMode.stop(context) },
                onOpen = { active?.let { onOpenTrip(it.id) } })
        }
    }

    if (namingTrip) {
        StartTripDialog(
            onDismiss = { namingTrip = false },
            onStart = { name -> namingTrip = false; startTravel(name) },
        )
    }

    if (homeDialog) {
        HomeSpotDialog(
            initialEditId = editHomeId,
            onDismiss = { homeDialog = false },
            onPickOnMap = { id -> homeDialog = false; pickTarget = id; pickingHome = true },
        )
    }
}

@Composable
private fun ModeInfo(
    mode: MapMode,
    state: HomeViewModel.MapState?,
    tripCount: Int,
    dayIndex: Int,
    onDayIndex: (Int) -> Unit,
) {
    val explored = state?.explored
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            when (mode) {
                MapMode.Explored -> {
                    Text(
                        if (explored == null || explored.cells == 0) "Everything is fog. Start a trip to clear it."
                        else "%.2f km² unfogged".format(explored.areaKm2),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (explored != null && explored.cells > 0) {
                        Text(
                            "${plural(tripCount, "trip")} · ${plural(explored.days, "day")} on the road",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                MapMode.Frequency -> Legend(
                    "Days visited",
                    listOf("1" to Color(0xFFFFD666), "3" to Color(0xFFFF8A3D),
                        "10" to Color(0xFFF0567A), "30+" to Color(0xFFB07CFF)),
                )
                MapMode.Routes -> Text(
                    "Every route you've recorded",
                    style = MaterialTheme.typography.titleSmall,
                )
                MapMode.Photos -> {
                    Text("Where you take photos", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Zoom in for pins; tap one to open its trip",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MapMode.Days -> DayPicker(state?.days.orEmpty(), dayIndex, onDayIndex)
            }
        }
    }
}

@Composable
private fun DayPicker(days: List<MapData.Day>, index: Int, onIndex: (Int) -> Unit) {
    if (days.isEmpty()) {
        Text("No travel days yet", style = MaterialTheme.typography.titleSmall)
        return
    }
    val i = index.coerceIn(0, days.lastIndex)
    val day = days[i]
    Row(verticalAlignment = Alignment.CenterVertically) {
        // The list is newest first, so "previous day" moves further into it.
        IconButton(onClick = { onIndex(i + 1) }, enabled = i < days.lastIndex) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Earlier day")
        }
        Column(Modifier.padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatDate(LocalDate.ofEpochDay(day.epochDay)), style = MaterialTheme.typography.titleSmall)
            Text(
                "${plural(day.trips, "trip")} · ${formatKm(day.distanceM)} · ${i + 1} of ${days.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onIndex(i - 1) }, enabled = i > 0) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Later day")
        }
    }
}

@Composable
private fun Legend(title: String, items: List<Pair<String, Color>>) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Row(
        Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TravelCard(
    active: Trip?,
    running: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: () -> Unit,
) {
    if (active == null) {
        Button(
            onClick = onStart,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.height(56.dp),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(Icons.Rounded.Explore, null)
            Spacer(Modifier.width(10.dp))
            Text("Start travel mode", style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active.id) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(running) {
                        Box(
                            Modifier.padding(end = 8.dp).size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        )
                    }
                    Text(active.name, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "${formatKm(active.distanceM)} · ${formatDuration(now - active.startMs)} · ${plural(active.photoCount, "photo")}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF0567A), contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Rounded.Stop, null)
                Spacer(Modifier.width(4.dp))
                Text("End")
            }
        }
    }
}


@Composable
private fun StartTripDialog(onDismiss: () -> Unit, onStart: (String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start a trip") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Trip name (optional)") },
                placeholder = { Text("Named from where you go if left empty") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { onStart(name.ifBlank { null }) }),
            )
        },
        confirmButton = { TextButton(onClick = { onStart(name.ifBlank { null }) }) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
