package com.matt.unfog

import android.app.Application
import com.matt.unfog.data.TripRepository

class UnfogApp : Application() {
    val repository by lazy { TripRepository(this) }
}

val android.content.Context.repository: TripRepository
    get() = (applicationContext as UnfogApp).repository
