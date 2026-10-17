package com.neutrino.calendar.core.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * One row of `GET /api/v1/calendar/events`, as `EventResponse` in
 * `neutrino/src/calendar/events/dto.rs` serialises it.
 *
 * Times arrive as UTC instants (`2026-09-24T17:00:00Z`). An all-day event is the exception in
 * meaning if not in shape: the web writes it as `<date>T00:00:00Z` to `<last date>T23:59:59Z` and
 * reads the date part back without converting it, so an all-day event is a *date*, the same date in
 * every time zone. [com.neutrino.calendar.core.logic.EventDayRange] is where that is honoured.
 */
data class CalendarEvent(
    val id: String,
    val title: String,
    val description: String? = null,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean = false,
    val location: String? = null,
    /**
     * An RFC 5545 RRULE body such as `FREQ=WEEKLY;BYDAY=MO,WE`, stored as written. Expanded on the
     * device by `RecurrenceExpander`; the server never expands it.
     */
    val recurrenceRule: String? = null,
    val attendees: List<String> = emptyList(),
    val source: EventSource = EventSource.Local,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    /** The IANA zone a timed event was created in. Display only: times are instants. */
    val timezone: String? = null,
    /** Set on an exception: the repeating event whose occurrence this row stands in for. */
    val recurringEventId: String? = null,
    /** Set on an exception: the occurrence's start in its series, before any edit. Its key. */
    val originalStart: Instant? = null,
    /** An exception that deletes its occurrence. */
    val cancelled: Boolean = false,
    /** The calendar it is in. Null from a server older than calendars. */
    val calendarId: String? = null,
) {
    /** Whether this is a series: an event with a rule. An exception is never one. */
    val isRecurring: Boolean get() = !recurrenceRule.isNullOrEmpty()

    /** This event with other times and rule: an occurrence of it, or the series as it runs from one. */
    fun with(start: Instant, end: Instant, recurrenceRule: String?) = copy(start = start, end = end, recurrenceRule = recurrenceRule)

    companion object {
        fun from(wire: EventWire): CalendarEvent? {
            val start = ServerDate.parse(wire.startTime) ?: return null
            val end = ServerDate.parse(wire.endTime) ?: return null
            return CalendarEvent(
                id = wire.id,
                title = wire.title,
                description = wire.description,
                start = start,
                end = end,
                allDay = wire.allDay ?: false,
                location = wire.location,
                recurrenceRule = wire.recurrenceRule,
                attendees = wire.attendees ?: emptyList(),
                source = EventSource.of(wire.source ?: "local"),
                createdAt = ServerDate.parse(wire.createdAt),
                updatedAt = ServerDate.parse(wire.updatedAt),
                timezone = wire.timezone,
                recurringEventId = wire.recurringEventId,
                originalStart = ServerDate.parse(wire.originalStartTime),
                cancelled = wire.cancelled ?: false,
                calendarId = wire.calendarId,
            )
        }
    }
}

/** The wire shape; every field a reader can do without is optional, so a server change can't empty the calendar. */
@Serializable
data class EventWire(
    val id: String,
    val title: String,
    val description: String? = null,
    val startTime: String,
    val endTime: String,
    val allDay: Boolean? = null,
    val location: String? = null,
    val recurrenceRule: String? = null,
    val attendees: List<String>? = null,
    val source: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val timezone: String? = null,
    val recurringEventId: String? = null,
    val originalStartTime: String? = null,
    val cancelled: Boolean? = null,
    val calendarId: String? = null,
)

@Serializable
data class ListEventsWire(val events: List<EventWire> = emptyList())

/** `EventChangesResponse` in `neutrino/src/calendar/events/dto.rs`. */
@Serializable
data class EventChangesWire(
    val events: List<EventWire> = emptyList(),
    val deletedIds: List<String> = emptyList(),
    /** Pass as `since` next time. */
    val cursor: String,
    /** The cursor is older than the server keeps deletions for: drop everything and load afresh. */
    val fullResyncRequired: Boolean = false,
)

/**
 * Where an event came from. The server writes `local` for events made in Neutrino and the
 * provider's name for synced ones. Two are never stored: a holiday, computed on the device, and a
 * task drawn on the calendar. The web uses the same names (`HOLIDAY_SOURCE`, `TASK_SOURCE`).
 */
sealed class EventSource(val raw: String) {
    object Local : EventSource("local")
    object Google : EventSource("google")
    object Outlook : EventSource("outlook")
    object Apple : EventSource("apple")
    object Holidays : EventSource("holidays")
    object Task : EventSource("task")
    class Other(raw: String) : EventSource(raw) {
        override fun equals(other: Any?) = other is Other && other.raw == raw
        override fun hashCode() = raw.hashCode()
    }

    /** Computed on the device, with no server row behind it: nothing to fetch, edit, remind about or attach to. */
    val isComputed: Boolean get() = this == Holidays || this == Task

    /** The badge shown on a synced event. `null` for Neutrino's own events, which need none. */
    val badge: String?
        get() = when (this) {
            Local, Holidays, Task -> null
            Google -> "Google"
            Outlook -> "Outlook"
            Apple -> "iCloud"
            is Other -> raw.replaceFirstChar { it.uppercase() }
        }

    override fun toString() = raw

    companion object {
        fun of(raw: String): EventSource = when (raw.lowercase()) {
            "local" -> Local
            "google" -> Google
            "outlook" -> Outlook
            "apple" -> Apple
            "holidays" -> Holidays
            "task" -> Task
            else -> Other(raw)
        }
    }
}
