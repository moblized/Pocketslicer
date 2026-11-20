package org.moblized.pocketslicer.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.Color
import java.util.Locale

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return "—"
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    return when {
        d > 0 -> "${d} d ${h} h"
        h > 0 -> "${h} h ${m} min"
        m > 0 -> "${m} min"
        else -> "${seconds} s"
    }
}

fun formatMinutes(minutes: Int?): String = if (minutes == null) "—" else formatDuration(minutes * 60L)

fun formatGrams(g: Double): String = if (g >= 1000) String.format(Locale.US, "%.2f kg", g / 1000) else String.format(Locale.US, "%.1f g", g)

fun formatMm(v: Float): String = if (v >= 100) String.format(Locale.US, "%.0f", v) else String.format(Locale.US, "%.1f", v)

fun timeAgo(epochSeconds: Double): String {
    val diff = (System.currentTimeMillis() / 1000.0 - epochSeconds).toLong()
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60} min ago"
        diff < 86400 -> "${diff / 3600} h ago"
        else -> "${diff / 86400} d ago"
    }
}

/** "#RRGGBB" (or RRGGBBAA) to a Compose color, or null. */
fun parseHexColor(hex: String?): Color? {
    val clean = hex?.trim()?.removePrefix("#") ?: return null
    if (clean.length < 6) return null
    return try {
        val rgb = clean.substring(0, 6).toLong(16)
        Color(0xFF000000 or rgb)
    } catch (e: NumberFormatException) {
        null
    }
}

data class PickedFile(val name: String, val size: Long)

fun describeUri(resolver: ContentResolver, uri: Uri): PickedFile {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "model.stl"
    var size = -1L
    try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
    } catch (e: Exception) {
        // Some providers don't support queries; fall back to the URI.
    }
    return PickedFile(name, size)
}

val SupportedExtensions = setOf("stl", "3mf", "obj")

fun isSupportedModel(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in SupportedExtensions
