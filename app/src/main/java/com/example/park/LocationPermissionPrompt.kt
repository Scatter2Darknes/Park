package com.example.park

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * What the map screen shows when it doesn't have location permission, in place of the map.
 *
 * Before this existed the whole map screen — including the Settings gear and every button — lived
 * inside the "has permission" branch, so declining left a single line of text on an empty screen
 * with nothing to tap. This gives the person a way forward:
 *  - "Allow location" asks again through the system dialog. Android only shows that dialog a limited
 *    number of times (twice, then it's permanently declined), after which the button silently does
 *    nothing — hence the second button, which always works.
 *  - "Open app settings" goes to the app's page in system Settings, where Permissions → Location
 *    can be switched on by hand.
 *  - The Settings gear stays reachable so the rest of the app (cars, saved locations, sync) is still usable.
 *
 * @param approximateOnly true when the person granted only "Approximate" location: the map needs the
 *   precise GPS fix, so the wording asks for precise location specifically.
 */
@Composable
fun LocationPermissionRequired(
    approximateOnly: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenSettingsScreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        IconButton(
            onClick = onOpenSettingsScreen,
            modifier = Modifier.align(Alignment.TopStart).padding(24.dp)
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onBackground)
        }

        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.MyLocation,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                if (approximateOnly) "Precise location needed" else "Location access needed",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (approximateOnly) {
                    "Park only has approximate location, which isn't accurate enough to tell which " +
                            "block you're on. Allow \"Precise\" location to see nearby street-cleaning " +
                            "rules and save where you parked."
                } else {
                    "Park uses your location to show street-cleaning rules near you and to save where " +
                            "you parked. Without it the map can't be shown."
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRequestPermission) { Text("Allow location") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenAppSettings) { Text("Open app settings") }
            Text(
                "If \"Allow location\" does nothing, Android has stopped asking — use \"Open app " +
                        "settings\", then Permissions → Location.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
