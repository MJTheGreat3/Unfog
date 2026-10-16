package com.matt.unfog

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.matt.unfog.ui.HomeScreen
import com.matt.unfog.ui.TripScreen
import com.matt.unfog.ui.TripsScreen
import com.matt.unfog.ui.theme.UnfogTheme
import org.maplibre.android.MapLibre

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_START_TRAVEL = "com.matt.unfog.START_TRAVEL"
    }

    private var startRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        enableEdgeToEdge()
        handle(intent)

        setContent {
            UnfogTheme {
                // Back stack of screens: "home", "trips", or a trip id.
                val stack = rememberSaveable(saver = listSaver(save = { it.toList() }, restore = { mutableStateListOf(*it.toTypedArray()) })) {
                    mutableStateListOf("home")
                }
                fun push(s: String) = stack.add(s)
                fun pop() = stack.removeAt(stack.lastIndex)
                BackHandler(stack.size > 1) { pop() }

                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AnimatedContent(
                        targetState = stack.last(),
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "screen",
                    ) { screen ->
                        when (screen) {
                            "home" -> HomeScreen(
                                startRequested = startRequested,
                                onStartHandled = { startRequested = false },
                                onOpenTrips = { push("trips") },
                                onOpenTrip = { push(it.toString()) },
                            )
                            "trips" -> TripsScreen(
                                onBack = { pop() },
                                onOpenTrip = { push(it.toString()) },
                            )
                            else -> TripScreen(screen.toLong(), onBack = { pop() })
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action == ACTION_START_TRAVEL) startRequested = true
    }
}
