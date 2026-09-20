package com.example.park

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

// Matches the color that was hardcoded for the highlight polyline before this feature
// existed, so an uncustomized car looks exactly the same as it always did.
const val DEFAULT_CAR_COLOR_HEX = "#9C27B0"
const val DEFAULT_CAR_ICON = "\uD83D\uDE97" // 🚗
const val DEFAULT_WIDGET_TEXT_COLOR_HEX = "#FFFFFF" // white reads reasonably well over most photos and status colors

val CAR_COLOR_PALETTE: List<String> = listOf(
    "#9C27B0", // purple (default)
    "#E91E63", // pink
    "#F44336", // red
    "#FF9800", // orange
    "#4CAF50", // green
    "#2196F3", // blue
    "#009688", // teal
    "#795548"  // brown
)

// Separate from CAR_COLOR_PALETTE since this is chosen for text legibility against an
// unpredictable background (a photo, or whatever road-status color is active), not for
// identifying the car — black/white are the two most broadly useful options, included here
// even though they wouldn't make much sense as a street-highlight color.
val WIDGET_TEXT_COLOR_PALETTE: List<String> = listOf(
    "#FFFFFF", "#000000", "#9C27B0", "#E91E63", "#F44336", "#FF9800", "#4CAF50", "#2196F3"
)

val CAR_ICON_PALETTE: List<String> = listOf(
    "\uD83D\uDE97", // 🚗 car
    "\uD83D\uDE99", // 🚙 SUV
    "\uD83D\uDE95", // 🚕 taxi
    "\uD83D\uDE93", // 🚓 (just visual variety, not literal)
    "\u2B50",       // ⭐ star
    "\uD83D\uDC3E", // 🐾 paw
    "\u2764\uFE0F", // ❤️ heart
    "\uD83C\uDFE0"  // 🏠 home
)

/** Parses a car's stored hex color, falling back to the default on missing/invalid input. */
fun carColorInt(car: Car): Int = try {
    android.graphics.Color.parseColor(car.colorHex ?: DEFAULT_CAR_COLOR_HEX)
} catch (e: IllegalArgumentException) {
    android.graphics.Color.parseColor(DEFAULT_CAR_COLOR_HEX)
}

fun carComposeColor(car: Car): Color = Color(carColorInt(car))

fun carIcon(car: Car): String = car.iconEmoji ?: DEFAULT_CAR_ICON

/** Parses a car's stored widget text color, falling back to white on missing/invalid input. */
fun widgetTextColor(car: Car): Color = try {
    Color(android.graphics.Color.parseColor(car.widgetTextColorHex ?: DEFAULT_WIDGET_TEXT_COLOR_HEX))
} catch (e: IllegalArgumentException) {
    Color(android.graphics.Color.parseColor(DEFAULT_WIDGET_TEXT_COLOR_HEX))
}

/**
 * Loads a photo from local storage for display — used for both car and saved-location
 * photos, since the logic is identical (decode a file path). Returns null if no photo is
 * set or the file can't be decoded (e.g. deleted externally); callers should fall back to
 * the color+emoji representation in that case, which is exactly what CarAvatar/LocationAvatar do.
 */
fun loadPhotoBitmap(photoPath: String): ImageBitmap? = try {
    BitmapFactory.decodeFile(photoPath)?.asImageBitmap()
} catch (e: Exception) {
    null
}

/**
 * The single shared "how a car looks" composable — a photo if one's set, otherwise the
 * color+emoji swatch. Used everywhere a car's identity is shown (Manage Cars, the car
 * picker, the map's parked-cars banner) so a photo shows up consistently everywhere at
 * once, rather than needing each call site updated separately.
 */
@Composable
fun CarAvatar(car: Car, size: Dp) {
    val bitmap = car.photoPath?.let { path -> remember(path) { loadPhotoBitmap(path) } }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = car.name,
            modifier = Modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier.size(size).clip(CircleShape).background(carComposeColor(car)),
            contentAlignment = Alignment.Center
        ) {
            Text(carIcon(car), fontSize = (size.value * 0.5).sp)
        }
    }
}