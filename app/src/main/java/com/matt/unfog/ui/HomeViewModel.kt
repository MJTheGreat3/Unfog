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
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.geojson.FeatureCollection

enum class MapMode(val label: String) {
    Explored("Explored"),
    Days("Days"),
    Photos("Photos"),
    Frequency("Frequency"),
    Routes("Routes"),
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = app.repository

    fun renameTrip(id: Long, name: String) {
        viewModelScope.launch(Dispatchers.IO) { repo.renameTrip(id, name) }
    }

    class MapState(
        val fog: List<MapData.FogPath>,
        val frequency: MapData.Frequency,
        val routes: FeatureCollection,
        val photos: FeatureCollection,
        val photoHeat: List<LatLng>,
        val days: List<MapData.Day>,
        val explored: MapData.Explored,
        val bounds: DoubleArray?,
    )

    val activeTrip: StateFlow<Trip?> = repo.observeActiveTrip()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tripCount: StateFlow<Int> = repo.observeTrips().map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val map: StateFlow<MapState?> = combine(
        repo.observeAllPoints(), repo.observeAllPhotos(), repo.observeTrips(),
    ) { points, photos, trips ->
        MapState(
            fog = MapData.fogPaths(points),
            frequency = MapData.frequency(points),
            routes = MapData.routes(points),
            photos = MapData.photos(photos),
            photoHeat = MapData.photoHeat(photos),
            days = MapData.days(points, trips, photos),
            explored = MapData.explored(points),
            bounds = com.matt.unfog.geo.Geo.bounds(points.map { it.lat to it.lng }),
        )
    }
        .debounce(1_000)
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
