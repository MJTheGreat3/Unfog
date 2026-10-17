package com.matt.unfog.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.matt.unfog.data.TrackPoint
import com.matt.unfog.data.TripPhoto
import kotlinx.coroutines.delay
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

const val REPLAY_ORANGE = "#FF8A3D"

/**
 * Animated playback: the route draws itself over ~25 seconds while the camera follows, and each
 * photo pops up as the playhead passes the moment it was taken. [points] may span several trips;
 * each is drawn in [colorOf] its trip. With [maxGapMs], idle stretches longer than that (between
 * trips, or sitting still) are squeezed out so the playback doesn't stall.
 */
suspend fun replay(
    handle: MapHandle,
    points: List<TrackPoint>,
    photos: List<TripPhoto>,
    onPhoto: (TripPhoto?) -> Unit,
    colorOf: (Long) -> String = { REPLAY_ORANGE },
    maxGapMs: Long = Long.MAX_VALUE,
) {
    if (points.size < 2) return
    val style = handle.style
    MapLayers.show(style, MapLayers.REPLAY, true)
    MapLayers.show(style, MapLayers.REPLAY_HEAD, true)

    // Playback clock per point: real elapsed time, with long gaps capped.
    val clock = LongArray(points.size)
    for (i in 1 until points.size) {
        clock[i] = clock[i - 1] + (points[i].timeMs - points[i - 1].timeMs).coerceIn(0, maxGapMs)
    }
    val photoClock = photos.map { clockAt(points, clock, it.takenMs) }
    val total = clock.last().coerceAtLeast(1)
    val frames = 300
    val frameMs = 85L

    handle.map.animateCamera(
        CameraUpdateFactory.newLatLngZoom(LatLng(points[0].lat, points[0].lng), 15.0), 800,
    )
    delay(900)

    var idx = 0
    var nextPhoto = 0
    val drawn = LinkedHashMap<Long, MutableList<Point>>()
    var head: Point? = null
    for (f in 0..frames) {
        val t = total * f / frames
        while (idx < points.size && clock[idx] <= t) {
            val p = points[idx]
            head = Point.fromLngLat(p.lng, p.lat)
            drawn.getOrPut(p.tripId) { mutableListOf() } += head
            idx++
        }
        val features = drawn.filterValues { it.size >= 2 }.map { (trip, line) ->
            Feature.fromGeometry(LineString.fromLngLats(line)).apply { addStringProperty("color", colorOf(trip)) }
        }
        MapLayers.set(style, MapLayers.REPLAY, FeatureCollection.fromFeatures(features))
        val h = head ?: continue
        MapLayers.set(style, MapLayers.REPLAY_HEAD, FeatureCollection.fromFeature(Feature.fromGeometry(h)))
        if (f % 4 == 0) {
            handle.map.easeCamera(CameraUpdateFactory.newLatLng(LatLng(h.latitude(), h.longitude())), 200)
        }
        if (nextPhoto < photos.size && photoClock[nextPhoto] <= t) {
            onPhoto(photos[nextPhoto])
            while (nextPhoto < photos.size && photoClock[nextPhoto] <= t) nextPhoto++
            delay(1_400)
            onPhoto(null)
        }
        delay(frameMs)
    }
}

/** Hides the replay layers again. */
fun clearReplay(handle: MapHandle) {
    MapLayers.show(handle.style, MapLayers.REPLAY, false)
    MapLayers.show(handle.style, MapLayers.REPLAY_HEAD, false)
}

/** Maps a real timestamp onto the (gap-squeezed) playback clock. */
private fun clockAt(points: List<TrackPoint>, clock: LongArray, timeMs: Long): Long {
    if (timeMs <= points.first().timeMs) return 0
    for (i in 1 until points.size) {
        if (timeMs <= points[i].timeMs) {
            val span = (points[i].timeMs - points[i - 1].timeMs).coerceAtLeast(1)
            val f = (timeMs - points[i - 1].timeMs).toDouble() / span
            return clock[i - 1] + ((clock[i] - clock[i - 1]) * f).toLong()
        }
    }
    return clock.last()
}

/** The photo that pops up in the middle of the map during a replay. */
@Composable
fun ReplayPhotoPopup(photo: TripPhoto?, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = photo != null,
        enter = fadeIn() + scaleIn(initialScale = 0.8f),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        photo?.let {
            AsyncImage(
                model = it.uri, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(220.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black),
            )
        }
    }
}

@Composable
fun RenameTripDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    // Old name starts selected, so typing replaces it.
    var name by remember { mutableStateOf(TextFieldValue(current, TextRange(0, current.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename trip") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(enabled = name.text.isNotBlank(), onClick = { onRename(name.text.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
