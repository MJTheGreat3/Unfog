package com.matt.unfog.tracking

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.matt.unfog.ui.formatTime
import com.matt.unfog.ui.theme.UnfogTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Full-screen, alarm-style prompt shown over the lock screen when you leave a saved place
 * (start a trip?) or arrive at one during a trip (end it?). Rings for up to a minute.
 */
class PlaceAlarmActivity : ComponentActivity() {

    companion object {
        const val EXTRA_KIND = "kind"
        const val EXTRA_PLACE = "place"
        const val EXTRA_TRIP = "trip"
        const val KIND_LEFT = "left"
        const val KIND_ARRIVED = "arrived"
        private const val RING_MS = 60_000L

        fun intent(context: Context, kind: String, place: String, trip: String? = null): Intent =
            Intent(context, PlaceAlarmActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_PLACE, place)
                .putExtra(EXTRA_TRIP, trip)
    }

    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // The screen is always dark, so keep the system bar icons light.
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))

        val kind = intent.getStringExtra(EXTRA_KIND) ?: KIND_LEFT
        val place = intent.getStringExtra(EXTRA_PLACE) ?: "home"
        val trip = intent.getStringExtra(EXTRA_TRIP)
        val leaving = kind == KIND_LEFT

        // Close by itself once the trip state already matches, e.g. it was answered from the notification.
        lifecycleScope.launch {
            TravelMode.running.first { it == leaving }
            dismiss()
        }
        if (savedInstanceState == null) ring()
        lifecycleScope.launch {
            delay(RING_MS)
            silence()
        }

        setContent {
            UnfogTheme {
                AlarmScreen(
                    leaving = leaving,
                    title = when {
                        leaving -> "Heading out?"
                        place == "Home" -> "Welcome home"
                        else -> "You're at $place"
                    },
                    body = if (leaving) "You just left $place. Start a trip to record it."
                    else "End “${trip ?: "this trip"}”?",
                    confirm = if (leaving) "Start trip" else "End trip",
                    decline = if (leaving) "Not now" else "Keep going",
                    onConfirm = {
                        if (leaving) TravelMode.start(this) else TravelMode.stop(this)
                        dismiss()
                    },
                    onDecline = { dismiss() },
                )
            }
        }
    }

    private fun ring() {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ringtone = runCatching {
            RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                isLooping = true
                play()
            }
        }.getOrNull()
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 500), 0))
    }

    private fun silence() {
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun dismiss() {
        silence()
        HomeFence.cancelLeftHome(this)
        HomeFence.cancelArrived(this)
        finish()
    }

    // Volume buttons silence the alarm, like a clock alarm.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            silence()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        silence()
        super.onDestroy()
    }
}

@Composable
private fun AlarmScreen(
    leaving: Boolean,
    title: String,
    body: String,
    confirm: String,
    decline: String,
    onConfirm: () -> Unit,
    onDecline: () -> Unit,
) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulse",
    )
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            formatTime(System.currentTimeMillis()),
            fontSize = 64.sp,
            fontWeight = FontWeight.Light,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(132.dp)
                .scale(pulse)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (leaving) Icons.Rounded.Flag else Icons.Rounded.Home,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                Text(confirm, style = MaterialTheme.typography.titleMedium)
            }
            OutlinedButton(onClick = onDecline, modifier = Modifier.fillMaxWidth().height(60.dp)) {
                Text(decline, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
