package com.example.park

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch

sealed class ParkingFlowState {
    object Hidden : ParkingFlowState()
    data class ChoosingCar(val point: LatLng) : ParkingFlowState()
    data class Confirming(val carId: Long, val match: SegmentMatch, val point: LatLng) : ParkingFlowState()
    data class PickingManually(val carId: Long, val candidates: List<SegmentMatch>, val point: LatLng) : ParkingFlowState()

    // True no-match: not one nearby-but-too-far/ambiguous candidate, but zero candidates at
    // all — a garage, driveway or private lot with no street-cleaning data anywhere nearby.
    // See proceedToMatching's routing and saveUnmanagedParkedState.
    data class NoStreetNearby(val carId: Long, val point: LatLng) : ParkingFlowState()

    // The manual "I'm Parked" point matched a safe-tagged Saved Location (see
    // MapScreen.kt's resolveParkingFlow) — being near it isn't proof the car is actually IN
    // the garage rather than legally parked on the street out front, so this asks instead of
    // assuming (unlike Bluetooth auto-park, where the assumption is fine).
    data class ConfirmingSafeLocation(val carId: Long, val location: SavedLocation, val point: LatLng) : ParkingFlowState()

    // Confirmation step for the "select from map" escape hatch specifically — every other
    // selection path (distance-sorted list, manual picker) already shows the street/side back
    // to the user before committing; a map tap had no equivalent check before this.
    data class ConfirmingSide(val carId: Long, val segment: StreetSegment, val point: LatLng) : ParkingFlowState()

    data class AskingForPin(val carId: Long, val segment: StreetSegment, val point: LatLng) : ParkingFlowState()

    // Offered as an extra option from AskingForPin when a confident, currently-enforced
    // MeteredZone match exists for the point — see MapScreen.kt. Not a MovedCarAction-style
    // auto-decision: the user types the actual deadline themselves (see MeterTimer.kt).
    data class AskingForMeterTimer(val carId: Long, val segment: StreetSegment, val point: LatLng, val meter: MeteredZone) : ParkingFlowState()

    data class DroppingPin(val carId: Long, val segment: StreetSegment, val originalPoint: LatLng) : ParkingFlowState()

    // Manual-picker escape hatch: instead of choosing from the distance-sorted list, the
    // user taps a street directly on the map. Reuses the existing segment tap-handling
    // infrastructure rather than GPS matching, so it works even with poor/no GPS.
    data class PickingViaMap(val carId: Long, val originPoint: LatLng) : ParkingFlowState()
}

/**
 * Carries a Bluetooth-disconnect auto-detect notification's tap-through into MapScreen: the
 * car and GPS point BluetoothDisconnectReceiver captured at disconnect time, re-evaluated
 * fresh against current segment data (via proceedToMatching) rather than serialized through
 * the notification's Intent extras.
 */
data class PendingAutoDetect(val carId: Long, val point: LatLng)

