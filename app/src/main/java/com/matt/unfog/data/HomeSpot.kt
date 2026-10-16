package com.matt.unfog.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HomeSpot(
    val lat: Double,
    val lng: Double,
    val radiusM: Float = 200f,
    /** Notify and offer to start a trip when you leave. Needs background location. */
    val askOnLeave: Boolean = true,
    /** End the running trip when you get back. */
    val autoStop: Boolean = true,
)

/** The single home spot, kept in SharedPreferences. */
class HomeSpotStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("home", Context.MODE_PRIVATE)
    private val _spot = MutableStateFlow(read())
    val spot: StateFlow<HomeSpot?> = _spot.asStateFlow()

    fun set(spot: HomeSpot?) {
        prefs.edit().apply {
            if (spot == null) {
                clear()
            } else {
                putLong("lat", spot.lat.toRawBits())
                putLong("lng", spot.lng.toRawBits())
                putFloat("radius", spot.radiusM)
                putBoolean("ask", spot.askOnLeave)
                putBoolean("stop", spot.autoStop)
            }
        }.apply()
        _spot.value = spot
    }

    private fun read(): HomeSpot? {
        if (!prefs.contains("lat")) return null
        return HomeSpot(
            lat = Double.fromBits(prefs.getLong("lat", 0)),
            lng = Double.fromBits(prefs.getLong("lng", 0)),
            radiusM = prefs.getFloat("radius", 200f),
            askOnLeave = prefs.getBoolean("ask", true),
            autoStop = prefs.getBoolean("stop", true),
        )
    }
}
