package com.example.park


// If GPS fixes stop arriving for longer than this while driving, assume a tunnel (or similar
// signal-blocking environment) rather than routine GPS noise. Was 8s, which turned out to be
// way too tight in practice: continuous fixes come from LocationManager.GPS_PROVIDER alone
// (no network/fused blending), and raw satellite GPS routinely has multi-second gaps in a
// dense city — tall buildings, freeway underpasses, hills — that aren't remotely a tunnel.
// Real-world driving on the S25 confirmed this: repeated false triggers caused a visible
// full-screen "flicker" as the map's tile source and floating-icon tint flipped dark and
// back, sometimes several times a minute. Raised to 20s, trading slower detection of a
// genuine tunnel for many fewer false positives from ordinary urban GPS jitter — SF doesn't
// have many long highway tunnels, so a few extra seconds before flipping dark isn't a big
// loss, but repeated false flickers during normal surface-street driving was a real problem.
const val TUNNEL_GAP_THRESHOLD_MS = 20000L

/** Below this speed (m/s, ~11 mph) a silent GPS is far more likely a stopped car than a tunnel. */
const val TUNNEL_MIN_SPEED_MPS = 5f

/** After "GPS regained", don't dim again for this long — stops a flicker if the signal is patchy. */
const val TUNNEL_REGAIN_COOLDOWN_MS = 60_000L

/** Tuning knobs for [shouldSuspectTunnel], bundled so the map can build one from the Settings
 *  toggle and tests can vary each value independently. */
data class TunnelConfig(
    /** The "Auto-dim map in tunnels" setting. When false the answer is always "no". */
    val enabled: Boolean = true,
    val gapThresholdMs: Long = TUNNEL_GAP_THRESHOLD_MS,
    val minSpeedMps: Float = TUNNEL_MIN_SPEED_MPS,
    val regainCooldownMs: Long = TUNNEL_REGAIN_COOLDOWN_MS
)

/**
 * Whether a silence in GPS fixes should be treated as a tunnel (and the map dimmed). A pure
 * function of its inputs — no clock, no Android — so it can be unit tested on the JVM.
 *
 * "No fix for a while" alone is not enough evidence. With the driving distance filter this used
 * to be (2 m), Android stops delivering fixes while the car is stationary even though GPS is
 * perfectly healthy, so a long red light looked identical to a tunnel. The extra conditions:
 *  - the last fix must show the car MOVING (faster than [TunnelConfig.minSpeedMps]) — a car that
 *    was stopped when the fixes went quiet can't have just entered a tunnel; an unknown speed
 *    (null) counts as not moving, since a missed dim is cosmetic but a false one is a flicker.
 *  - not within [TunnelConfig.regainCooldownMs] of the last "GPS regained".
 *
 * @param lastFixSpeedMps speed reported by the most recent fix, or null if it had none.
 * @param lastRegainMs when the map last went from "suspected tunnel" back to normal, or null.
 */
fun shouldSuspectTunnel(
    nowMs: Long,
    lastFixMs: Long,
    lastFixSpeedMps: Float?,
    lastRegainMs: Long?,
    config: TunnelConfig = TunnelConfig()
): Boolean {
    if (!config.enabled) return false
    if (nowMs - lastFixMs <= config.gapThresholdMs) return false
    if (lastFixSpeedMps == null || lastFixSpeedMps <= config.minSpeedMps) return false
    if (lastRegainMs != null && nowMs - lastRegainMs < config.regainCooldownMs) return false
    return true
}
