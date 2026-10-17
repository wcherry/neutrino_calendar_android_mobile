package com.neutrino.calendar.core.logic

import com.neutrino.calendar.core.model.CalendarContext
import com.neutrino.calendar.core.model.CalendarEvent
import com.neutrino.calendar.core.model.CalendarTask
import com.neutrino.calendar.core.model.CreateTaskRequest
import com.neutrino.calendar.core.model.EventFilter
import com.neutrino.calendar.core.model.ServerDate
import com.neutrino.calendar.core.model.listPhrase
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * One occurrence of an event, as a string that survives outside the app: a widget tap, a
 * notification, a launcher shortcut. `<event id>@<start, in seconds since 1970>`, so a repeating
 * event opens on the occurrence that was tapped, not on its first. The iOS app uses the same form.
 */
data class EventLink(val eventId: String, val start: Instant) {
    constructor(occurrence: EventOccurrence) : this(occurrence.event.id, occurrence.start)

    val string: String get() = "$eventId@${start.epochSecond}"

    /**
     * The occurrence of `event` this link names: the event's own length, from the linked start. An
     * exception is its own occurrence of `series`, the event it is an exception of.
     */
    fun occurrenceOf(event: CalendarEvent, series: CalendarEvent? = null): EventOccurrence {
        if (series != null && event.recurringEventId == series.id) {
            return EventOccurrence(event, event.start, event.end, series, event.originalStart ?: event.start)
        }
        if (!event.isRecurring) return EventOccurrence(event, event.start, event.end)
        return EventOccurrence(event, start, start.plus(Duration.between(event.start, event.end)), event, start)
    }

    companion object {
        fun parse(string: String): EventLink? {
            val at = string.lastIndexOf('@')
            if (at <= 0) return null
            val seconds = string.substring(at + 1).toDoubleOrNull() ?: return null
            return EventLink(string.substring(0, at), Instant.ofEpochMilli((seconds * 1000).toLong()))
        }
    }
}

/** What is coming up: what the next-event notification, the widgets and "What's next" show. */
object UpNext {

    /** The occurrences not over yet, soonest first. An all-day one is over at the end of its last day. */
    fun upcoming(occurrences: List<EventOccurrence>, now: Instant, context: CalendarContext): List<EventOccurrence> {
        val today = context.day(now)
        fun sortStart(o: EventOccurrence): Instant =
            if (o.event.allDay) context.startOf(EventDayRange.of(o, context).first) else o.start
        return occurrences
            .filter { if (it.event.allDay) EventDayRange.of(it, context).last >= today else it.end > now }
            .sortedWith { a, b ->
                val c = sortStart(a).compareTo(sortStart(b))
                if (c != 0) c else titleOrder(a.event.title, b.event.title)
            }
    }

    /** Each event once, at its first upcoming occurrence. */
    fun firstOfEach(upcoming: List<EventOccurrence>): List<EventOccurrence> = upcoming.distinctBy { it.event.id }

    /** The next timed event the filter shows, including one under way. All-day events are the day, not a thing in it. */
    fun next(upcoming: List<EventOccurrence>, filter: EventFilter): EventOccurrence? =
        upcoming.firstOrNull { !it.event.allDay && filter.shows(it.event) }

    /** "Stand-up is on now, until 10:30 AM." or "Next is Stand-up, tomorrow at 9:00 AM, at Room 4." */
    fun sentence(occurrence: EventOccurrence?, now: Instant, context: CalendarContext, days: Int, locale: Locale = Locale.getDefault()): String {
        occurrence ?: return "There's nothing on your calendar in the next $days days."
        val title = occurrence.event.title
        val place = occurrence.event.location?.trim()?.takeIf { it.isNotEmpty() }?.let { ", at $it" } ?: ""
        if (occurrence.start <= now) {
            return "$title is on now, until ${EventFormatting.shortTime(occurrence.end, context.zone, locale)}$place."
        }
        return "Next is $title, ${at(occurrence.start, now, context, locale)}$place."
    }

    /** "today", "tomorrow", "on Thursday" within the week, or "on Oct 3". */
    fun `when`(day: LocalDate, now: Instant, context: CalendarContext, locale: Locale = Locale.getDefault()): String {
        val days = ChronoUnit.DAYS.between(context.day(now), day)
        return when {
            days == 0L -> "today"
            days == 1L -> "tomorrow"
            days in 2..6 -> "on ${day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)}"
            else -> "on ${DateTimeFormatter.ofPattern(EventFormatting.bestPattern("MMM d", locale), locale).format(day)}"
        }
    }

    /** "tomorrow at 9:00 AM". */
    fun at(instant: Instant, now: Instant, context: CalendarContext, locale: Locale = Locale.getDefault()): String =
        "${`when`(context.day(instant), now, context, locale)} at ${EventFormatting.shortTime(instant, context.zone, locale)}"

    /** "Added Dentist, tomorrow at 9:00 AM." or "Added Holiday, on Oct 3, all day." */
    fun added(occurrence: EventOccurrence, now: Instant, context: CalendarContext, locale: Locale = Locale.getDefault()): String {
        val title = occurrence.event.title
        if (occurrence.event.allDay) {
            return "Added $title, ${`when`(EventDayRange.of(occurrence, context).first, now, context, locale)}, all day."
        }
        return "Added $title, ${at(occurrence.start, now, context, locale)}."
    }

