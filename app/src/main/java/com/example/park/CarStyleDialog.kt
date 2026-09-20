package com.example.park

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

@Composable
fun CarStyleDialog(
    car: Car,
    onSave: (name: String, colorHex: String, iconEmoji: String, photoPath: String?, widgetTextColorHex: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editedName by remember { mutableStateOf(car.name) }
    var selectedColor by remember { mutableStateOf(car.colorHex ?: DEFAULT_CAR_COLOR_HEX) }
    var selectedIcon by remember { mutableStateOf(car.iconEmoji ?: DEFAULT_CAR_ICON) }
    var selectedWidgetTextColor by remember { mutableStateOf(car.widgetTextColorHex ?: DEFAULT_WIDGET_TEXT_COLOR_HEX) }
    var pendingPhotoPath by remember { mutableStateOf(car.photoPath) }
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
                if (corrected != null) {
                    cropSourceBitmap = corrected // presence of this triggers the crop dialog below
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${car.name}") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = editedName,
                    onValueChange = { editedName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))

                PreviewSwatch(colorHex = selectedColor, icon = selectedIcon, photoPath = pendingPhotoPath)
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
                }
                Text(
                    "Color",
                    style = MaterialTheme.typography.labelMedium
                )
                Text(
                    "Also used for this car's highlighted street on the map \u2014 stays " +
                            "in effect even with a photo set.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CAR_COLOR_PALETTE.forEach { hex ->
                        ColorSwatch(
                            hex = hex,
                            selected = hex.equals(selectedColor, ignoreCase = true),
                            onClick = { selectedColor = hex }
                        )
                    }
                }

                if (pendingPhotoPath == null) {
                    Spacer(Modifier.height(16.dp))

                    Text("Icon", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CAR_ICON_PALETTE.forEach { emoji ->
                            IconSwatch(
                                emoji = emoji,
                                selected = emoji == selectedIcon,
                                onClick = { selectedIcon = emoji }
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Icon is hidden while a photo is set \u2014 remove the photo to use " +
                                "it instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text("Widget Text Color", style = MaterialTheme.typography.labelMedium)
                Text(
                    "Color of the text on the home screen widget, over its photo or status color.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WIDGET_TEXT_COLOR_PALETTE.forEach { hex ->
                        ColorSwatch(
                            hex = hex,
                            selected = hex.equals(selectedWidgetTextColor, ignoreCase = true),
                            onClick = { selectedWidgetTextColor = hex }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(editedName.trim(), selectedColor, selectedIcon, pendingPhotoPath, selectedWidgetTextColor)
                },
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
                    val path = saveCroppedPhoto(context, bitmap, cx, cy, radius, "car_photos", car.id)
                    if (path != null) pendingPhotoPath = path
                    cropSourceBitmap = null
                }
            },
            onDismiss = { cropSourceBitmap = null }
        )
    }
}

/**
 * Decodes a picked image and bakes in its EXIF rotation, since BitmapFactory.decodeFile()
 * (used everywhere a photo is later loaded) does NOT apply EXIF orientation automatically
 * — a portrait photo's raw pixel data is often landscape with a "rotate 90°" tag, and nothing
 * downstream was reading that tag, which is why photos were showing up sideways. Also
 * downscales generously, since this is headed for a small circular avatar, not a full-res
 * photo viewer. Returns an ImageBitmap for display in the crop-selection dialog — nothing is
 * saved to disk yet, since the user hasn't chosen a crop region. Shared between car and
 * saved-location photo customization, since the logic has nothing car-specific in it.
 */
suspend fun loadAndCorrectOrientation(context: Context, uri: Uri): ImageBitmap? =
    withContext(Dispatchers.IO) {
        try {
            val rawBitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: return@withContext null

            val orientation = context.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL

            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            val corrected = if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            } else {
                rawBitmap
            }

            val maxDimension = 1024
            val scale = minOf(1f, maxDimension.toFloat() / maxOf(corrected.width, corrected.height))
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    corrected, (corrected.width * scale).toInt(), (corrected.height * scale).toInt(), true
                )
            } else corrected

            scaled.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

/**
 * Crops [sourceBitmap] to the square region implied by the user's chosen circle (center +
 * radius, as fractions of the image), and saves it into the app's own persistent storage
 * under [subDir], named using [fileId]. Saving a square crop centered exactly on the chosen
 * circle — rather than baking in an alpha mask — is deliberate: every place a photo is later
 * displayed already renders it inside a circular clip (CarAvatar/LocationAvatar's
 * Modifier.clip(CircleShape), the map marker's BitmapShader crop), so a centered square crop
 * reproduces exactly the circle the user selected with no extra masking step needed. Shared
 * between car and saved-location photos — [subDir] and [fileId] are what keep their files
 * from colliding on disk.
 */
suspend fun saveCroppedPhoto(
    context: Context,
    sourceBitmap: ImageBitmap,
    centerXFraction: Float,
    centerYFraction: Float,
    radiusFraction: Float,
    subDir: String,
    fileId: Long
): String? = withContext(Dispatchers.IO) {
    try {
        val androidBitmap = sourceBitmap.asAndroidBitmap()
        val shorterSide = minOf(androidBitmap.width, androidBitmap.height)
        val radiusPx = (radiusFraction * shorterSide).toInt().coerceAtLeast(1).coerceAtMost(shorterSide / 2)
        val diameter = radiusPx * 2

        val centerXPx = (centerXFraction * androidBitmap.width).toInt()
        val centerYPx = (centerYFraction * androidBitmap.height).toInt()
        val left = (centerXPx - radiusPx).coerceIn(0, androidBitmap.width - diameter)
        val top = (centerYPx - radiusPx).coerceIn(0, androidBitmap.height - diameter)

        val cropped = Bitmap.createBitmap(androidBitmap, left, top, diameter, diameter)

        val dir = File(context.filesDir, subDir).apply { mkdirs() }
        val destFile = File(dir, "${subDir}_${fileId}_${System.currentTimeMillis()}.jpg")
        FileOutputStream(destFile).use { output ->
            cropped.compress(Bitmap.CompressFormat.JPEG, 90, output)
        }
        destFile.absolutePath
    } catch (e: Exception) {
        null
    }
}

@Composable
private fun PreviewSwatch(colorHex: String, icon: String, photoPath: String?) {
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
private fun ColorSwatch(hex: String, selected: Boolean, onClick: () -> Unit) {
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
private fun IconSwatch(emoji: String, selected: Boolean, onClick: () -> Unit) {
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