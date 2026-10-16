package com.matt.unfog.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.matt.unfog.data.TrackPoint
import com.matt.unfog.data.Trip
import com.matt.unfog.data.TripPhoto
import com.matt.unfog.photos.PhotoImporter
import com.matt.unfog.repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.geojson.FeatureCollection

class TripViewModel(app: Application, val tripId: Long) : AndroidViewModel(app) {

    private val repo = app.repository

    val trip: StateFlow<Trip?> = repo.observeTrip(tripId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val points: StateFlow<List<TrackPoint>> = repo.observePoints(tripId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val photos: StateFlow<List<TripPhoto>> = repo.observePhotos(tripId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val routes: StateFlow<FeatureCollection?> = repo.observePoints(tripId)
        .map { MapData.routes(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun rename(name: String) = io { repo.renameTrip(tripId, name.trim()) }

    fun delete() = io { repo.deleteTrip(tripId) }

    fun hidePhoto(id: Long) = io { repo.setPhotoHidden(id, true) }

    fun importPhotos(onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                repo.trip(tripId)?.let { PhotoImporter.import(getApplication(), it) } ?: 0
            }
            onDone(added)
        }
    }

    private fun io(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }

    class Factory(private val app: Application, private val tripId: Long) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = TripViewModel(app, tripId) as T
    }
}
