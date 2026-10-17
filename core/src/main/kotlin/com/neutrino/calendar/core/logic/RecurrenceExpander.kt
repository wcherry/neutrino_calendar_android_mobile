package com.neutrino.calendar.core.logic

import com.neutrino.calendar.core.model.CalendarContext
import com.neutrino.calendar.core.model.CalendarEvent
import com.neutrino.calendar.core.model.CalendarTask
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * One appearance of an event on the calendar. A plain event has one; a recurring one has one per
 * repetition, each sharing the event's id, so the id alone cannot key a list row.
 *
 * `event` is what is shown: the series, or, for an occurrence changed on its own, its exception. An
 * edit or delete of a repeating event goes by [series] and [originalStart], never by `event.id`,
 * which for an exception is the exception's.
 */
data class EventOccurrence(
    val event: CalendarEvent,
    val start: Instant,
    val end: Instant,
    /** The repeating event this is an occurrence of; null for a one-off event. */
    val series: CalendarEvent? = null,
    /** Where in its series the occurrence falls, before any edit of it; null for a one-off. */
    val originalStart: Instant? = null,
    /** The task this stands for, when it is a task drawn on the calendar ([TaskOccurrences]). */
    val task: CalendarTask? = null,
) {
    val id: String get() = "${series?.id ?: event.id}@${(originalStart ?: start).epochSecond}"

    /** Whether this is one of several: an edit or delete asks which of them it is for. */
    val isRepeating: Boolean get() = series != null
}

/**
 * Turns events and their RRULEs into the occurrences inside a range.
 *
 * **A port of the web's `expandRecurringEvents`, not an RFC 5545 implementation**, exactly as the
 * iOS app's `RecurrenceExpander.swift` is. The clients show one calendar, so they must agree on
 * where a repeating event falls, and that means agreeing with `calendarHelpers.ts` where it departs
 * from the RFC. Each departure is kept on purpose and pinned by `recurrence_vectors.json`, produced
 * by running the web's own code:
 *
 * - Repetitions step in the *viewer's* zone, keeping the wall-clock time across DST.
 * - MONTHLY and YEARLY overflow instead of clamping: Jan 31 → Mar 3, Feb 29 → Mar 1.
 * - BYDAY applies to WEEKLY only, and reaches forward from each step's date, never back.
 * - A date-only UNTIL (`UNTIL=20260930`) is unreadable to the web and so is ignored.
 * - A rule that does not parse shows the event once, as written.
 * - Expansion gives up after 1000 steps from the first occurrence.
 * - An occurrence belongs to the range when it *starts* inside it.
 */
object RecurrenceExpander {

    /** The web's `MAX_OCCURRENCES`, which bounds FREQ steps rather than occurrences. */
    const val MAX_STEPS = 1000

    /**
     * How far an exception's original start and an occurrence may be apart and still be the same
     * occurrence: viewers whose zones change for DST on different dates put one occurrence up to an
     * hour apart. The web's `EXCEPTION_MATCH_WINDOW_MS`.
     */
    val MATCH_WINDOW: Duration = Duration.ofHours(2)

    fun expand(events: List<CalendarEvent>, from: Instant, to: Instant, context: CalendarContext): List<EventOccurrence> {
        val result = mutableListOf<EventOccurrence>()
        val exceptions = events.filter { it.recurringEventId != null }.groupBy { it.recurringEventId!! }
        for (event in events) {
            if (event.recurringEventId != null) continue
            val raw = event.recurrenceRule
            val rule = if (raw.isNullOrEmpty()) null else Rule.parse(raw)
            if (rule == null) {
                result += EventOccurrence(event, event.start, event.end)
                continue
            }
            val generated = occurrences(event, rule, from, to, context)
            result += applying(exceptions[event.id].orEmpty(), event, generated, from, to)
        }
        return result
    }

