package com.example.park

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SavedLocationPickerDialog(
    locations: List<SavedLocation>,
    onPick: (SavedLocation) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Park at a saved location") },
        text = {
            Column {
                locations.forEach { location ->
                    TextButton(onClick = { onPick(location) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LocationAvatar(location, size = 20.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(location.name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}