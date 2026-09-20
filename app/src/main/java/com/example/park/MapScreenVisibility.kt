package com.example.park

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether MapScreen is both the currently-selected screen (per MainActivity's `currentScreen`)
 * AND the Activity is actually resumed (not just backgrounded while nominally "on" the map
 * screen) — i.e., whether the map's live connection toast/pill/ring are genuinely on-screen
 * right now. The Bluetooth receivers check this before posting "parked"/"unparked"/"just
 * connected" notifications, since those would otherwise duplicate what the map is already
 * showing in real time.
 *
 * In-memory only, same as BluetoothConnectionCenter/DrivingModeState — and correctly so here:
 * if the app's process isn't running at all, this defaults to false, which is exactly right
 * (nothing is visible, so the notification should fire normally). No persistence needed for a
 * property that's only ever meaningful while the process asking about it is itself alive.
 */
object MapScreenVisibility {
    private val _isVisible = MutableStateFlow(false)
    val isVisible = _isVisible.asStateFlow()

    fun setVisible(visible: Boolean) {
        _isVisible.value = visible
    }
}