    /** "I'll remind you about Call the bank today at 5:00 PM." */
    fun willRemind(title: String, due: Instant, now: Instant, context: CalendarContext, locale: Locale = Locale.getDefault()) =
        "I'll remind you about $title ${at(due, now, context, locale)}."
}

/** Tasks in words: Add Task from a shortcut, and what's due today. */
object TaskSpeech {

    /**
     * The create request for what was said, through Smart Add. A due date given separately wins over
     * one in the words: midnight means the day, any other time means that time. `null` when no title is left.
     */
    fun request(text: String, due: Instant?, now: Instant, context: CalendarContext): CreateTaskRequest? {
        val parsed = SmartAdd.parse(text, SmartAdd.Context.current(now, context))
        val title = parsed.title.trim()
        if (title.isEmpty()) return null
        var request = SmartAdd.request(parsed, context).copy(title = title)
        if (due != null) {
            val time = context.dateTime(due).toLocalTime()
            request = if (time.hour == 0 && time.minute == 0) {
                request.copy(dueDate = CalendarTask.dueDateValue(context.day(due)), dueHasTime = null)
            } else {
                request.copy(dueDate = ServerDate.format(due), dueHasTime = true)
            }
        }
        return request
    }

    /** "Added Buy milk, due tomorrow at 5:00 PM." or "Added Buy milk." */
    fun added(task: CalendarTask, now: Instant, context: CalendarContext, locale: Locale = Locale.getDefault()): String {
        val due = dueText(task, now, context, locale) ?: return "Added ${task.title}."
        return "Added ${task.title}, due $due."
    }

    /** The open tasks due by the end of today: overdue ones oldest first, then today's, timed ones by time. */
    fun dueToday(tasks: List<CalendarTask>, now: Instant, context: CalendarContext): Pair<List<CalendarTask>, List<CalendarTask>> {
        val today = context.day(now)
        val open = tasks.withIndex().mapNotNull { (i, t) ->
            val day = t.dueDay(context)
            if (t.done || day == null || day > today) null else Triple(i, t, day)
        }
        val overdue = open.filter { it.third < today }.sortedWith(compareBy({ it.third }, { it.first })).map { it.second }
        val dueToday = open.filter { it.third == today }.sortedWith { a, b ->
            val x = if (a.second.dueHasTime) a.second.dueDate else null
            val y = if (b.second.dueHasTime) b.second.dueDate else null
            when {
                x != null && y != null -> if (x != y) x.compareTo(y) else a.first.compareTo(b.first)
                x != null -> -1
                y != null -> 1
                else -> a.first.compareTo(b.first)
            }
        }.map { it.second }
        return overdue to dueToday
    }

    /** "You have 2 tasks due today: Call mom at 3:00 PM and Buy milk. 1 is overdue: Taxes." */
    fun sentence(overdue: List<CalendarTask>, today: List<CalendarTask>, context: CalendarContext, limit: Int = 5, locale: Locale = Locale.getDefault()): String {
        if (overdue.isEmpty() && today.isEmpty()) return "Nothing's due today."
        fun names(tasks: List<CalendarTask>): String {
            val shown = tasks.take(limit).map { t ->
                val due = t.dueDate
                if (t.dueHasTime && due != null) "${t.title} at ${EventFormatting.shortTime(due, context.zone, locale)}" else t.title
            }.toMutableList()
            if (tasks.size > limit) shown += "${tasks.size - limit} more"
            return listPhrase(shown)
        }
        val parts = mutableListOf<String>()
        if (today.isEmpty()) parts += "Nothing's due today."
        else parts += "You have ${if (today.size == 1) "1 task" else "${today.size} tasks"} due today: ${names(today)}."
        if (overdue.isNotEmpty()) parts += "${overdue.size} ${if (overdue.size == 1) "is" else "are"} overdue: ${names(overdue)}."
        return parts.joinToString(" ")
    }

    /** "tomorrow", "tomorrow at 5:00 PM", "on Oct 3"; `null` with no due date. */
    fun dueText(task: CalendarTask, now: Instant, context: CalendarContext, locale: Locale = Locale.getDefault()): String? {
        val due = task.dueDate ?: return null
        val day = task.dueDay(context) ?: return null
        val w = UpNext.`when`(day, now, context, locale)
        return if (task.dueHasTime) "$w at ${EventFormatting.shortTime(due, context.zone, locale)}" else w
    }

    /** "Fri, Oct 2" or "Fri, Oct 2, 3:00 PM", for a Smart Add preview chip. */
    fun formatSmartDate(value: SmartDate, locale: Locale = Locale.getDefault()): String {
        val day = runCatching { LocalDate.parse(value.date) }.getOrNull() ?: return value.date
        val datePart = "${day.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)}, " +
            DateTimeFormatter.ofPattern(EventFormatting.bestPattern("MMM d", locale), locale).format(day)
        val time = value.time ?: return datePart
        val hm = time.split(":").mapNotNull { it.toIntOrNull() }
        if (hm.size != 2) return datePart
        return "$datePart, ${java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale).format(LocalTime.of(hm[0], hm[1]))}"
    }
}
