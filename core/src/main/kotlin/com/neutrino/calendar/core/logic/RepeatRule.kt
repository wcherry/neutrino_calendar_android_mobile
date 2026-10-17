package com.neutrino.calendar.core.logic

import com.neutrino.calendar.core.model.RepeatOption
import com.neutrino.calendar.core.model.rootFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * An event's RRULE as the event form edits it: how often (FREQ and INTERVAL — "every 3 days") and
 * when it stops (never, on a date, or after a number of occurrences).
 *
 * **A port of the web's `repeatRule.ts`** (and of the iOS `RepeatRule.swift`), held to the same
 * `repeat_rule_vectors.json`: each client expands the rules the others write, so they must write
 * the same strings.
 *
 *     FREQ=WEEKLY[;INTERVAL=n][;BYDAY=…][;any other part, as it was][;COUNT=n | ;UNTIL=…]
 *
 * - INTERVAL only when it isn't 1, so the plain choices stay the strings [RepeatOption] has.
 * - UNTIL is always a UTC date-time: every expander ignores a bare date. For a timed event it is the
 *   last second of the chosen day in the event's zone; for an all-day event it is `T235959Z`.
 * - A rule this can't represent faithfully doesn't parse, and the form leaves it exactly as it was.
 */
data class RepeatRule(
    val frequency: Frequency,
    val interval: Int = 1,
    /** BYDAY as written, e.g. `MO,TU,WE,TH,FR`. */
    val byDay: String? = null,
    val end: End = End.Never,
    /** Every other part (BYMONTHDAY, WKST…), as written, in order. */
    val extra: List<String> = emptyList(),
) {
    enum class Frequency(val wire: String, private val unitName: String) {
        DAILY("DAILY", "day"), WEEKLY("WEEKLY", "week"), MONTHLY("MONTHLY", "month"), YEARLY("YEARLY", "year");

        /** "day" or "days", for "Every 3 days". */
        fun unit(interval: Int) = if (interval == 1) unitName else unitName + "s"
    }

    sealed class End {
        object Never : End()
        /** The last day an occurrence may fall on, inclusive. */
        data class On(val day: LocalDate) : End()
        data class After(val count: Int) : End()
    }

    /** Whether the event is all-day, and the zone its times are entered in. */
    data class Context(val allDay: Boolean, val zone: ZoneId)

    fun rule(context: Context): String {
        val parts = mutableListOf("FREQ=${frequency.wire}")
        if (interval > 1) parts += "INTERVAL=$interval"
        if (!byDay.isNullOrEmpty()) parts += "BYDAY=$byDay"
        parts += extra
        when (end) {
            End.Never -> Unit
            is End.After -> parts += "COUNT=${end.count}"
            is End.On -> parts += "UNTIL=${untilValue(end.day, context)}"
        }
        return parts.joinToString(";")
    }

    /**
     * The [RepeatOption] a rule is shown under: its FREQ, or Every Weekday. A weekly rule on other
     * days ("every Mon and Thu", from Smart Add) shows as Weekly and keeps its days.
     */
    val preset: RepeatOption
        get() = when (frequency) {
            Frequency.DAILY -> RepeatOption.Daily
            Frequency.WEEKLY -> if (byDay == WEEKDAYS_BY_DAY) RepeatOption.Weekdays else RepeatOption.Weekly
            Frequency.MONTHLY -> RepeatOption.Monthly
            Frequency.YEARLY -> RepeatOption.Yearly
        }

    companion object {
        const val WEEKDAYS_BY_DAY = "MO,TU,WE,TH,FR"
        const val MAX_NUMBER = 999

        fun parse(rule: String, context: Context): RepeatRule? {
            var frequency: Frequency? = null
            var interval = 1
            var byDay: String? = null
            var count: Int? = null
            var until: LocalDate? = null
            val extra = mutableListOf<String>()
            for (part in rule.split(";")) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq <= 0) return null
                val key = part.substring(0, eq).uppercase()
                val value = part.substring(eq + 1)
                when (key) {
                    "FREQ" -> frequency = Frequency.entries.firstOrNull { it.wire == value.uppercase() } ?: return null
                    "INTERVAL" -> interval = positive(value) ?: return null
                    "COUNT" -> count = positive(value) ?: return null
                    "UNTIL" -> until = untilDay(value, context) ?: return null
                    "BYDAY" -> byDay = value.uppercase()
                    else -> extra += part
                }
            }
            if (frequency == null || (count != null && until != null)) return null
            val end = when {
                count != null -> End.After(count)
                until != null -> End.On(until)
                else -> End.Never
            }
            return RepeatRule(frequency, interval, byDay, end, extra)
        }

        /**
         * `rule` after picking `preset`. Picking the choice it already shows changes nothing; picking
         * another keeps the interval and the end, and drops the days and any other parts.
         */
        fun with(preset: RepeatOption, rule: RepeatRule?): RepeatRule? {
            val frequency = when (preset) {
                RepeatOption.Never, is RepeatOption.Custom -> return null
                RepeatOption.Daily -> Frequency.DAILY
                RepeatOption.Weekdays, RepeatOption.Weekly -> Frequency.WEEKLY
                RepeatOption.Monthly -> Frequency.MONTHLY
                RepeatOption.Yearly -> Frequency.YEARLY
            }
            if (rule != null && rule.preset == preset) return rule
            return RepeatRule(
                frequency, rule?.interval ?: 1,
                if (preset == RepeatOption.Weekdays) WEEKDAYS_BY_DAY else null,
                rule?.end ?: End.Never,
            )
        }

        /** A sensible end date to offer for "On Date": a month or a year after `start`. */
        fun defaultEndDay(start: LocalDate, frequency: Frequency): LocalDate =
            if (frequency == Frequency.MONTHLY || frequency == Frequency.YEARLY) start.plusYears(1) else start.plusMonths(1)

        private fun positive(s: String): Int? {
            if (s.isEmpty() || !s.all { it in '0'..'9' }) return null
            return s.toIntOrNull()?.takeIf { it >= 1 }
        }

        /** An UNTIL value as the day it ends on, or null when it isn't one. */
        private fun untilDay(value: String, context: Context): LocalDate? {
            val v = value.uppercase()
            val digits = v.filter { it in '0'..'9' }
            val shapeOk = (v.length == 8 && digits.length == 8) ||
                (digits.length == 14 && v.length in 15..16 && v[8] == 'T' && (v.length == 15 || v.endsWith("Z")))
            if (!shapeOk) return null
            fun num(from: Int, len: Int) = digits.substring(from, from + len).toInt()
            val day = runCatching { LocalDate.of(num(0, 4), num(4, 2), num(6, 2)) }.getOrNull() ?: return null
            // A bare date, a floating date-time, or an all-day event: the day as written.
            if (!v.endsWith("Z") || context.allDay) return day
            val instant = runCatching {
                LocalDateTime.of(day.year, day.monthValue, day.dayOfMonth, num(8, 2), num(10, 2), num(12, 2)).toInstant(ZoneOffset.UTC)
            }.getOrNull() ?: return null
            return instant.atZone(context.zone).toLocalDate()
        }

        private fun untilValue(day: LocalDate, context: Context): String {
            val compact = "%04d%02d%02d".rootFormat(day.year, day.monthValue, day.dayOfMonth)
            if (context.allDay) return compact + "T235959Z"
            // The last second of the day in the event's zone.
            val last = day.plusDays(1).atStartOfDay(context.zone).toInstant().minusSeconds(1).atZone(ZoneOffset.UTC)
            return "%04d%02d%02dT%02d%02d%02dZ".rootFormat(last.year, last.monthValue, last.dayOfMonth, last.hour, last.minute, last.second)
        }
    }
}
