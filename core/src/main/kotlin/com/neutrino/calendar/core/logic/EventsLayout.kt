package com.neutrino.calendar.core.logic

import com.neutrino.calendar.core.model.CalendarContext
import com.neutrino.calendar.core.model.CalendarEvent
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** One day of the agenda and every occurrence on it, multi-day events included on each of their days. */
data class DaySection(val day: LocalDate, val occurrences: List<EventOccurrence>)

/**
 * The month arithmetic and ordering every view shares. The month is the unit because it is the web's:
 * `monthRange` in `calendarHelpers.ts` asks for local midnight on the 1st through 23:59:59 on the
 * last day, and expands over the same span, which keeps an occurrence near a month boundary on the
 * same side of it in both clients.
 */
object EventsLayout {

    /** The web's `monthRange`: local midnight on the 1st through 23:59:59 on the last day. */
    fun monthRange(month: LocalDate, context: CalendarContext): Pair<Instant, Instant> {
        val first = month.withDayOfMonth(1)
        val lastDay = first.plusMonths(1).minusDays(1)
        return context.startOf(first) to context.instant(lastDay, LocalTime.of(23, 59, 59))
    }

    /** All-day first, then by start, then by title, so a refresh doesn't reshuffle events sharing a start. */
    val dayOrder: Comparator<EventOccurrence> = Comparator { a, b ->
        when {
            a.event.allDay != b.event.allDay -> if (a.event.allDay) -1 else 1
            a.start != b.start -> a.start.compareTo(b.start)
            else -> titleOrder(a.event.title, b.event.title)
        }
    }

    /** Every day of `month` with its occurrences, empty days dropped: the web's `AgendaView`. */
    fun layout(occurrences: List<EventOccurrence>, month: LocalDate, context: CalendarContext): List<DaySection> {
        val ranges = occurrences.map { it to EventDayRange.of(it, context) }
        val first = month.withDayOfMonth(1)
        return (0 until first.lengthOfMonth()).mapNotNull { offset ->
            val day = first.plusDays(offset.toLong())
            val onDay = ranges.filter { it.second.contains(day) }.map { it.first }.sortedWith(dayOrder)
            if (onDay.isEmpty()) null else DaySection(day, onDay)
        }
    }

    /**
     * `held` with every event in `changed` or `deleted` taken out, and those of `changed` that belong
     * to the range put back in. An exception belongs wherever its series is held, and goes with it.
     */
    fun merge(held: List<CalendarEvent>, changed: List<CalendarEvent>, deleted: Set<String>, from: Instant, to: Instant): List<CalendarEvent> {
        val touched = deleted + changed.map { it.id }
        val rows = held.filter { it.id !in touched } + changed.filter { it.recurringEventId != null || belongs(it, from, to) }
        val series = rows.filter { it.recurringEventId == null }.map { it.id }.toSet()
        return rows.filter { it.recurringEventId?.let(series::contains) ?: true }
    }

    /** The server's own test for listing an event in a range: it starts by the end, and ends in it or repeats. */
    fun belongs(event: CalendarEvent, from: Instant, to: Instant): Boolean =
        event.start <= to && (event.end >= from || event.recurrenceRule != null)
}

// ── Widgets ─────────────────────────────────────────────────────────────────────────────────────

/** One occurrence as the widgets show it, already expanded and filtered by the app. */
@Serializable
data class WidgetEvent(
    /** An [EventLink] string, which is what a tap on it opens. */
    val id: String,
    val title: String,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
    val allDay: Boolean,
    /** The first and last local days it covers, `yyyy-MM-dd`. */
    val firstDay: String,
    val lastDay: String,
    val location: String? = null,
    /** Its calendar's colour, `#rrggbb`; null draws it in the accent colour. */
    val color: String? = null,
) {
    val start: Instant get() = Instant.ofEpochSecond(startEpochSeconds)
    val end: Instant get() = Instant.ofEpochSecond(endEpochSeconds)
}

