package com.example.park

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CarSelectionDialog(cars: List<Car>, onPick: (Car) -> Unit, onAddNew: (String) -> Unit, onDismiss: () -> Unit) {
    var addingNew by remember { mutableStateOf(false) }
    var newCarName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which car are you parking?") },
        text = {
            Column {
                if (!addingNew) {
                    cars.forEach { car ->
                        TextButton(onClick = { onPick(car) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CarAvatar(car, size = 20.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(car.name)
                            }
                        }
                    }
                    TextButton(onClick = { addingNew = true }) {
                        Text("+ Add new car")
                    }
                } else {
                    OutlinedTextField(
                        value = newCarName,
                        onValueChange = { newCarName = it },
                        label = { Text("Car name") },
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            if (addingNew) {
                TextButton(
                    onClick = { if (newCarName.isNotBlank()) onAddNew(newCarName) },
                    enabled = newCarName.isNotBlank()
                ) { Text("Add") }
            }
        },
        dismissButton = {
            if (addingNew) TextButton(onClick = { addingNew = false }) { Text("Back") }
        }
    )
}