package com.sarak.cameraai.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Format {
    @Suppress("SimpleDateFormat")
    private fun fmt(pattern: String) = SimpleDateFormat(pattern, Locale.US)

    fun clock(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = TimeUnit.SECONDS.toHours(total)
        val m = TimeUnit.SECONDS.toMinutes(total) % 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun durationLong(seconds: Int): String {
        if (seconds < 60) return "${seconds}s"
        val m = seconds / 60
        val s = seconds % 60
        return if (s == 0) "${m} min" else "$m min ${s}s"
    }

    fun stamp(): String = fmt("yyyyMMdd_HHmmss").format(Date())

    fun humanDate(millis: Long): String = fmt("d MMM yyyy · HH:mm").format(Date(millis))

    fun fileSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb < 1) String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else String.format(Locale.US, "%.1f MB", mb)
    }
}
