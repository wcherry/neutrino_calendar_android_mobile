package com.neutrino.calendar.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate

/**
 * One row of `GET /api/v1/calendar/reminders` (`ReminderResponse` in
 * `neutrino/src/calendar/reminders/dto.rs`).
 *
 * A reminder belongs to at most one thing, an event or a task, and only at creation: the server's
 * update has no link fields, so a link can't be added, changed or removed later.
 */
data class Reminder(
    val id: String,
    val title: String,
    val due: Instant,
    val completed: Boolean = false,
    /** An RRULE body. Completing a reminder with one moves it to its next occurrence on the server. */
    val recurrenceRule: String? = null,
    val linkedEventId: String? = null,
    val linkedTaskId: String? = null,
) {
    fun isOverdue(now: Instant = Instant.now()): Boolean = !completed && due < now

    companion object {
        fun from(wire: ReminderWire): Reminder? = ServerDate.parse(wire.dueTime)?.let { due ->
            Reminder(
                id = wire.id, title = wire.title, due = due, completed = wire.completed ?: false,
                // An empty rule is one cleared before the server learnt to store NULL: no repeat.
                recurrenceRule = wire.recurrenceRule?.takeIf { it.isNotEmpty() },
                linkedEventId = wire.linkedEventId, linkedTaskId = wire.linkedTaskId,
            )
        }
    }
}

@Serializable
data class ReminderWire(
    val id: String,
    val title: String,
    val dueTime: String,
    val completed: Boolean? = null,
    val recurrenceRule: String? = null,
    val linkedEventId: String? = null,
    val linkedTaskId: String? = null,
)

@Serializable
data class ListRemindersWire(val reminders: List<ReminderWire> = emptyList())

@Serializable
data class CreateReminderRequest(
    val title: String,
    val dueTime: String,
    val recurrenceRule: String? = null,
    val linkedEventId: String? = null,
    val linkedTaskId: String? = null,
) : JsonBody {
    override fun toJson(): JsonObject = encodeObject(serializer(), this)
}

/** Only the fields being changed are sent; `null` leaves a field alone. */
@Serializable
data class UpdateReminderRequest(
    val title: String? = null,
    val dueTime: String? = null,
    val completed: Boolean? = null,
    /** An empty string removes the rule. */
    val recurrenceRule: String? = null,
    /** The zone the server steps a recurrence in when this completes it. */
    val timezone: String? = null,
) : JsonBody {
    val isEmpty: Boolean get() = this == UpdateReminderRequest()
    override fun toJson(): JsonObject = encodeObject(serializer(), this)
}

/**
 * The web's reminder filter (`REMINDER_RANGES` in `calendarHelpers.ts`).
 *
 * A range is an upper bound only: a reminder that came due yesterday is still something to deal
 * with today, so an overdue reminder is in every range. A range ends at the end of its last day in
 * the device's zone, counting today as the first day.
 */
enum class ReminderRange(val label: String, private val days: Int?) {
    TODAY("Today", 1), THREE_DAYS("3 days", 3), SEVEN_DAYS("7 days", 7), ALL("All", null);

    /** The last instant the range includes, or `null` for [ALL]: `setHours(23, 59, 59, 999)` on the last day. */
    fun end(now: Instant, context: CalendarContext): Instant? {
        val days = days ?: return null
        val dayAfter: LocalDate = context.day(now).plusDays(days.toLong())
        return context.startOf(dayAfter).minusMillis(1)
    }

    fun contains(reminder: Reminder, now: Instant, context: CalendarContext): Boolean {
        val end = end(now, context) ?: return true
        return reminder.due <= end
    }
}

/**
 * The repeat choices offered for a reminder or an event, each an RRULE the web and the server both
 * read. A rule written elsewhere that isn't one of these is kept as [Custom] and saved back
 * untouched, so opening and saving never rewrites a rule.
 */
