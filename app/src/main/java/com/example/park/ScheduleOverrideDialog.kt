package com.example.park

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleOverrideDialog(
    segment: StreetSegment,
    existingOverride: ScheduleOverride?,
    onSave: (ScheduleOverride) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    var dayMenuExpanded by remember { mutableStateOf(false) }
    var selectedDay by remember {
        mutableStateOf(
            existingOverride?.fullName
                ?: segment.fullName.trim().split(Regex("\\s+")).firstOrNull()
                ?: "Monday"
        )
    }
    var week1 by remember { mutableStateOf(existingOverride?.week1 ?: segment.week1) }
    var week2 by remember { mutableStateOf(existingOverride?.week2 ?: segment.week2) }
    var week3 by remember { mutableStateOf(existingOverride?.week3 ?: segment.week3) }
    var week4 by remember { mutableStateOf(existingOverride?.week4 ?: segment.week4) }
    var week5 by remember { mutableStateOf(existingOverride?.week5 ?: segment.week5) }
    var fromHour by remember { mutableStateOf(existingOverride?.fromHour ?: segment.fromHour) }
    var toHour by remember { mutableStateOf(existingOverride?.toHour ?: segment.toHour) }
    var notes by remember { mutableStateOf(existingOverride?.notes ?: "") }

    val dayOptions = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    val weekToggles = listOf(
        Triple("1st", week1) { v: Boolean -> week1 = v },
        Triple("2nd", week2) { v: Boolean -> week2 = v },
        Triple("3rd", week3) { v: Boolean -> week3 = v },
        Triple("4th", week4) { v: Boolean -> week4 = v },
        Triple("5th", week5) { v: Boolean -> week5 = v }
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Correct this street's schedule") },
        text = {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .verticalScrollbar(scrollState, MaterialTheme.colorScheme.onSurfaceVariant) // "more below" hint
                    .verticalScroll(scrollState)
                    .padding(end = 10.dp) // room for the bar
            ) {
                Text(
                    "If the posted sign on ${segment.corridor} (${segment.limits}) doesn't " +
                            "match what's shown, fix it here. This only changes what your device " +
                            "displays for this one block.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))

                ExposedDropdownMenuBox(
                    expanded = dayMenuExpanded,
                    onExpandedChange = { dayMenuExpanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedDay,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Day of week") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = dayMenuExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = dayMenuExpanded,
                        onDismissRequest = { dayMenuExpanded = false }
                    ) {
                        dayOptions.forEach { day ->
                            DropdownMenuItem(
                                text = { Text(day) },
                                onClick = { selectedDay = day; dayMenuExpanded = false }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Weeks of the month", style = MaterialTheme.typography.labelMedium)
                weekToggles.forEach { (label, checked, onCheckedChange) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
                        Text(label)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("From hour: ${formatHour(fromHour)}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = fromHour.toFloat(),
                    onValueChange = { fromHour = it.toInt() },
                    valueRange = 0f..23f,
                    steps = 22
                )
                Text("To hour: ${formatHour(toHour)}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = toHour.toFloat(),
                    onValueChange = { toHour = it.toInt() },
                    valueRange = 0f..23f,
                    steps = 22
                )

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    ScheduleOverride(
                        blockSweepId = segment.blockSweepId,
                        fullName = selectedDay,
                        week1 = week1, week2 = week2, week3 = week3, week4 = week4, week5 = week5,
                        fromHour = fromHour, toHour = toHour,
                        notes = notes.ifBlank { null }
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existingOverride != null) {
                    TextButton(onClick = onRemove) { Text("Remove") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}