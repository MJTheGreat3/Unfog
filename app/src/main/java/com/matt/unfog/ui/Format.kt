package com.matt.unfog.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatKm(m: Double): String = when {
    m < 1_000 -> "%.0f m".format(m)
    m < 100_000 -> "%.1f km".format(m / 1_000)
    else -> "%,.0f km".format(m / 1_000)
}

fun formatDuration(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 60 -> "$min min"
        min < 48 * 60 -> "${min / 60} h ${min % 60} min"
        else -> "${min / (24 * 60)} days"
    }
}

fun formatDate(ms: Long): String = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date(ms))

fun formatTime(ms: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