/**
 * What the widgets and nothing else know about the calendar: the coming fortnight's events, the days
 * of this month and next that have any, and the week start. Written by the app whenever the calendar
 * changes. **Never tokens, and nothing the widgets don't draw** — no notes, no attendees.
 */
@Serializable
data class WidgetSnapshot(
    val signedIn: Boolean,
    val generatedAtEpochSeconds: Long,
    /** The zone the day keys were worked out in. */
    val timeZone: String,
    /** 1 = Monday … 7 = Sunday, `DayOfWeek.value`. */
    val firstDayOfWeek: Int,
    /** From the start of the day it was written, soonest first. */
    val events: List<WidgetEvent>,
    /** `yyyy-MM-dd` of every day this month and next with at least one event. */
    val busyDays: Set<String>,
    /** Set when a source filter is hiding some calendars: "Neutrino and Google". */
    val filterSummary: String? = null,
) {
    val context: CalendarContext
        get() = CalendarContext(
            runCatching { java.time.ZoneId.of(timeZone) }.getOrDefault(java.time.ZoneId.systemDefault()),
            java.time.DayOfWeek.of(firstDayOfWeek.coerceIn(1, 7)),
        )

    /** Not over at `at`, soonest first. */
    fun upcoming(at: Instant): List<WidgetEvent> {
        val today = context.day(at).toString()
        return events.filter { if (it.allDay) it.lastDay >= today else it.end > at }
    }

    /** The next timed event, or the one under way. */
    fun next(at: Instant): WidgetEvent? = upcoming(at).firstOrNull { !it.allDay }

    /** Everything on the day of `at`, all-day first, then by start. */
    fun eventsOn(day: LocalDate): List<WidgetEvent> {
        val key = day.toString()
        return events.filter { it.firstDay <= key && key <= it.lastDay }
            .sortedWith(compareBy<WidgetEvent>({ !it.allDay }, { it.startEpochSeconds }))
    }

    companion object {
        const val FILE_NAME = "widget-snapshot.json"
        /** How far ahead `events` reaches. */
        const val HORIZON_DAYS = 14L

        fun signedOut(now: Instant = Instant.now()) = WidgetSnapshot(
            false, now.epochSecond, java.time.ZoneId.systemDefault().id, 7, emptyList(), emptySet(), null,
        )

        /**
         * The snapshot for `occurrences`, fetched from the first of this month: what the calendars and
         * the source filter show, from the start of today for [HORIZON_DAYS], plus the busy days of this
         * month and next. Each event carries its calendar's colour.
         */
        fun build(
            occurrences: List<EventOccurrence>,
            now: Instant,
            context: CalendarContext,
            filter: com.neutrino.calendar.core.model.EventFilter,
            limit: Int = 100,
        ): WidgetSnapshot {
            val shown = occurrences.filter { filter.shows(it.event) }
            val today = context.day(now)
            val horizon = today.plusDays(HORIZON_DAYS)
            val events = UpNext.upcoming(shown, context.startOf(today), context)
                .map { it to EventDayRange.of(it, context) }
                .filter { it.second.first < horizon }
                .take(limit)
                .map { (o, range) ->
                    WidgetEvent(
                        EventLink(o).string, o.event.title, o.start.epochSecond, o.end.epochSecond, o.event.allDay,
                        range.first.toString(), range.last.toString(), o.event.location?.takeIf { it.isNotEmpty() },
                        filter.rules.colorOf(o.event),
                    )
                }
            val firstOfMonth = today.withDayOfMonth(1)
            val afterNextMonth = firstOfMonth.plusMonths(2)
            val busy = mutableSetOf<String>()
            for (o in shown) {
                val range = EventDayRange.of(o, context)
                var day = maxOf(range.first, firstOfMonth)
                while (day <= range.last && day < afterNextMonth) {
                    busy += day.toString()
                    day = day.plusDays(1)
                }
            }
            return WidgetSnapshot(true, now.epochSecond, context.zone.id, context.firstDayOfWeek.value, events, busy, filter.focus.summary)
        }
    }
}
