package com.matt.unfog

import android.app.Application
import com.matt.unfog.data.HomeSpotStore
import com.matt.unfog.data.MapThemeStore
import com.matt.unfog.data.TripRepository

class UnfogApp : Application() {
    val repository by lazy { TripRepository(this) }
    val homeSpot by lazy { HomeSpotStore(this) }
    val mapTheme by lazy { MapThemeStore(this) }
}

val android.content.Context.repository: TripRepository
    get() = (applicationContext as UnfogApp).repository

val android.content.Context.homeSpot: HomeSpotStore
    get() = (applicationContext as UnfogApp).homeSpot

val android.content.Context.mapTheme: MapThemeStore
    get() = (applicationContext as UnfogApp).mapTheme
