package com.example.park

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

private const val MIN_RADIUS_FRACTION = 0.15f // lower bound: circle can't shrink past 15% of the viewport
private const val MAX_RADIUS_FRACTION = 0.5f  // upper bound: can't exceed half the viewport
private const val DEFAULT_RADIUS_FRACTION = 0.4f
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 4f

/**
 * Lets the user pick a circular crop region by dragging/pinch-zooming the PHOTO underneath a
 * fixed, centered circle — the same interaction pattern apps like Instagram use for profile
 * photo cropping, rather than a fixed photo with a draggable circle. The circle's radius is
 * still adjustable via slider, with a floor so it can't shrink into an unusably tiny sliver.
 *
 * The photo is shown at a base "fill the square viewport" scale (ContentScale.Crop) so
 * there's never an empty gap even before any pinch-zoom; pinching zooms in further from
 * there. Panning isn't bounds-clamped — if you drag far enough that the resulting crop would
 * fall outside the image, saveCroppedPhoto (in CarStyleDialog.kt) already clamps the final
 * crop rectangle to stay within the bitmap, so the worst case is a shifted-but-valid crop
 * rather than a broken one.
 */
@Composable
fun PhotoCropDialog(
    sourceBitmap: ImageBitmap,
    onConfirm: (centerXFraction: Float, centerYFraction: Float, radiusFraction: Float) -> Unit,
    onDismiss: () -> Unit
) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableStateOf(1f) }
    var radiusFraction by remember { mutableStateOf(DEFAULT_RADIUS_FRACTION) }
    var viewportSizePx by remember { mutableStateOf(IntSize.Zero) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose crop area") },
        text = {
            Column {
                Text(
                    "Drag or pinch the photo to position it inside the circle.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RectangleShape) // keeps zoomed/panned photo from visually spilling outside the viewport
                        .onSizeChanged { viewportSizePx = it }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                zoom = (zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                offset += pan
                            }
                        }
                ) {
                    Image(
                        bitmap = sourceBitmap,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = zoom,
                                scaleY = zoom,
                                translationX = offset.x,
                                translationY = offset.y
                            ),
                        contentScale = ContentScale.Crop
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val r = radiusFraction * minOf(size.width, size.height)
                        drawCircle(
                            color = Color.White,
                            radius = r,
                            center = Offset(size.width / 2f, size.height / 2f),
                            style = Stroke(width = 3.dp.toPx())
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Circle size", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = radiusFraction,
                    onValueChange = { radiusFraction = it.coerceIn(MIN_RADIUS_FRACTION, MAX_RADIUS_FRACTION) },
                    valueRange = MIN_RADIUS_FRACTION..MAX_RADIUS_FRACTION
                )
                TextButton(onClick = { offset = Offset.Zero; zoom = 1f }) { Text("Reset position") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (cx, cy, r) = resolveCropFractions(
                    sourceBitmap = sourceBitmap,
                    viewportSizePx = viewportSizePx,
                    offset = offset,
                    zoom = zoom,
                    radiusFraction = radiusFraction
                )
                onConfirm(cx, cy, r)
            }) { Text("Use This Crop") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Translates the on-screen crop (a fixed circle at the viewport's center, with the photo
 * dragged/zoomed underneath via ContentScale.Crop base-fill + graphicsLayer scale/translation)
 * back into fractions of the ORIGINAL bitmap's own dimensions, matching the
 * (centerXFraction, centerYFraction, radiusFraction) contract saveCroppedPhoto expects.
 *
 * Derivation: graphicsLayer scales/translates around the viewport's own center by default, so
 * the box-local point that ends up back at the viewport's center after the user's pan/zoom is
 * `center - offset / zoom`. That point is then converted from "base Crop-fill layout space"
 * into actual image-pixel coordinates using the same fill-scale ContentScale.Crop computed
 * internally (fit the larger of width/height ratios, then center).
 */
private fun resolveCropFractions(
    sourceBitmap: ImageBitmap,
    viewportSizePx: IntSize,
    offset: Offset,
    zoom: Float,
    radiusFraction: Float
): Triple<Float, Float, Float> {
    val viewportSize = viewportSizePx.width.toFloat().coerceAtLeast(1f) // square viewport (aspectRatio(1f))
    val imgW = sourceBitmap.width.toFloat()
    val imgH = sourceBitmap.height.toFloat()

    val baseScale = maxOf(viewportSize / imgW, viewportSize / imgH) // ContentScale.Crop fill factor
    val baseOffsetX = (viewportSize - imgW * baseScale) / 2f
    val baseOffsetY = (viewportSize - imgH * baseScale) / 2f

    val viewportCenter = viewportSize / 2f
    val localX = viewportCenter - offset.x / zoom
    val localY = viewportCenter - offset.y / zoom

    val imgCenterX = (localX - baseOffsetX) / baseScale
    val imgCenterY = (localY - baseOffsetY) / baseScale
    val centerXFraction = (imgCenterX / imgW).coerceIn(0f, 1f)
    val centerYFraction = (imgCenterY / imgH).coerceIn(0f, 1f)

    val effectiveScale = baseScale * zoom
    val radiusOnScreenPx = radiusFraction * viewportSize
    val radiusInImagePx = radiusOnScreenPx / effectiveScale
    val radiusAsFractionOfShorterSide = radiusInImagePx / minOf(imgW, imgH)

    return Triple(centerXFraction, centerYFraction, radiusAsFractionOfShorterSide)
}