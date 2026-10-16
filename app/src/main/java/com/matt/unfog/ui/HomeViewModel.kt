package com.matt.unfog.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.matt.unfog.data.Trip
import com.matt.unfog.repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.maplibre.android.geometry.LatLng
import org.maplibre.geojson.FeatureCollection

enum class MapMode(val label: String) {
    Explored("Explored"),
    Frequency("Frequency"),
    Routes("Routes"),
    Photos("Photos"),
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = app.repository

    class MapState(
        val holes: List<LatLng>,
        val frequency: MapData.Frequency,
        val routes: FeatureCollection,
        val photos: FeatureCollection,
        val explored: MapData.Explored,
        val bounds: DoubleArray?,
    )

    val activeTrip: StateFlow<Trip?> = repo.observeActiveTrip()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tripCount: StateFlow<Int> = repo.observeTrips().map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val map: StateFlow<MapState?> = combine(
        repo.observeAllPoints(), repo.observeAllPhotos(),
    ) { points, photos ->
        MapState(
            holes = MapData.fogHoles(points),
            frequency = MapData.frequency(points),
            routes = MapData.routes(points),
            photos = MapData.photos(photos),
            explored = MapData.explored(points),
            bounds = com.matt.unfog.geo.Geo.bounds(points.map { it.lat to it.lng }),
        )
    }
        .debounce(1_000)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