    /**
     * A series' occurrences in the range with its exceptions applied: each occurrence replaced by the
     * exception nearest its start within [MATCH_WINDOW], dropped when that one is cancelled, and shown
     * at the exception's own time when that is in the range. An exception moved here from an
     * occurrence outside the range is added. The web's `applyExceptions`.
     */
    private fun applying(
        exceptions: List<CalendarEvent>,
        series: CalendarEvent,
        generated: List<EventOccurrence>,
        from: Instant,
        to: Instant,
    ): List<EventOccurrence> {
        if (exceptions.isEmpty()) return generated
        fun inRange(i: Instant) = i >= from && i <= to
        fun shown(e: CalendarEvent) = EventOccurrence(e, e.start, e.end, series, e.originalStart ?: e.start)
        val used = mutableSetOf<String>()
        val result = mutableListOf<EventOccurrence>()

        for (occurrence in generated) {
            val match = exceptions
                .filter { it.id !in used && it.originalStart != null }
                .map { it to abs(Duration.between(occurrence.start, it.originalStart!!).toMillis()) }
                .filter { it.second <= MATCH_WINDOW.toMillis() }
                .minByOrNull { it.second }?.first
            if (match == null) {
                result += occurrence
                continue
            }
            used += match.id
            if (!match.cancelled && inRange(match.start)) result += shown(match)
        }

        for (exception in exceptions) {
            if (exception.id in used || exception.cancelled) continue
            val original = exception.originalStart ?: continue
            if (inRange(original)) continue
            if (inRange(exception.start)) result += shown(exception)
        }
        return result
    }

    /**
     * The rule for a series that starts at the occurrence `occurrenceStart` of `series`: the same
     * rule, with its COUNT, if any, less the occurrences before that one. The web's `ruleFromOccurrence`.
     */
    fun ruleFrom(series: CalendarEvent, occurrenceStart: Instant, context: CalendarContext): String? {
        val rule = series.recurrenceRule
        if (rule.isNullOrEmpty()) return null
        val parts = rule.split(";").toMutableList()
        val index = parts.indexOfFirst { it.uppercase().startsWith("COUNT=") }
        if (index < 0) return rule
        val count = parts[index].substring("COUNT=".length).toIntOrNull() ?: return rule
        val plain = series.with(series.start, series.end, rule)
        val before = expand(listOf(plain), series.start, occurrenceStart.minusSeconds(1), context).size
        parts[index] = parts[index].substring(0, "COUNT=".length) + maxOf(1, count - before)
        return parts.joinToString(";")
    }

    private fun occurrences(event: CalendarEvent, rule: Rule, from: Instant, to: Instant, context: CalendarContext): List<EventOccurrence> {
        val dtStart = event.start
        val duration = Duration.between(event.start, event.end)
        val result = mutableListOf<EventOccurrence>()
        var current = dtStart
        var steps = 0
        // COUNT counts occurrences, as RFC 5545 has it — those before `from` too.
        var emitted = 0

        while (current <= to && steps < MAX_STEPS) {
            if (rule.count != null && emitted >= rule.count) break
            if (rule.until != null && current > rule.until) break

            val weekday = context.jsDay(current)
            // In the order they fall in the step, so COUNT stops at the right one.
            val targets = (if (rule.freq == Frequency.WEEKLY) (rule.byDay ?: listOf(weekday)) else listOf(weekday))
                .sortedBy { (it - weekday + 7) % 7 }
            for (target in targets) {
                val occurrence = JsDate.shifting(current, JsDate.Unit.DAY, ((target - weekday) + 7) % 7, context)
                if (occurrence < dtStart) continue
                if (rule.until != null && occurrence > rule.until) continue
                if (rule.count != null && emitted >= rule.count) break
                emitted++
                if (occurrence > to) continue
                if (occurrence >= from) {
                    result += EventOccurrence(event, occurrence, occurrence.plus(duration), event, occurrence)
                }
            }

            // An INTERVAL the web's `parseInt` cannot read turns its date invalid, which ends the
            // loop after this first pass.
            val interval = rule.interval ?: break
            current = JsDate.shifting(current, rule.freq.unit, rule.freq.multiplier * interval, context)
            steps++
        }
        return result
    }

    enum class Frequency(val wire: String, val unit: JsDate.Unit, val multiplier: Int) {
        DAILY("DAILY", JsDate.Unit.DAY, 1),
        WEEKLY("WEEKLY", JsDate.Unit.DAY, 7),
        MONTHLY("MONTHLY", JsDate.Unit.MONTH, 1),
        YEARLY("YEARLY", JsDate.Unit.YEAR, 1),
    }

