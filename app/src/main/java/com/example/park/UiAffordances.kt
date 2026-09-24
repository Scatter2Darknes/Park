package com.example.park

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Small UI helpers that make options and content DISCOVERABLE instead of silently hidden:
 *  - DependentSetting: an option that only works once another one is on stays visible, greyed out,
 *    with a "Needs: ..." line, instead of appearing only after the other switch is flipped.
 *  - Modifier.verticalScrollbar: a scroll bar that is always shown while there is more to scroll, so a
 *    height-capped dialog visibly has "more below" (Compose has no built-in scroll bar).
 */

/**
 * Wraps the options that depend on another setting. Always shown; indented under their parent with a
 * thin line on the left so they read as "part of the switch above". When [enabled] is false the block
 * is dimmed and starts with "Needs: [needs]". The controls inside must also be given `enabled` (a
 * Switch's `enabled`, a dropdown's text field's `enabled`) so they can't be changed while dimmed —
 * dimming alone doesn't stop taps.
 */
@Composable
fun DependentSetting(enabled: Boolean, needs: String, content: @Composable ColumnScope.() -> Unit) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(lineColor, size = Size(2.dp.toPx(), size.height)) }
            .padding(start = 12.dp)
    ) {
        if (!enabled) {
            Text(
                "Needs: $needs",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
        Column(Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA), content = content)
    }
}

/** How faded a disabled block is. Material's own disabled content uses 0.38; slightly more readable here,
 *  since the point is that the option can still be READ and understood. */
private const val DISABLED_ALPHA = 0.5f

/**
 * Draws a scroll bar on the right edge of a scrolling column, visible the whole time the content is
 * taller than the space it has (not only while scrolling, as Android's native bars do), so it doubles
 * as a "more below" hint. A faint track shows the full height; the thumb shows where you are and how
 * much there is.
 *
 * ORDER MATTERS: put it BEFORE `.verticalScroll(state)` in the modifier chain, so it draws over the
 * visible window rather than scrolling away with the content:
 *     Modifier.heightIn(max = 400.dp).verticalScrollbar(state).verticalScroll(state)
 * Give the content a little end padding so the bar doesn't sit on top of text.
 */
fun Modifier.verticalScrollbar(
    state: ScrollState,
    color: Color,
    width: Dp = 4.dp,
    minThumbHeight: Dp = 24.dp
): Modifier = drawWithContent {
    drawContent()
    val maxScroll = state.maxValue
    // 0: everything fits. Int.MAX_VALUE: not measured yet. Either way, no bar.
    if (maxScroll <= 0 || maxScroll == Int.MAX_VALUE) return@drawWithContent
    val viewport = size.height
    val barWidth = width.toPx()
    val x = size.width - barWidth
    val radius = CornerRadius(barWidth / 2)
    drawRoundRect(color.copy(alpha = 0.15f), topLeft = Offset(x, 0f), size = Size(barWidth, viewport), cornerRadius = radius)
    val thumbHeight = (viewport * viewport / (viewport + maxScroll)).coerceAtLeast(minThumbHeight.toPx()).coerceAtMost(viewport)
    val thumbTop = (viewport - thumbHeight) * state.value / maxScroll
    drawRoundRect(color.copy(alpha = 0.6f), topLeft = Offset(x, thumbTop), size = Size(barWidth, thumbHeight), cornerRadius = radius)
}
