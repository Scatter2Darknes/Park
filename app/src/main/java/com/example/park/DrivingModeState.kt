package com.example.park

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Driving Mode is currently active, exposed at the app level (not just as MapScreen's
 * local state) so MainActivity can freeze the Color Theme resolution while driving.
 *
 * A theme flip mid-drive — whether that's AUTO_TIME crossing its day/night boundary, or the
 * intermittent "randomly resolves as AUTO instead of AUTO_TIME" bug (see the diagnostic log
 * added in resolveIsDarkColorTheme's else-branch) — is a real visibility hazard behind the
 * wheel. Rather than chase every possible cause of a theme recomputation, this freezes
 * whatever isDarkTheme resolved to at the moment driving starts, and only lets it move again
 * once driving mode ends — so the map's brightness can no longer change out from under the
 * driver regardless of what triggers a theme recomputation.
 */
object DrivingModeState {
    private val _isActive = MutableStateFlow(false)
    val isActive = _isActive.asStateFlow()

    fun setActive(active: Boolean) {
        _isActive.value = active
    }
}