    /** The web's `parseRRule`, including what it tolerates and what it silently drops. */
    data class Rule(
        val freq: Frequency,
        /** `null` when INTERVAL is present but not a number. */
        val interval: Int?,
        /** `null` when absent *or* unreadable: the web compares against NaN, which never stops it. */
        val count: Int?,
        val until: Instant?,
        /** Days 0 (Sunday) to 6 (Saturday); `null` when BYDAY is absent. */
        val byDay: List<Int>?,
    ) {
        companion object {
            private val DAY_NUMBERS = mapOf("SU" to 0, "MO" to 1, "TU" to 2, "WE" to 3, "TH" to 4, "FR" to 5, "SA" to 6)

            fun parse(rrule: String): Rule? {
                var parts = mutableMapOf<String, String>()
                for (part in rrule.split(";")) {
                    // `const [k, v] = part.split('=')`: the value is the text up to a second '=', and
                    // a part with no '=' at all is skipped.
                    val pieces = part.split("=")
                    if (pieces.size < 2 || pieces[0].isEmpty()) continue
                    parts[pieces[0].uppercase()] = pieces[1]
                }
                val freq = Frequency.entries.firstOrNull { it.wire == parts["FREQ"] } ?: return null
                // The web tests each value for truthiness first, so `INTERVAL=` is absent, not unreadable.
                parts = parts.filterValues { it.isNotEmpty() }.toMutableMap()
                return Rule(
                    freq = freq,
                    interval = parts["INTERVAL"]?.let(::jsParseInt) ?: if (parts.containsKey("INTERVAL")) null else 1,
                    count = parts["COUNT"]?.let(::jsParseInt),
                    until = parts["UNTIL"]?.let(::parseUntil),
                    byDay = parts["BYDAY"]?.let { value ->
                        value.split(",").mapNotNull { token -> DAY_NUMBERS[token.filter { it !in "+-0123456789" }] }
                    },
                )
            }

            /** JavaScript's `parseInt(s, 10)`: whitespace, an optional sign, then digits. `null` stands in for NaN. */
            fun jsParseInt(s: String): Int? {
                var rest = s.trimStart()
                var sign = 1
                if (rest.startsWith("+") || rest.startsWith("-")) {
                    if (rest[0] == '-') sign = -1
                    rest = rest.substring(1)
                }
                val digits = rest.takeWhile { it in '0'..'9' }
                return digits.toIntOrNull()?.let { sign * it }
            }

            /**
             * The web strips every `T` and `Z`, then reads the result only when it is fourteen digits
             * — a UTC date-time. Anything else, a bare date included, never stops the expansion.
             */
            fun parseUntil(value: String): Instant? {
                val digits = value.filter { it != 'T' && it != 'Z' }
                if (digits.length != 14 || !digits.all { it in '0'..'9' }) return null
                fun f(a: Int, b: Int) = digits.substring(a, b).toInt()
                return runCatching {
                    LocalDateTime.of(f(0, 4), f(4, 6), f(6, 8), f(8, 10), f(10, 12), f(12, 14)).toInstant(ZoneOffset.UTC)
                }.getOrNull()
            }
        }
    }
}

/** JavaScript `Date` arithmetic, which the expansion has to reproduce. */
object JsDate {
    enum class Unit { DAY, MONTH, YEAR }

    /**
     * `setDate(getDate() + n)`, `setMonth(…)` and `setFullYear(…)`: change one wall-clock field and
     * let the rest normalise — Jan 31 + 1 month overflows to Mar 3 where `plusMonths` would clamp.
     * The wall-clock time is kept, through DST gaps and overlaps, as JavaScript keeps it.
     */
    fun shifting(instant: Instant, unit: Unit, value: Int, context: CalendarContext): Instant {
        val local = context.dateTime(instant)
        var year = local.year
        var month = local.monthValue
        var day = local.dayOfMonth
        when (unit) {
            Unit.DAY -> day += value
            Unit.MONTH -> month += value
            Unit.YEAR -> year += value
        }
        val totalMonths = year * 12L + (month - 1)
        val normalisedYear = Math.floorDiv(totalMonths, 12L).toInt()
        val normalisedMonth = Math.floorMod(totalMonths, 12L).toInt() + 1
        val date = LocalDate.of(normalisedYear, normalisedMonth, 1).plusDays(day - 1L)
        return context.instant(LocalDateTime.of(date, local.toLocalTime()))
    }
}
