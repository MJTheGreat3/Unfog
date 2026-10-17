package com.matt.unfog.data

import android.content.Context
import com.matt.unfog.geo.Geo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class HomeSpot(
    val lat: Double,
    val lng: Double,
    val name: String = "Home",
    val id: String = UUID.randomUUID().toString(),
    val radiusM: Float = 200f,
    /** Notify and offer to start a trip when you leave. Needs background location. */
    val askOnLeave: Boolean = true,
    /** Offer to end the running trip when you arrive. */
    val autoStop: Boolean = true,
) {
    fun distanceTo(lat: Double, lng: Double) = Geo.distance(this.lat, this.lng, lat, lng)
}

/** Saved home spots (home, work, family…), kept as JSON in SharedPreferences. */
class HomeSpotStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("home", Context.MODE_PRIVATE)
    private val _spots = MutableStateFlow(read())
    val spots: StateFlow<List<HomeSpot>> = _spots.asStateFlow()

    fun get(id: String): HomeSpot? = _spots.value.firstOrNull { it.id == id }

    /** Adds [spot], or replaces the saved spot with the same id. */
    fun put(spot: HomeSpot) {
        val list = _spots.value
        write(if (list.any { it.id == spot.id }) list.map { if (it.id == spot.id) spot else it } else list + spot)
    }

    fun remove(id: String) = write(_spots.value.filterNot { it.id == id })

    private fun write(list: List<HomeSpot>) {
        val json = JSONArray()
        list.forEach {
            json.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("lat", it.lat)
                put("lng", it.lng)
                put("radius", it.radiusM.toDouble())
                put("ask", it.askOnLeave)
                put("stop", it.autoStop)
            })
        }
        prefs.edit().putString("spots", json.toString()).apply()
        _spots.value = list
    }

    private fun read(): List<HomeSpot> {
        prefs.getString("spots", null)?.let { raw ->
            val json = JSONArray(raw)
            return (0 until json.length()).map { i ->
                val o = json.getJSONObject(i)
                HomeSpot(
                    lat = o.getDouble("lat"),
                    lng = o.getDouble("lng"),
                    name = o.optString("name", "Home"),
                    id = o.getString("id"),
                    radiusM = o.optDouble("radius", 200.0).toFloat(),
                    askOnLeave = o.optBoolean("ask", true),
                    autoStop = o.optBoolean("stop", true),
                )
            }
        }
        // Builds before multiple spots stored a single one under flat keys.
        if (!prefs.contains("lat")) return emptyList()
        val legacy = HomeSpot(
            lat = Double.fromBits(prefs.getLong("lat", 0)),
            lng = Double.fromBits(prefs.getLong("lng", 0)),
            radiusM = prefs.getFloat("radius", 200f),
            askOnLeave = prefs.getBoolean("ask", true),
            autoStop = prefs.getBoolean("stop", true),
        )
        prefs.edit().clear().apply()
        write(listOf(legacy))
        return listOf(legacy)
    }
}
