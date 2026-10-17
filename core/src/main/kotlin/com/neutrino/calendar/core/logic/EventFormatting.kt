package com.neutrino.calendar.core.logic

import com.neutrino.calendar.core.model.CalendarContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** The strings the event screens show, kept out of the screens so they can be tested in a fixed zone and locale. */
object EventFormatting {

    fun time(zone: ZoneId, locale: Locale = Locale.getDefault()): DateTimeFormatter =
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)

    private fun monthDayTime(zone: ZoneId, locale: Locale) =
        DateTimeFormatter.ofPattern(bestPattern("MMM d", locale) + ", " + timePattern(locale), locale).withZone(zone)

    /**
     * "All day", or "9:00 AM – 10:00 AM" in `zone`. A timed event that ends on a later day names the
     * end day too, since the time alone would read as a negative duration.
     */
    fun timeSummary(occurrence: EventOccurrence, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        if (occurrence.event.allDay) return "All day"
        val context = CalendarContext(zone)
        val start = time(zone, locale).format(occurrence.start)
        // `EventDayRange` already treats an end at midnight as the day before.
        return if (EventDayRange.of(occurrence, context).last > context.day(occurrence.start)) {
            "$start – ${monthDayTime(zone, locale).format(occurrence.end)}"
        } else {
            "$start – ${time(zone, locale).format(occurrence.end)}"
        }
    }

    /** "Thursday, September 24, 2026", or a range for an event covering several days. */
    fun dateSummary(occurrence: EventOccurrence, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val range = EventDayRange.of(occurrence, CalendarContext(zone))
        if (range.first == range.last) return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(range.first)
        val short = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        return "${short.format(range.first)} – ${short.format(range.last)}"
    }

    /**
     * The event's times in the zone it was created in, when that is not the device's zone:
     * "12:00 PM – 1:00 PM Eastern Time". `null` for all-day events, events with no zone, and when the
     * zones agree at the event's start.
     */
    fun originalTimeZoneSummary(occurrence: EventOccurrence, deviceZone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
        if (occurrence.event.allDay) return null
        val id = occurrence.event.timezone ?: return null
        val zone = runCatching { ZoneId.of(id) }.getOrNull() ?: return null
        if (zone.rules.getOffset(occurrence.start) == deviceZone.rules.getOffset(occurrence.start)) return null
        return "${timeSummary(occurrence, zone, locale)} ${zone.getDisplayName(TextStyle.FULL, locale).ifEmpty { id }}"
    }

    /**
     * "Repeats weekly on Mon, Tue, Wed, Thu, Fri" for the rules the web editor writes. A rule the
     * expander cannot read is shown as written: a label that hides a rule is worse than a technical one.
     */
    fun recurrenceSummary(rrule: String, locale: Locale = Locale.getDefault()): String {
        val rule = RecurrenceExpander.Rule.parse(rrule) ?: return "Repeats ($rrule)"
        val interval = rule.interval ?: 1
        val unit = when (rule.freq) {
            RecurrenceExpander.Frequency.DAILY -> if (interval == 1) "daily" else "every $interval days"
            RecurrenceExpander.Frequency.WEEKLY -> if (interval == 1) "weekly" else "every $interval weeks"
            RecurrenceExpander.Frequency.MONTHLY -> if (interval == 1) "monthly" else "every $interval months"
            RecurrenceExpander.Frequency.YEARLY -> if (interval == 1) "yearly" else "every $interval years"
        }
        var text = "Repeats $unit"
        val days = rule.byDay
        if (rule.freq == RecurrenceExpander.Frequency.WEEKLY && !days.isNullOrEmpty()) {
            text += " on " + days.joinToString(", ") { dayOfWeekFromJsDay(it).getDisplayName(TextStyle.SHORT, locale) }
        }
        rule.count?.let { text += ", $it times" }
        rule.until?.let { text += ", until " + DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneId.systemDefault()).format(it) }
        return text
    }

    /** "Sep 20 – 26" or, across a month end, "Sep 27 – Oct 3". */
    fun span(first: java.time.LocalDate, last: java.time.LocalDate, locale: Locale = Locale.getDefault()): String {
        val md = DateTimeFormatter.ofPattern(bestPattern("MMM d", locale), locale)
        val end = if (first.year == last.year && first.month == last.month) last.dayOfMonth.toString() else md.format(last)
        return "${md.format(first)} – $end"
    }

    fun dayOfWeekFromJsDay(n: Int): DayOfWeek = DayOfWeek.of(if (n == 0) 7 else n)

    /** "MMM d" or "d MMM", as the locale orders a month and day. */
    fun bestPattern(skeleton: String, locale: Locale): String {
        if (skeleton != "MMM d") return skeleton
        val sample = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(java.time.LocalDate.of(2026, 1, 23))
        return if (sample.indexOf("23") < sample.indexOf(sample.firstOrNull { it.isLetter() } ?: '2')) "d MMM" else "MMM d"
    }

    private fun timePattern(locale: Locale): String {
        val sample = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(java.time.LocalTime.of(15, 0))
        return if (sample.contains("15")) "HH:mm" else "h:mm a"
    }

    /** The time in `zone` as the device shows it: "3:00 PM". */
    fun shortTime(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        time(zone, locale).format(instant)
}
