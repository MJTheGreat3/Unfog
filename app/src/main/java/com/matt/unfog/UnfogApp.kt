package com.matt.unfog

import android.app.Application
import com.matt.unfog.data.HomeSpotStore
import com.matt.unfog.data.MapThemeStore
import com.matt.unfog.data.TripRepository
import com.matt.unfog.widget.StatsWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class UnfogApp : Application() {
    val repository by lazy { TripRepository(this) }
    val homeSpot by lazy { HomeSpotStore(this) }
    val mapTheme by lazy { MapThemeStore(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Keep the home-screen widget in step with the data, batching bursts of writes.
        scope.launch {
            repository.version.collectLatest {
                delay(1_500)
                runCatching { StatsWidget.update(this@UnfogApp) }
            }
        }
    }
}

val android.content.Context.repository: TripRepository
    get() = (applicationContext as UnfogApp).repository

val android.content.Context.homeSpot: HomeSpotStore
    get() = (applicationContext as UnfogApp).homeSpot

val android.content.Context.mapTheme: MapThemeStore
    get() = (applicationContext as UnfogApp).mapTheme
