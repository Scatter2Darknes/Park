package com.example.park

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight

// Crude local-time heuristic for the "Automatic (time of day)" color theme option — not real
// sunrise/sunset, just fixed hours.
const val COLOR_THEME_DAY_START_HOUR = 6
const val COLOR_THEME_DAY_END_HOUR = 19

/**
 * Resolves the "Color Theme" setting (Day/Night/Automatic-matches-system/
 * Automatic-time-of-day) to a single dark/light boolean. Shared by the overall app theme
 * (ParkAppTheme, applied once in MainActivity) and the map's own tile choice (MapScreen) so
 * they always agree by default — the map is then free to layer an additional
 * tunnel-detection override on top for its tiles specifically, since being in a tunnel
 * doesn't mean Settings or Manage Cars should also flip to dark.
 */
fun resolveIsDarkColorTheme(mode: String, systemDark: Boolean): Boolean = when (mode) {
    "NIGHT" -> true
    "DAY" -> false
    "AUTO" -> systemDark
    "AUTO_TIME" -> {
        val hour = java.time.LocalTime.now().hour
        hour < COLOR_THEME_DAY_START_HOUR || hour >= COLOR_THEME_DAY_END_HOUR
    }
    else -> {
        // Should never happen with a valid persisted mode — logged so a real occurrence
        // (vs. this being a red herring for the "randomly switches to matches system" bug
        // report) is actually diagnosable next time, instead of silently falling through.
        android.util.Log.w("ColorTheme", "resolveIsDarkColorTheme: unexpected mode '$mode', falling back to system")
        systemDark
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF2196F3),
    secondary = Color(0xFF9C27B0)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFFCE93D8)
)

// Semibold titles read as more deliberate/"designed" than Material3's stock Normal-weight
// default, at essentially zero cost since every screen already leans on titleLarge/titleMedium
// for TopAppBar titles and section headers.
private val AppTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
    )
}

/**
 * App-wide theme wrapper. This was missing entirely before — without a MaterialTheme{}
 * ancestor, every `MaterialTheme.colorScheme.*` reference used throughout this codebase
 * (banners, containers, dividers, etc.) was silently resolving to Material3's hardcoded
 * default light scheme, regardless of the system's dark-mode setting. Wrapping the app in
 * this fixes that: dark mode now actually switches the palette, and Android 12+ additionally
 * gets dynamic (wallpaper-based) color by default.
 *
 * Car swatches and sweep-status highlight colors are unaffected either way — those already
 * use raw user-chosen hex values (CarStyle.kt / SweepStatus.kt) rather than theme colors, by
 * design, so they stay exactly as customized regardless of light/dark/dynamic.
 */
@Composable
fun ParkAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}