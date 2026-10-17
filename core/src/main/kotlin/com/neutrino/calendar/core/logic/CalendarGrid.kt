package com.neutrino.calendar.core.logic

import com.neutrino.calendar.core.model.CalendarContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** The five ways the Calendar tab shows time. Remembered per device. */
enum class CalendarMode(val label: String) {
    DAY("Day"), WEEK("Week"), MONTH("Month"), YEAR("Year"), AGENDA("Agenda");

    companion object {
        const val STORAGE_KEY = "ncal.calendar.mode"
    }
}

/** The dates behind the month, week and year layouts. Pure arithmetic over a [CalendarContext]. */
object CalendarGrid {

    /** The days of `month`'s month in whole weeks from the first weekday, padded with neighbours' days. */
    fun monthDays(month: LocalDate, context: CalendarContext): List<LocalDate> {
        val first = month.withDayOfMonth(1)
        val dayCount = YearMonth.from(first).lengthOfMonth()
        val lead = (first.dayOfWeek.value - context.firstDayOfWeek.value + 7) % 7
        val rows = (lead + dayCount + 6) / 7
        val start = first.minusDays(lead.toLong())
        return (0 until rows * 7).map { start.plusDays(it.toLong()) }
    }

    /** The seven days of the week containing `day`, from the first weekday. */
    fun weekDays(containing: LocalDate, context: CalendarContext): List<LocalDate> {
        val back = (containing.dayOfWeek.value - context.firstDayOfWeek.value + 7) % 7
        val start = containing.minusDays(back.toLong())
        return (0 until 7).map { start.plusDays(it.toLong()) }
    }

    /** The narrow weekday symbols ("S", "M", …) in the week's order. */
    fun weekdaySymbols(context: CalendarContext, locale: Locale = Locale.getDefault()): List<String> =
        (0 until 7).map { context.firstDayOfWeek.plus(it.toLong()).getDisplayName(TextStyle.NARROW_STANDALONE, locale) }

    /** The first of each month containing any of `days`, in order: what has to be loaded to show them. */
    fun months(covering: List<LocalDate>): List<LocalDate> = covering.map { it.withDayOfMonth(1) }.distinct()
}

/**
 * Where each timed occurrence sits in one day's column of the week or day view.
 *
 * Positions are minutes from the day's start, as the web's `getEventDayBounds` works them out: an
 * occurrence is clamped to the day, and a short one is drawn at least [MINIMUM_MINUTES] tall.
 * Unlike the web, overlapping occurrences sit side by side rather than on top of each other.
 */
object TimeGridLayout {

    data class Placed(
        val occurrence: EventOccurrence,
        /** Minutes from the day's start to the top of the block, 0–1440. */
        val startMinute: Double,
        /** Minutes from the day's start to the bottom of the block, after the minimum height. */
        val endMinute: Double,
        /** Which of [columns] side-by-side slots this block takes, from 0. */
        val column: Int,
        val columns: Int,
    ) {
        val id: String get() = occurrence.id
    }

    /** The web's minimum: 24 px against a 60 px hour. */
    const val MINIMUM_MINUTES = 24.0

    /** Every timed occurrence that touches `day`, positioned. All-day ones go in the row above. */
    fun layout(occurrences: List<EventOccurrence>, day: LocalDate, context: CalendarContext): List<Placed> {
        val dayStart = context.startOf(day)
        val dayEnd = context.startOf(day.plusDays(1))
        val dayLength = minutes(dayStart, dayEnd)

        data class Span(val occurrence: EventOccurrence, val start: Double, val end: Double)

        val spans = occurrences
            .filter { !it.event.allDay && it.start < dayEnd && it.end > dayStart }
            .map { o ->
                val start = minutes(dayStart, maxOf(o.start, dayStart))
                val end = minutes(dayStart, minOf(o.end, dayEnd))
                val shown = minOf(maxOf(end, start + MINIMUM_MINUTES), dayLength)
                Span(o, minOf(start, dayLength - MINIMUM_MINUTES), shown)
            }
            .sortedWith(
                compareBy<Span> { it.start }.thenByDescending { it.end }
                    .thenComparator { a, b -> titleOrder(a.occurrence.event.title, b.occurrence.event.title) },
            )

        // Group occurrences that overlap, directly or through a chain, then give each the first
        // column free at its start. Every block in a group shares the group's column count.
        val placed = mutableListOf<Placed>()
        val group = mutableListOf<Pair<Span, Int>>()
        var groupEnd = Double.NEGATIVE_INFINITY

        fun flush() {
            val columns = (group.maxOfOrNull { it.second } ?: -1) + 1
            group.forEach { (s, c) -> placed += Placed(s.occurrence, s.start, s.end, c, columns) }
            group.clear()
        }

        for (span in spans) {
            if (span.start >= groupEnd) {
                flush()
                groupEnd = Double.NEGATIVE_INFINITY
            }
            val busy = group.filter { it.first.end > span.start }.map { it.second }.toSet()
            val column = generateSequence(0) { it + 1 }.first { it !in busy }
            group += span to column
            groupEnd = maxOf(groupEnd, span.end)
        }
        flush()
        return placed
    }

    /** The all-day occurrences on `day`, for the row above the grid. */
    fun allDay(occurrences: List<EventOccurrence>, day: LocalDate, context: CalendarContext): List<EventOccurrence> =
        occurrences.filter { it.event.allDay && EventDayRange.of(it, context).contains(day) }
            .sortedWith { a, b -> titleOrder(a.event.title, b.event.title) }

    /** Minutes since the start of `day` for `now`, or `null` when `now` is another day. */
    fun nowMinute(now: Instant, day: LocalDate, context: CalendarContext): Double? =
        if (context.day(now) != day) null else minutes(context.startOf(day), now)

    private fun minutes(from: Instant, to: Instant) = Duration.between(from, to).toMillis() / 60_000.0
}

/**
 * `localizedStandardCompare`, near enough: case-insensitive, with runs of digits compared as
 * numbers, so "Item 2" sorts before "Item 10".
 */
fun titleOrder(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ei = i
            while (ei < a.length && a[ei].isDigit()) ei++
            var ej = j
            while (ej < b.length && b[ej].isDigit()) ej++
            val na = a.substring(i, ei).trimStart('0')
            val nb = b.substring(j, ej).trimStart('0')
            val c = if (na.length != nb.length) na.length - nb.length else na.compareTo(nb)
            if (c != 0) return c
            i = ei
            j = ej
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}
