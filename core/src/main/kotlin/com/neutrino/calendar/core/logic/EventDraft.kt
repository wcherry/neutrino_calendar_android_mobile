package com.neutrino.calendar.core.logic

import com.neutrino.calendar.core.model.CalendarContext
import com.neutrino.calendar.core.model.CalendarEvent
import com.neutrino.calendar.core.model.JsonBody
import com.neutrino.calendar.core.model.RecurrenceScope
import com.neutrino.calendar.core.model.RepeatOption
import com.neutrino.calendar.core.model.ServerDate
import com.neutrino.calendar.core.model.encodeObject
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** `CreateEventRequest` in `neutrino/src/calendar/events/dto.rs`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CreateEventRequest(
    val title: String,
    val description: String? = null,
    val startTime: String,
    val endTime: String,
    @EncodeDefault val allDay: Boolean = false,
    val location: String? = null,
    val recurrenceRule: String? = null,
    @EncodeDefault val attendees: List<String> = emptyList(),
    val timezone: String? = null,
    /** Absent, the default calendar. */
    val calendarId: String? = null,
) : JsonBody {
    override fun toJson(): JsonObject = encodeObject(serializer(), this)
}

/**
 * `UpdateEventRequest`: every field optional, and an absent one is left alone.
 *
 * The server cannot store NULL through an update, only a value, so a field being emptied is sent as
 * `""`: the app reads an empty location, note or rule as none, as the server's own readers do.
 */
@Serializable
data class UpdateEventRequest(
    val title: String? = null,
    val description: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val allDay: Boolean? = null,
    val location: String? = null,
    val recurrenceRule: String? = null,
    val attendees: List<String>? = null,
    val timezone: String? = null,
    /** Moves the event, a series with its exceptions. One occurrence can't move on its own. */
    val calendarId: String? = null,
) : JsonBody {
    val isEmpty: Boolean get() = this == UpdateEventRequest()
    override fun toJson(): JsonObject = encodeObject(serializer(), this)
}

/** "This and following": `POST /events/{id}/split`. The changes, flattened beside the occurrence's start. */
data class SplitEventRequest(val originalStartTime: String, val changes: UpdateEventRequest) : JsonBody {
    override fun toJson(): JsonObject = JsonObject(changes.toJson() + ("originalStartTime" to kotlinx.serialization.json.JsonPrimitive(originalStartTime)))
}

/**
 * The event form's state, and the requests it turns into. Kept out of the screen so what gets sent
 * is testable in a fixed zone.
 */
