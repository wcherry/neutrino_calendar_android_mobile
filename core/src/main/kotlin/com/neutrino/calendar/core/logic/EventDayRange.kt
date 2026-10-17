package com.neutrino.calendar.core.logic

import com.neutrino.calendar.core.model.CalendarContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The first and last local day an occurrence covers, both inclusive. A port of the web's
 * `eventDayRange` (`calendarHelpers.ts`), pinned by the same generated vectors as the expansion.
 *
 * - **An all-day event is a date, not an instant.** Its days are the UTC date parts of its start
 *   and end, so an all-day event on the 1st is on the 1st in every zone.
 * - **An end exactly at midnight belongs to the day before.** That is what an `.ics` all-day
 *   `DTEND` means, being exclusive, and a timed event ending at 00:00 has nothing on that day.
 */
data class EventDayRange(val first: LocalDate, val last: LocalDate) {

    fun contains(day: LocalDate): Boolean = day >= first && day <= last

    /** Every day from first to last. */
    val days: List<LocalDate>
        get() = generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.toList()

    companion object {
        fun of(start: Instant, end: Instant, allDay: Boolean, context: CalendarContext): EventDayRange {
            val endsAtMidnight = if (allDay) {
                end.atZone(ZoneOffset.UTC).toLocalTime().let { it.hour == 0 && it.minute == 0 && it.second == 0 }
            } else {
                context.dateTime(end).toLocalTime().let { it.hour == 0 && it.minute == 0 && it.second == 0 }
            }
            val first = if (allDay) utcDate(start) else context.day(start)
            var last = if (allDay) utcDate(end) else context.day(end)
            if (last > first && endsAtMidnight) last = last.minusDays(1)
            if (last < first) last = first
            return EventDayRange(first, last)
        }

        fun of(occurrence: EventOccurrence, context: CalendarContext) =
            of(occurrence.start, occurrence.end, occurrence.event.allDay, context)

        /** The web's `dateOnly(iso)`: an instant's UTC date. */
        fun utcDate(instant: Instant): LocalDate = instant.atZone(ZoneOffset.UTC).toLocalDate()
    }
}