@Composable
fun ParkingConfirmationDialog(match: SegmentMatch, onConfirm: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Is this where you parked?") },
        text = {
            Column {
                Text(match.segment.corridor, fontWeight = FontWeight.Bold)
                Text(match.segment.limits)
                Text("${match.segment.blockSide} side")
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Yes") } },
        dismissButton = { TextButton(onClick = onReject) { Text("No, pick manually") } }
    )
}

/**
 * Confirmation step specifically for the "select from map" escape hatch — a direct map tap
 * has no GPS-confidence signal behind it the way the other selection paths do, so this asks
 * the user to eyeball the street/side back before moving on to "add an exact pin?", the same
 * way ParkingConfirmationDialog already does for a GPS-matched guess.
 */
@Composable
fun ConfirmSideDialog(segment: StreetSegment, onConfirm: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Is this the correct side?") },
        text = {
            Column {
                Text(segment.corridor, fontWeight = FontWeight.Bold)
                Text(segment.limits)
                Text("${segment.blockSide} side")
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Yes") } },
        dismissButton = { TextButton(onClick = onReject) { Text("No, tap again") } }
    )
}




/**
 * The "Simple" parking-confirmation style's whole flow in a single dialog — collapses what
 * CAUTIOUS mode does as two sequential dialogs (confirm the street/side, then separately ask
 * about an exact pin) into one, for someone who's decided they'd rather trade that extra
 * checkpoint for fewer taps. Used for both entry points that would otherwise show two dialogs
 * (the GPS-matched guess and the manual map-tap path) — [rejectLabel] is the only thing that
 * differs between them, since "reject" means something slightly different for each (pick a
 * different street from the list, vs. tap the map again).
 */
@Composable
fun QuickParkConfirmDialog(
    segment: StreetSegment,
    rejectLabel: String,
    onConfirm: (dropExactPin: Boolean) -> Unit,
    onReject: () -> Unit
) {
    var dropPin by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("Park here?") },
        text = {
            Column {
                Text(segment.corridor, fontWeight = FontWeight.Bold)
                Text(segment.limits)
                Text("${segment.blockSide} side")
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { dropPin = !dropPin }
                ) {
                    Checkbox(checked = dropPin, onCheckedChange = { dropPin = it })
                    Text("Drop an exact pin at my current location")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(dropPin) }) { Text("Yes, park here") } },
        dismissButton = { TextButton(onClick = onReject) { Text(rejectLabel) } }
    )
}

@Composable
fun ManualSegmentPicker(
    candidates: List<SegmentMatch>,
    onPick: (StreetSegment) -> Unit,
    onPickFromMap: () -> Unit,
    onDismiss: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val visibleCandidates = if (expanded) candidates else candidates.take(5)
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which side did you park on?") },
        text = {
            Column {
                Row(modifier = Modifier.heightIn(max = 320.dp)) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(scrollState)
                    ) {
                        if (candidates.isEmpty()) {
                            Text("No nearby streets found in our data.")
                        }
                        visibleCandidates.forEach { match ->
                            TextButton(onClick = { onPick(match.segment) }) {
                                Text("${match.segment.corridor} (${match.segment.blockSide}) \u2014 ${"%.0f".format(match.distanceMeters)}m away")
                            }
                        }
                        if (!expanded && candidates.size > 5) {
                            TextButton(onClick = { expanded = true }) {
                                Text("Show more (${candidates.size - 5} more)")
                            }
                        }
                    }

                    if (scrollState.maxValue > 0) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(16.dp)
                                .padding(start = 4.dp)
                        ) {
                            val trackHeightPx = constraints.maxHeight.toFloat()
                            val viewportFraction = trackHeightPx / (trackHeightPx + scrollState.maxValue)
                            val thumbHeightPx = (trackHeightPx * viewportFraction).coerceAtLeast(60f)
                            val scrollFraction = scrollState.value.toFloat() / scrollState.maxValue.toFloat()
                            val thumbOffsetPx = scrollFraction * (trackHeightPx - thumbHeightPx)

                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .width(4.dp)
                                    .align(Alignment.TopCenter)
                                    .background(Color.LightGray, RoundedCornerShape(2.dp))
                            )

                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(with(LocalDensity.current) { thumbHeightPx.toDp() })
                                    .offset { IntOffset(0, thumbOffsetPx.toInt()) }
                                    .background(Color.DarkGray, RoundedCornerShape(2.dp))
                                    .pointerInput(scrollState.maxValue) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            val newOffsetPx = (thumbOffsetPx + dragAmount.y)
                                                .coerceIn(0f, trackHeightPx - thumbHeightPx)
                                            val newFraction = newOffsetPx / (trackHeightPx - thumbHeightPx)
                                            coroutineScope.launch {
                                                scrollState.scrollTo((newFraction * scrollState.maxValue).toInt())
                                            }
                                        }
                                    }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onPickFromMap) { Text("Select from map instead") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}