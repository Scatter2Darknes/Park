package com.example.park

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Color/icon/photo customization for a saved location — mirrors CarStyleDialog, reusing its
 * loadAndCorrectOrientation (EXIF fix + downscale) and saveCroppedPhoto (crop + persist)
 * helpers rather than duplicating that logic, since neither is car-specific internally.
 * Unlike cars, a location's color has no second use elsewhere (cars' colors also drive the
 * map's highlight polyline), so hiding color+icon entirely while a photo is set is fine here.
 */
@Composable
fun LocationStyleDialog(
    location: SavedLocation,
    onSave: (name: String, colorHex: String, iconEmoji: String, photoPath: String?, isSafeFromSweeping: Boolean, isOffStreet: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editedName by remember { mutableStateOf(location.name) }
    var selectedColor by remember { mutableStateOf(location.colorHex ?: DEFAULT_LOCATION_COLOR_HEX) }
    var selectedIcon by remember { mutableStateOf(location.iconEmoji ?: DEFAULT_LOCATION_ICON) }
    var pendingPhotoPath by remember { mutableStateOf(location.photoPath) }
    // Both flags as one choice (see SpotKind): all three options are always visible, instead of an
    // "off the street" switch that only appeared after turning "safe from street cleaning" on.
    var spotKind by remember { mutableStateOf(location.spotKind) }
    var isProcessingPhoto by remember { mutableStateOf(false) }
    var cropSourceBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            isProcessingPhoto = true
            scope.launch {
                val corrected = loadAndCorrectOrientation(context, uri)
                isProcessingPhoto = false
                if (corrected != null) cropSourceBitmap = corrected
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${location.name}") },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                // First after the name: it decides which reminders a car parked here gets, so it must be
                // seen without scrolling (the dialog is height-capped and scrolls).
                Text("What kind of spot is this?", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Applies when you park here, manually or via Bluetooth auto-park.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                // selectableGroup + selectable(role = RadioButton) make screen readers announce this as
                // one group of three options, "1 of 3, selected", like a native radio list.
                Column(Modifier.selectableGroup()) {
                    SpotKind.entries.forEach { kind ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = spotKind == kind, role = Role.RadioButton, onClick = { spotKind = kind })
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(selected = spotKind == kind, onClick = null) // the whole row handles the tap
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(kind.title, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    kind.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                LocationPreviewSwatch(colorHex = selectedColor, icon = selectedIcon, photoPath = pendingPhotoPath)
                Spacer(Modifier.height(16.dp))

                Row {
                    TextButton(
                        onClick = { photoPickerLauncher.launch("image/*") },
                        enabled = !isProcessingPhoto
                    ) {
                        Text(if (isProcessingPhoto) "Loading photo\u2026" else "Choose Photo")
                    }
                    if (pendingPhotoPath != null) {
                        TextButton(onClick = { pendingPhotoPath = null }) { Text("Remove Photo") }
                    }
                }

                if (pendingPhotoPath == null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Color", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CAR_COLOR_PALETTE.forEach { hex ->
                            LocationColorSwatch(
                                hex = hex,
                                selected = hex.equals(selectedColor, ignoreCase = true),
                                onClick = { selectedColor = hex }
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("Icon", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LOCATION_ICON_PALETTE.forEach { emoji ->
                            LocationIconSwatch(
                                emoji = emoji,
                                selected = emoji == selectedIcon,
                                onClick = { selectedIcon = emoji }
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Color and icon are hidden while a photo is set \u2014 remove the " +
                                "photo to use them instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(editedName.trim(), selectedColor, selectedIcon, pendingPhotoPath, spotKind.isSafeFromSweeping, spotKind.isOffStreet) },
                enabled = editedName.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    cropSourceBitmap?.let { bitmap ->
        PhotoCropDialog(
            sourceBitmap = bitmap,
            onConfirm = { cx, cy, radius ->
                scope.launch {
                    val path = saveCroppedPhoto(context, bitmap, cx, cy, radius, "location_photos", location.id)
                    if (path != null) pendingPhotoPath = path
                    cropSourceBitmap = null
                }
            },
            onDismiss = { cropSourceBitmap = null }
        )
    }
}

@Composable
private fun LocationPreviewSwatch(colorHex: String, icon: String, photoPath: String?) {
    val bitmap = photoPath?.let { path -> remember(path) { loadPhotoBitmap(path) } }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(CircleShape).border(2.dp, Color.White, CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(android.graphics.Color.parseColor(colorHex)))
                .border(2.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, fontSize = 22.sp)
        }
    }
}

@Composable
private fun LocationColorSwatch(hex: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(android.graphics.Color.parseColor(hex)))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.LightGray,
                shape = CircleShape
            )
            .clickable(onClick = onClick)
    )
}

@Composable
private fun LocationIconSwatch(emoji: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.LightGray,
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = 18.sp)
    }
}