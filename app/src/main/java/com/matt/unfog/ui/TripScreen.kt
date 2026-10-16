package com.matt.unfog.ui

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.matt.unfog.data.TrackPoint
import com.matt.unfog.data.TripPhoto
import com.matt.unfog.geo.Geo
import com.matt.unfog.photos.PhotoImporter
import kotlinx.coroutines.delay
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

@Composable
fun TripScreen(tripId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: TripViewModel = viewModel(
        key = "trip-$tripId",
        factory = TripViewModel.Factory(context.applicationContext as Application, tripId),
    )
    val trip by vm.trip.collectAsStateWithLifecycle()
    val points by vm.points.collectAsStateWithLifecycle()
    val photos by vm.photos.collectAsStateWithLifecycle()
    val routes by vm.routes.collectAsStateWithLifecycle()

    var replaying by remember { mutableStateOf(false) }
    var replayPhoto by remember { mutableStateOf<TripPhoto?>(null) }
    var viewing by remember { mutableStateOf<TripPhoto?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var fitted by remember { mutableStateOf(false) }

    val mediaLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (PhotoImporter.canRead(context)) {
            vm.importPhotos { message = if (it == 0) "No new photos found" else "Added ${plural(it, "photo")}" }
        } else {
            message = "Photo access was denied"
        }
    }
    fun findPhotos() {
        if (PhotoImporter.canRead(context)) {
            vm.importPhotos { message = if (it == 0) "No new photos found" else "Added ${plural(it, "photo")}" }
        } else {
            mediaLauncher.launch(PhotoImporter.mediaPermissions())
        }
    }

    BackHandler(replaying) { replaying = false }

    Box(Modifier.fillMaxSize()) {
        val handle = rememberMap(Modifier.fillMaxSize())

        if (handle != null) {
            LaunchedEffect(handle, routes, replaying) {
                MapLayers.set(handle.style, MapLayers.ROUTES, routes ?: return@LaunchedEffect)
                MapLayers.show(handle.style, MapLayers.ROUTES, !replaying)
            }
            LaunchedEffect(handle, photos) {
                MapLayers.set(handle.style, MapLayers.PHOTOS, MapData.photos(photos))
                MapLayers.show(handle.style, MapLayers.PHOTOS, true)
            }
            LaunchedEffect(handle, points) {
                if (!fitted && points.isNotEmpty()) {
                    fitted = true
                    handle.fit(points, bottomPadding = 520)
                }
            }
            LaunchedEffect(handle, replaying) {
                if (!replaying) {
                    MapLayers.show(handle.style, MapLayers.REPLAY, false)
                    MapLayers.show(handle.style, MapLayers.REPLAY_HEAD, false)
                    replayPhoto = null
                    return@LaunchedEffect
                }
                replay(handle, points, photos) { replayPhoto = it }
                delay(1_500)
                replaying = false
            }
        }

        Row(
            Modifier.statusBarsPadding().padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            FilledTonalIconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "Rename") }
            FilledTonalIconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Delete") }
        }

        AnimatedVisibility(
            visible = replayPhoto != null,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            replayPhoto?.let {
                AsyncImage(
                    model = it.uri, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(220.dp).clip(RoundedCornerShape(20.dp))
                        .background(Color.Black),
                )
            }
        }

        val t = trip
        if (t != null && !replaying) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                Column(Modifier.navigationBarsPadding().padding(vertical = 20.dp)) {
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text(t.name, style = MaterialTheme.typography.headlineSmall)
                        Text(
                            formatDate(t.startMs) + " · " + formatTime(t.startMs) +
                                (t.endMs?.let { "–" + formatTime(it) } ?: " · recording"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))
                        val duration = (t.endMs ?: System.currentTimeMillis()) - t.startMs
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            Stat("Distance", formatKm(t.distanceM))
                            Stat("Time", formatDuration(duration))
                            val hours = duration / 3_600_000.0
                            Stat("Avg", if (hours > 0) "%.0f km/h".format(t.distanceM / 1000 / hours) else "–")
                            Stat("Photos", photos.size.toString())
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { replaying = true }, enabled = points.size >= 2) {
                                Icon(Icons.Rounded.PlayArrow, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Replay")
                            }
                            FilledTonalButton(onClick = ::findPhotos) {
                                Icon(Icons.Rounded.AddPhotoAlternate, null)
                                Spacer(Modifier.width(6.dp))
                                Text("Find photos")
                            }
                        }
                        message?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    if (photos.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(photos, key = { it.id }) { photo ->
                                AsyncImage(
                                    model = photo.uri, contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { viewing = photo },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (replaying) {
            FilledTonalButton(
                onClick = { replaying = false },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp),
            ) {
                Icon(Icons.Rounded.Stop, null)
                Spacer(Modifier.width(6.dp))
                Text("Stop replay")
            }
        }
    }

    viewing?.let { photo ->
        PhotoViewer(
            photo = photo,
            onDismiss = { viewing = null },
            onRemove = { vm.hidePhoto(photo.id); viewing = null },
        )
    }
    if (renaming) {
        var name by remember { mutableStateOf(trip?.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename trip") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.rename(name); renaming = false }) {
                    Text("Save")
                }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this trip?") },
            text = { Text("Its route will be removed from your map. Your photos stay in your gallery.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(); confirmDelete = false; onBack() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PhotoViewer(photo: TripPhoto, onDismiss: () -> Unit, onRemove: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss)) {
            AsyncImage(
                model = photo.uri, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatTime(photo.takenMs) + if (photo.lat == null) " · no location" else "",
                    color = Color.White,
                )
                FilledTonalButton(onClick = onRemove) { Text("Remove from trip") }
            }
        }
    }
}

fun MapHandle.fit(points: List<TrackPoint>, bottomPadding: Int = 120) {
    val b = Geo.bounds(points.map { it.lat to it.lng }) ?: return
    runCatching {
        map.moveCamera(
            CameraUpdateFactory.newLatLngBounds(
                LatLngBounds.from(b[2], b[3], b[0], b[1]), 120, 260, 120, bottomPadding,
            )
        )
    }
    if (map.cameraPosition.zoom > 16) map.moveCamera(CameraUpdateFactory.zoomTo(16.0))
}

/**
 * Animated playback: the route draws itself over ~15 seconds while the camera follows, and
 * each photo pops up as the playhead passes the moment it was taken.
 */
private suspend fun replay(
    handle: MapHandle,
    points: List<TrackPoint>,
    photos: List<TripPhoto>,
    onPhoto: (TripPhoto?) -> Unit,
) {
    if (points.size < 2) return
    val style = handle.style
    MapLayers.show(style, MapLayers.REPLAY, true)
    MapLayers.show(style, MapLayers.REPLAY_HEAD, true)
    val start = points.first().timeMs
    val end = points.last().timeMs
    val frames = 300
    val frameMs = 50L
    handle.map.animateCamera(
        CameraUpdateFactory.newLatLngZoom(LatLng(points[0].lat, points[0].lng), 15.0), 800,
    )
    delay(900)

    var idx = 0
    var nextPhoto = 0
    val drawn = mutableListOf<Point>()
    for (f in 0..frames) {
        val t = start + (end - start) * f / frames
        while (idx < points.size && points[idx].timeMs <= t) {
            drawn += Point.fromLngLat(points[idx].lng, points[idx].lat)
            idx++
        }
        if (drawn.size >= 2) {
            MapLayers.set(style, MapLayers.REPLAY,
                FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(drawn))))
        }
        val head = drawn.lastOrNull() ?: continue
        MapLayers.set(style, MapLayers.REPLAY_HEAD,
            FeatureCollection.fromFeature(Feature.fromGeometry(head)))
        if (f % 4 == 0) {
            handle.map.easeCamera(
                CameraUpdateFactory.newLatLng(LatLng(head.latitude(), head.longitude())), 200,
            )
        }
        if (nextPhoto < photos.size && photos[nextPhoto].takenMs <= t) {
            onPhoto(photos[nextPhoto])
            while (nextPhoto < photos.size && photos[nextPhoto].takenMs <= t) nextPhoto++
            delay(1_400)
            onPhoto(null)
        }
        delay(frameMs)
    }
    handle.fit(points, bottomPadding = 520)
}
