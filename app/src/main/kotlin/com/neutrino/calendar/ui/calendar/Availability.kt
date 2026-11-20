package com.neutrino.calendar.ui.calendar

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.neutrino.calendar.core.logic.EventOccurrence
import com.neutrino.calendar.core.model.EventAvailability
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The fill behind an event on the calendar, by its availability, always in its calendar's colour.
 * The same four as the web (`calendar/availability.module.css`) and iOS (`AvailabilityFill.swift`):
 *
 * - busy: the tint every event had before availability existed.
 * - tentative: the tint in diagonal stripes.
 * - free: hollow, with a dashed outline.
 * - away (out of office): a deeper fill.
 *
 * Drawn behind the content, so put it after the `clip` that rounds the chip.
 */
fun Modifier.availabilityFill(
    availability: EventAvailability,
    color: Color,
    corner: Dp = 4.dp,
    tint: Float = 0.18f,
): Modifier = drawBehind {
    val radius = CornerRadius(corner.toPx())
    when (availability) {
        EventAvailability.BUSY -> drawRoundRect(color.copy(alpha = tint), cornerRadius = radius)
        EventAvailability.AWAY -> drawRoundRect(color.copy(alpha = min(1f, tint * 2.5f)), cornerRadius = radius)
        EventAvailability.TENTATIVE -> {
            drawRoundRect(color.copy(alpha = tint * 0.3f), cornerRadius = radius)
            // 45° stripes as wide as the gaps between them.
            val stripe = 4.dp.toPx()
            val step = stripe * 2 * sqrt(2f)
            val stripeColor = color.copy(alpha = min(1f, tint * 1.6f))
            var x = -size.height
            while (x < size.width) {
                drawLine(stripeColor, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = stripe)
                x += step
            }
        }
        EventAvailability.FREE -> {
            val width = 1.dp.toPx()
            drawRoundRect(
                color.copy(alpha = 0.85f),
                topLeft = Offset(width / 2, width / 2),
                size = Size(size.width - width, size.height - width),
                cornerRadius = radius,
                style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx()))),
            )
        }
    }
}

/**
 * A month day's dot for one event: filled, or a ring for a free event, which doesn't take the time.
 * The other kinds are too small to tell apart at 5dp and are shown in the day's list.
 */
@Composable
fun AvailabilityDot(availability: EventAvailability, color: Color) {
    val dot = Modifier.size(5.dp).clip(CircleShape)
    if (availability == EventAvailability.FREE) Box(dot.border(1.dp, color, CircleShape))
    else Box(dot.background(color))
}

/** A task on the calendar has none of its own and reads as busy. */
val EventOccurrence.availability: EventAvailability
    get() = if (task == null) event.availability else EventAvailability.BUSY