data class EventDraft(
    val title: String = "",
    val location: String = "",
    val notes: String = "",
    val allDay: Boolean = false,
    /** For a timed event, the instants; for an all-day one, any time on the first and last days, read in [timeZone]. */
    val start: Instant,
    val end: Instant,
    /** The zone the start and end are entered in, and stored as the event's `timezone`. */
    val timeZone: ZoneId,
    val repeatOption: RepeatOption = RepeatOption.Never,
    val attendees: List<String> = emptyList(),
    /** The calendar it goes in. Null for a new event means the default calendar. */
    val calendarId: String? = null,
) {

    // ── Repeat ──────────────────────────────────────────────────────────────────────────────────

    /** How the rule's end date is written for this event. */
    val repeatContext: RepeatRule.Context get() = RepeatRule.Context(allDay, timeZone)

    /** The rule as the form edits it: null when the event doesn't repeat, or its rule is one the form can't show. */
    val repeatRule: RepeatRule? get() = repeatOption.rule?.let { RepeatRule.parse(it, repeatContext) }

    fun withRepeatRule(rule: RepeatRule?): EventDraft = copy(repeatOption = RepeatOption.of(rule?.rule(repeatContext)))

    /**
     * Changes the zone and keeps the clock times, as the phone's own calendar does: picking New York
     * for a 10:00 event means 10:00 in New York, not the same instant shown as 13:00.
     */
    fun withTimeZone(zone: ZoneId): EventDraft {
        if (zone.id == timeZone.id) return this
        fun moved(i: Instant) = i.atZone(timeZone).toLocalDateTime().atZone(zone).toInstant()
        return reencodingRepeatEnd { copy(start = moved(start), end = moved(end), timeZone = zone) }
    }

    /** Switches between all-day and timed, keeping a repeat's end on the same day. */
    fun withAllDay(allDay: Boolean): EventDraft = reencodingRepeatEnd { copy(allDay = allDay) }

    /** An end date is written as an instant that depends on all-day and the zone, so a change to either writes it again. */
    private fun reencodingRepeatEnd(change: EventDraft.() -> EventDraft): EventDraft {
        val rule = repeatRule
        if (rule == null || rule.end !is RepeatRule.End.On) return change()
        return change().withRepeatRule(rule)
    }

    /** Moving the start keeps the event's length rather than inverting it, as on the web. */
    fun withStart(newStart: Instant): EventDraft {
        val length = Duration.between(start, end).let { if (it.isNegative) Duration.ZERO else it }
        return copy(start = newStart, end = newStart.plus(length))
    }

    // ── Validation ──────────────────────────────────────────────────────────────────────────────

    val trimmedTitle: String get() = title.trim()

    /** Why the form can't be saved, or `null` when it can. */
    val problem: String?
        get() {
            if (trimmedTitle.isEmpty()) return "An event needs a title."
            if (if (allDay) day(end) < day(start) else end < start) return "The event ends before it starts."
            val e = repeatRule?.end
            if (e is RepeatRule.End.On && e.day < day(start)) return "The event stops repeating before it starts."
            return null
        }

    /** Adds a guest by email, ignoring case for duplicates; `null` when it wasn't added. */
    fun addingAttendee(raw: String): EventDraft? {
        val email = raw.trim()
        if (!looksLikeEmail(email) || attendees.any { it.equals(email, ignoreCase = true) }) return null
        return copy(attendees = attendees + email)
    }

    // ── Wire form ───────────────────────────────────────────────────────────────────────────────

    /** The times as the web writes them: all-day is dates, `T00:00:00Z` to `T23:59:59Z`; timed is two instants. */
    val wireTimes: Pair<String, String>
        get() = if (allDay) "${day(start)}T00:00:00Z" to "${day(end)}T23:59:59Z"
        else ServerDate.format(start) to ServerDate.format(end)

    /** A timed event carries the zone it was entered in; an all-day one none, as on the web. */
    val wireTimeZone: String? get() = if (allDay) null else timeZone.id

    fun createRequest(): CreateEventRequest {
        val (s, e) = wireTimes
        return CreateEventRequest(
            title = trimmedTitle,
            description = notes.trim().ifEmpty { null },
            startTime = s,
            endTime = e,
            allDay = allDay,
            location = location.trim().ifEmpty { null },
            recurrenceRule = repeatOption.rule,
            attendees = attendees,
            timezone = wireTimeZone,
            calendarId = calendarId,
        )
    }

    /** Only what changed since `original`. The three time fields travel together. */
    fun updateRequest(original: EventDraft): UpdateEventRequest {
        val timesChanged = wireTimes != original.wireTimes || allDay != original.allDay
        return UpdateEventRequest(
            title = trimmedTitle.takeIf { it != original.trimmedTitle },
            description = notes.trim().takeIf { it != original.notes.trim() },
            location = location.trim().takeIf { it != original.location.trim() },
            startTime = wireTimes.first.takeIf { timesChanged },
            endTime = wireTimes.second.takeIf { timesChanged },
            allDay = allDay.takeIf { timesChanged },
            timezone = (wireTimeZone ?: "").takeIf { wireTimeZone != original.wireTimeZone },
            recurrenceRule = (repeatOption.rule ?: "").takeIf { repeatOption != original.repeatOption },
            attendees = attendees.takeIf { it != original.attendees },
            calendarId = calendarId?.takeIf { it != original.calendarId },
        )
    }

    /** The day of `instant` in the draft's zone. */
    fun day(instant: Instant): LocalDate = instant.atZone(timeZone).toLocalDate()

    companion object {
        /** An hour, the length a new event gets, as on the web. */
        val DEFAULT_LENGTH: Duration = Duration.ofHours(1)

        /** A new event on `day`: at the top of the next hour when `day` is today, at 09:00 otherwise. */
        fun newOn(day: LocalDate, now: Instant, context: CalendarContext): EventDraft {
            val start = if (context.day(now) == day) {
                context.dateTime(now).truncatedTo(ChronoUnit.HOURS).plusHours(1).let(context::instant)
            } else {
                context.instant(day, LocalTime.of(9, 0))
            }
            return EventDraft(start = start, end = start.plus(DEFAULT_LENGTH), timeZone = context.zone)
        }

        /** A new event from a shortcut or the assistant: an hour long without an end, the one day when all-day. */
        fun quick(title: String, start: Instant, end: Instant?, allDay: Boolean, location: String?, context: CalendarContext) =
            EventDraft(
                title = title, location = location ?: "", allDay = allDay, start = start,
                end = end ?: if (allDay) start else start.plus(DEFAULT_LENGTH), timeZone = context.zone,
            )

        /**
         * The form for editing `event`: the whole series for a repeating one, so its own start is
         * shown, not the tapped occurrence's.
         */
        fun editing(event: CalendarEvent, context: CalendarContext): EventDraft {
            val zone = event.timezone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: context.zone
            val (start, end) = if (event.allDay) {
                // An all-day event is dates: its first and last day, as local midnights in its zone.
                val range = EventDayRange.of(event.start, event.end, true, context.copy(zone = zone))
                range.first.atStartOfDay(zone).toInstant() to range.last.atStartOfDay(zone).toInstant()
            } else {
                event.start to event.end
            }
            return EventDraft(
                title = event.title,
                location = event.location ?: "",
                notes = event.description ?: "",
                allDay = event.allDay,
                start = start,
                end = end,
                timeZone = zone,
                repeatOption = RepeatOption.of(event.recurrenceRule),
                attendees = event.attendees,
                calendarId = event.calendarId,
            )
        }

        /**
         * The form for editing the occurrences of `occurrence` that `scope` names: the series from its
         * own start for ALL; the occurrence as shown for THIS; the series as it would run from this
         * occurrence, its COUNT less the occurrences before, for FOLLOWING.
         */
        fun editing(occurrence: EventOccurrence, scope: RecurrenceScope?, context: CalendarContext): EventDraft {
            val series = occurrence.series
            val effective = effectiveScope(occurrence, scope)
            if (series == null || effective == null) return editing(occurrence.event, context)
            return when (effective) {
                RecurrenceScope.ALL -> editing(series, context)
                RecurrenceScope.THIS -> editing(occurrence.event.with(occurrence.start, occurrence.end, occurrence.event.recurrenceRule), context)
                RecurrenceScope.FOLLOWING -> {
                    val at = occurrence.originalStart ?: occurrence.start
                    val rule = RecurrenceExpander.ruleFrom(series, at, context)
                    editing(series.with(at, at.plus(Duration.between(series.start, series.end)), rule), context)
                }
            }
        }

        /** "This and following" from a series' first occurrence is the whole series. Null for a one-off. */
        fun effectiveScope(occurrence: EventOccurrence, scope: RecurrenceScope?): RecurrenceScope? {
            val series = occurrence.series ?: return null
            scope ?: return null
            if (scope == RecurrenceScope.FOLLOWING && (occurrence.originalStart ?: occurrence.start) <= series.start) return RecurrenceScope.ALL
            return scope
        }

        /** The server takes any string; this only stops a typo like "ada" becoming a guest. */
        fun looksLikeEmail(s: String): Boolean {
            val parts = s.split("@")
            return parts.size == 2 && parts[0].isNotEmpty() && parts[1].contains(".") && !s.contains(" ")
        }
    }
}

/**
 * `event` as `draft` would leave it: what an edit made offline shows until the server has it. Times
 * are read back from the wire form, so an all-day event is its dates, as a loaded one is.
 */
fun CalendarEvent.editedTo(draft: EventDraft): CalendarEvent {
    val (s, e) = draft.wireTimes
    return copy(
        title = draft.trimmedTitle,
        description = draft.notes.takeIf { it.isNotBlank() },
        start = ServerDate.parse(s) ?: draft.start,
        end = ServerDate.parse(e) ?: draft.end,
        allDay = draft.allDay,
        location = draft.location.takeIf { it.isNotBlank() },
        recurrenceRule = draft.repeatOption.rule,
        attendees = draft.attendees,
        timezone = draft.wireTimeZone,
        calendarId = draft.calendarId ?: calendarId,
        createdAt = null,
        updatedAt = null,
        recurringEventId = null,
        originalStart = null,
        cancelled = false,
    )
}
