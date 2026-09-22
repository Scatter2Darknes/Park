package com.example.park

/**
 * DEBUG BUILDS ONLY. One latitude/longitude extra of a DebugControlReceiver broadcast, read without ever throwing.
 *
 * WHY STRINGS: `am broadcast --ed` (a double extra) doesn't exist on Android 10 (API 29), so scripts send
 * `--es lat 37.7802` and the receiver parses the text. Numeric extras (from `--ed` on a newer `am`, or from code) are
 * still accepted so existing callers keep working.
 */
sealed class DebugCoordinate {
    data class Value(val degrees: Double) : DebugCoordinate()
    object Missing : DebugCoordinate()
    data class Unparsable(val raw: String) : DebugCoordinate()

    /** What is wrong with the extra called [name], as a sentence for the ParkDebug log; null when it is fine. */
    fun problem(name: String): String? = when (this) {
        is Value -> null
        Missing -> "$name is missing (send --es $name <degrees>)"
        is Unparsable -> "$name is not a number: '$raw' (send --es $name <degrees>)"
    }

    companion object {
        /** [raw] is whatever the extras bundle holds under the key: a String, any Number, or null when absent. */
        fun parse(raw: Any?): DebugCoordinate = when (raw) {
            null -> Missing
            is Number -> raw.toDouble().let { if (it.isFinite()) Value(it) else Unparsable(raw.toString()) }
            else -> raw.toString().trim().toDoubleOrNull()
                ?.takeIf { it.isFinite() }
                ?.let { Value(it) }
                ?: Unparsable(raw.toString())
        }
    }
}
