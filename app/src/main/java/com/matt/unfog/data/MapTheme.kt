package com.matt.unfog.data

import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class MapTheme(val styleUrl: String) {
    Light("https://tiles.openfreemap.org/styles/liberty"),
    Dark("https://tiles.openfreemap.org/styles/dark"),
}

/** The chosen base map. Until you pick one, it follows the system dark mode setting. */
class MapThemeStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(read())
    val theme: StateFlow<MapTheme> = _theme.asStateFlow()

    fun set(theme: MapTheme) {
        prefs.edit().putString("map_theme", theme.name).apply()
        _theme.value = theme
    }

    private fun read(): MapTheme {
        prefs.getString("map_theme", null)?.let { saved ->
            return MapTheme.entries.firstOrNull { it.name == saved } ?: MapTheme.Light
        }
        val night = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) MapTheme.Dark else MapTheme.Light
    }
}
