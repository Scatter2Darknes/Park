package com.example.park

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.Text
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

// Blue by default, distinguishing an uncustomized saved location from an uncustomized car
// (which defaults to purple) at a glance.
const val DEFAULT_LOCATION_COLOR_HEX = "#2196F3"
const val DEFAULT_LOCATION_ICON = "\uD83D\uDCCD" // 📍

val LOCATION_ICON_PALETTE: List<String> = listOf(
    "\uD83D\uDCCD", // 📍 pin
    "\uD83C\uDFE0", // 🏠 home
    "\uD83C\uDFE2", // 🏢 work
    "\uD83C\uDFEB", // 🏫 school
    "\u2695\uFE0F", // ⚕️ medical
    "\uD83D\uDED2", // 🛒 shopping
    "\u2B50",       // ⭐ star
    "\u2764\uFE0F"  // ❤️ heart
)

/** Parses a saved location's stored hex color, falling back to the default on missing/invalid input. */
fun locationColorInt(location: SavedLocation): Int = try {
    android.graphics.Color.parseColor(location.colorHex ?: DEFAULT_LOCATION_COLOR_HEX)
} catch (e: IllegalArgumentException) {
    android.graphics.Color.parseColor(DEFAULT_LOCATION_COLOR_HEX)
}

fun locationComposeColor(location: SavedLocation): Color = Color(locationColorInt(location))

fun locationIcon(location: SavedLocation): String = location.iconEmoji ?: DEFAULT_LOCATION_ICON

/**
 * The shared "how a saved location looks" composable — a photo if one's set, otherwise the
 * color+emoji swatch. Mirrors CarAvatar in CarStyle.kt exactly, so both entity types get
 * identical visual treatment wherever they're shown.
 */
@Composable
fun LocationAvatar(location: SavedLocation, size: Dp) {
    val bitmap = location.photoPath?.let { path -> remember(path) { loadPhotoBitmap(path) } }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = location.name,
            modifier = Modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier.size(size).clip(CircleShape).background(locationComposeColor(location)),
            contentAlignment = Alignment.Center
        ) {
            Text(locationIcon(location), fontSize = (size.value * 0.5).sp)
        }
    }
}