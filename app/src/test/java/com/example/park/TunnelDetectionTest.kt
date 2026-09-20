package com.example.park

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelDetectionTest {

    private val now = 1_000_000_000L
    private val driving = 15f // m/s, ~34 mph

    private fun suspect(
        gapMs: Long,
        speed: Float? = driving,
        sinceRegainMs: Long? = null,
        config: TunnelConfig = TunnelConfig()
    ) = shouldSuspectTunnel(now, now - gapMs, speed, sinceRegainMs?.let { now - it }, config)

    @Test
    fun stoppedAtRedLightFor60Seconds_doesNotDim() {
        // Last fix was at 0 m/s (a stationary car stops producing fixes) — silence is expected.
        assertFalse(suspect(gapMs = 60_000, speed = 0f))
    }

    @Test
    fun movingThenLongGap_dims() {
        assertTrue(suspect(gapMs = 25_000, speed = driving))
    }

    @Test
    fun gapAtOrBelowThreshold_doesNotDim() {
        assertFalse(suspect(gapMs = TUNNEL_GAP_THRESHOLD_MS))       // exactly the threshold: not yet
        assertFalse(suspect(gapMs = 5_000, speed = driving))
        assertTrue(suspect(gapMs = TUNNEL_GAP_THRESHOLD_MS + 1))    // one ms past it
    }

    @Test
    fun speedGate_isStrictlyAboveTheMinimum_andUnknownSpeedNeverDims() {
        assertFalse(suspect(gapMs = 30_000, speed = TUNNEL_MIN_SPEED_MPS)) // exactly 5 m/s: not "above"
        assertTrue(suspect(gapMs = 30_000, speed = TUNNEL_MIN_SPEED_MPS + 0.1f))
        assertFalse(suspect(gapMs = 30_000, speed = null))
    }

    @Test
    fun cooldownAfterRegain_isRespected() {
        assertFalse(suspect(gapMs = 30_000, sinceRegainMs = 30_000))                          // 30 s after regaining
        assertFalse(suspect(gapMs = 30_000, sinceRegainMs = TUNNEL_REGAIN_COOLDOWN_MS - 1))
        assertTrue(suspect(gapMs = 30_000, sinceRegainMs = TUNNEL_REGAIN_COOLDOWN_MS))        // cooldown over
        assertTrue(suspect(gapMs = 30_000, sinceRegainMs = null))                             // never regained
    }

    @Test
    fun settingOff_neverDims() {
        assertFalse(suspect(gapMs = 10 * 60_000, speed = 30f, config = TunnelConfig(enabled = false)))
    }
}
