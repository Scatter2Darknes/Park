package com.example.park

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Lets a car be marked as holding a residential parking permit for one or more zones — blocks
 * posted with a matching letter then skip the non-permit time-limit warning entirely (see
 * RppStatus.kt's rppWarning). [availableZones] comes from the synced RPP data itself
 * (loadDistinctRppZoneLetters) rather than a hardcoded list, so it can never drift out of date
 * with what SFMTA actually has posted.
 */
@Composable
fun PermitZonesDialog(
    car: Car,
    availableZones: List<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selected by remember { mutableStateOf(car.permitZoneLetterSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${car.name}'s RPP Zones") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Zones this car holds a residential parking permit for — posted " +
                            "blocks in these zones won't show a non-permit time-limit warning.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (availableZones.isEmpty()) {
                    Text(
                        "No RPP zone data synced yet — check back after the next sync.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                availableZones.forEach { zone ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = if (zone in selected) selected - zone else selected + zone }
                    ) {
                        Checkbox(
                            checked = zone in selected,
                            onCheckedChange = { checked -> selected = if (checked) selected + zone else selected - zone }
                        )
                        Text("Zone $zone")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