sealed class RepeatOption(val rule: String?) {
    object Never : RepeatOption(null)
    object Daily : RepeatOption("FREQ=DAILY")
    object Weekdays : RepeatOption("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR")
    object Weekly : RepeatOption("FREQ=WEEKLY")
    object Monthly : RepeatOption("FREQ=MONTHLY")
    object Yearly : RepeatOption("FREQ=YEARLY")
    class Custom(rule: String) : RepeatOption(rule) {
        override fun equals(other: Any?) = other is Custom && other.rule == rule
        override fun hashCode() = rule.hashCode()
    }

    val id: String get() = rule ?: "never"

    val label: String
        get() = when (this) {
            Never -> "Never"
            Daily -> "Every Day"
            Weekdays -> "Every Weekday"
            Weekly -> "Every Week"
            Monthly -> "Every Month"
            Yearly -> "Every Year"
            is Custom -> com.neutrino.calendar.core.logic.EventFormatting.recurrenceSummary(rule!!)
        }

    override fun toString() = id

    companion object {
        val STANDARD: List<RepeatOption> = listOf(Never, Daily, Weekdays, Weekly, Monthly, Yearly)

        fun of(rule: String?): RepeatOption = when (rule) {
            null, "" -> Never
            "FREQ=DAILY" -> Daily
            "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR" -> Weekdays
            "FREQ=WEEKLY" -> Weekly
            "FREQ=MONTHLY" -> Monthly
            "FREQ=YEARLY" -> Yearly
            else -> Custom(rule)
        }
    }
}

/** "10 minutes before" and friends, for a reminder on an event: the web's `REMINDER_PRESETS`. */
data class ReminderPreset(val label: String, val minutes: Int) {
    companion object {
        val ALL = listOf(
            ReminderPreset("At time of event", 0),
            ReminderPreset("5 minutes before", 5),
            ReminderPreset("10 minutes before", 10),
            ReminderPreset("15 minutes before", 15),
            ReminderPreset("30 minutes before", 30),
            ReminderPreset("1 hour before", 60),
            ReminderPreset("2 hours before", 120),
            ReminderPreset("1 day before", 1440),
            ReminderPreset("2 days before", 2880),
            ReminderPreset("1 week before", 10080),
        )
    }
}

/**
 * Which occurrences of a repeating event or reminder an edit or delete is for, as Google and Outlook
 * ask. See `neutrino/agent_docs/recurrence-exceptions.md`. For a reminder `FOLLOWING` does what
 * `ALL` does (a repeating reminder is one row at its next occurrence); it is offered anyway.
 */
enum class RecurrenceScope {
    THIS, FOLLOWING, ALL;

    fun label(kind: Kind): String = when (this) {
        THIS -> "This ${kind.noun}"
        FOLLOWING -> "This and Following ${kind.noun}s"
        ALL -> "All ${kind.noun}s"
    }

    enum class Kind(val noun: String) { EVENT("Event"), REMINDER("Reminder") }
}

/** "Delete this reminder" for a repeating one: `POST /reminders/{id}/skip`. */
@Serializable
data class SkipReminderRequest(val timezone: String) : JsonBody {
    override fun toJson(): JsonObject = encodeObject(serializer(), this)
}

@Serializable
data class SkipReminderWire(
    /** The reminder at its next occurrence, or null once its rule ran out and it was deleted. */
    val series: ReminderWire? = null,
)

/** "Edit this reminder" for a repeating one: `POST /reminders/{id}/occurrence`. */
@Serializable
data class ReminderOccurrenceRequest(val title: String? = null, val dueTime: String? = null, val timezone: String) : JsonBody {
    override fun toJson(): JsonObject = encodeObject(serializer(), this)
}

@Serializable
data class ReminderOccurrenceWire(
    /** The one-off reminder the occurrence became. */
    val reminder: ReminderWire,
    /** The repeating reminder at its next occurrence, or null once its rule ran out. */
    val series: ReminderWire? = null,
)
