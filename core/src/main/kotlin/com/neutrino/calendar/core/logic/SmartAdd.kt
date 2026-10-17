package com.neutrino.calendar.core.logic

import com.neutrino.calendar.core.model.CalendarContext
import com.neutrino.calendar.core.model.CreateTaskRequest
import com.neutrino.calendar.core.model.ServerDate
import com.neutrino.calendar.core.model.rootFormat
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** A calendar date (`YYYY-MM-DD`) in the user's zone, and a 24-hour `HH:MM` or none. */
@Serializable
data class SmartDate(val date: String, val time: String? = null)

data class SmartAddResult(
    val title: String = "",
    val due: SmartDate? = null,
    val start: SmartDate? = null,
    val priority: Int? = null,
    /** Lowercase, without `#`, in the order typed, no repeats. */
    val tags: List<String> = emptyList(),
    /** An RRULE body the server's `calendar::recurrence` steps, e.g. `FREQ=WEEKLY;BYDAY=MO,TH`. */
    val recurrenceRule: String? = null,
    val repeatAfterCompletion: Boolean = false,
    val estimateMinutes: Int? = null,
    val location: String? = null,
    val note: String? = null,
) {
    /** Whether anything besides the title was found — what decides if a preview is worth showing. */
    val hasDetails: Boolean
        get() = due != null || start != null || priority != null || tags.isNotEmpty() || recurrenceRule != null ||
            estimateMinutes != null || location != null || note != null
}

/**
 * RTM-style Smart Add: one typed line becomes a task and its fields.
 *
 *     Buy milk ^tomorrow 5pm !1 #errands *weekly =15min ~today @Safeway // semi-skimmed
 *
 * `^date` due · `~date` start · `!1`–`!3` priority · `#word` tag · `*repeat` repeat ·
 * `=estimate` time estimate · `@place` location · `// text` note (rest of the line).
 *
 * A date phrase with no `^` is also the due date — "call mom tomorrow 3pm" — as long as it reads
 * unambiguously as a date. Text in double quotes is never parsed.
 *
 * A port of the web's `smartAdd.ts` and the iOS `SmartAdd.swift`, function for function, held to
 * the same table, `smart_add_vectors.json`. Change the grammar in all three.
 */
object SmartAdd {

    /** What "today" and "now" are in the user's zone. Passed in so parsing is a pure function. */
    @Serializable
    data class Context(
        /** `YYYY-MM-DD` */
        val today: String,
        /** `HH:MM` */
        val now: String,
    ) {
        companion object {
            fun current(instant: Instant = Instant.now(), context: CalendarContext = CalendarContext.system()): Context {
                val t = context.dateTime(instant)
                return Context(t.toLocalDate().toString(), "%02d:%02d".rootFormat(t.hour, t.minute))
            }
        }
    }

    private class Word(val raw: String, val norm: String, val quoted: Boolean, var used: Boolean = false)

    fun parse(text: String, context: Context = Context.current()): SmartAddResult {
        var title: String
        var due: SmartDate? = null
        var start: SmartDate? = null
        var priority: Int? = null
        val tags = mutableListOf<String>()
        var rule: String? = null
        var after = false
        var estimate: Int? = null
        var location: String? = null
        var note: String? = null

        // "// note" runs to the end of the line; a URL's "://" is not one.
        var body = text
        Regex("(^|\\s)//").find(text)?.let { marker ->
            val slash = text.indexOf("//", marker.range.first)
            if (slash >= 0) {
                note = text.substring(slash + 2).trim().ifEmpty { null }
                body = text.substring(0, slash)
            }
        }

        val words = tokenize(body)

        // The phrase a prefixed token reads: the rest of its own word, then the words after it.
        fun phrase(i: Int): Pair<List<String>, Int> {
            val first = words[i].norm.drop(1)
            val rest = words.drop(i + 1).map { if (it.used || it.quoted) "\u0000" else it.norm }
            return if (first.isEmpty()) rest to 1 else (listOf(first) + rest) to 0
        }
        fun consume(i: Int, n: Int, offset: Int) {
            var k = i
            while (k < i + n + offset && k < words.size) {
                words[k].used = true
                k++
            }
        }

        // Pass 1: the prefixed tokens.
        for (i in words.indices) {
            val word = words[i]
            if (word.used || word.quoted) continue
            val sigil = word.norm.firstOrNull()
            val priorityMatch = Regex("^!([123])$").find(word.norm)
            if (priorityMatch != null) {
                priority = priorityMatch.groupValues[1].toInt()
                word.used = true
            } else if (sigil == '#' && Regex("^#[\\p{L}_][\\p{L}\\p{N}_\\-/.]*$").containsMatchIn(word.norm)) {
                val tag = word.norm.drop(1)
                if (tag !in tags) tags += tag
                word.used = true
            } else if (sigil == '@' && word.norm.length > 1 && !word.raw.drop(1).contains('@')) {
                location = word.raw.drop(1).replace(Regex("[.,;:!?)]+$"), "")
                word.used = true
            } else if (sigil == '^' || sigil == '~') {
                val (w, offset) = phrase(i)
                matchDate(w, 0, context, bare = false)?.let { match ->
                    if (sigil == '^') due = match.value else start = match.value
                    consume(i, match.n, offset)
                    if (offset == 0) word.used = true
                }
            } else if (sigil == '*') {
                val (w, offset) = phrase(i)
                matchRepeat(w, 0)?.let { match ->
                    rule = match.rule
                    after = match.after
                    consume(i, match.n, offset)
                    word.used = true
                }
            } else if (sigil == '=') {
                val (w, offset) = phrase(i)
                matchEstimate(w, 0)?.let { (minutes, n) ->
                    estimate = minutes
                    consume(i, n, offset)
                    word.used = true
                }
            }
        }

        // Pass 2: an un-prefixed date phrase, when there was no ^ — the first that reads as a date.
        if (due == null) {
            for (i in words.indices) {
                if (words[i].used || words[i].quoted) continue
                // A run of free words: a date phrase must not reach across a token or into quotes.
                var end = i
                while (end < words.size && !words[end].used && !words[end].quoted) end++
                val w = words.subList(i, end).map { it.norm }
                val match = matchDate(w, 0, context, bare = true)
                if (match != null) {
                    due = match.value
                    consume(i, match.n, 0)
                    break
                }
            }
        }

        // A repeat with no due date starts on its first occurrence.
        val r = rule
        if (r != null && due == null) {
            val today = Ymd.parse(context.today)
            var first = today
            Regex("BYDAY=([A-Z,]+)").find(r)?.let { m ->
                first = m.groupValues[1].split(",").mapNotNull { RRULE_DAYS.indexOf(it).takeIf { i -> i >= 0 } }
                    .map { onOrAfter(today, it) }.minOrNull() ?: today
            }
            due = SmartDate(first.formatted, null)
        }

        title = words.filter { !it.used }.joinToString(" ") { it.raw }.trim()
        return SmartAddResult(title, due, start, priority, tags, rule, after, estimate, location, note)
    }

    // ── Single fields ───────────────────────────────────────────────────────────────────────────

    /** "every other week", "after 2 weeks", "weekly" — with or without the `*`. */
    fun parseRepeat(text: String): Pair<String, Boolean>? {
        var trimmed = text.trim()
        if (trimmed.startsWith("*")) trimmed = trimmed.drop(1)
        val w = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }.map(::normalise)
        if (w.isEmpty()) return null
        val match = matchRepeat(w, 0) ?: return null
        return if (match.n == w.size) match.rule to match.after else null
    }

    /** "1h 30m", "45m", "2h". */
    fun formatEstimate(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return listOf(if (h > 0) "${h}h" else "", if (m > 0 || h == 0) "${m}m" else "").filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** A rule in the words Smart Add reads it from: "every 2 weeks", "every Mon, Thu", "after 6 weeks". */
    fun describeRepeat(rule: String, after: Boolean): String {
        val parts = mutableMapOf<String, String>()
        for (part in rule.split(";")) {
            val kv = part.split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotEmpty() && kv[1].isNotEmpty()) parts[kv[0]] = kv[1]
        }
        val unitNames = mapOf("DAILY" to "day", "WEEKLY" to "week", "MONTHLY" to "month", "YEARLY" to "year")
        val unit = parts["FREQ"]?.let { unitNames[it] } ?: return rule
        val interval = parts["INTERVAL"]?.toIntOrNull()?.takeIf { it != 0 } ?: 1
        var text: String
        val byDay = parts["BYDAY"]
        if (byDay != null) {
            val days = byDay.split(",").mapNotNull { d -> RRULE_DAYS.indexOf(d.filter { it.isLetter() && it.isUpperCase() }).takeIf { it >= 0 } }
            val names = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            val list = when (days) {
                listOf(1, 2, 3, 4, 5) -> "weekday"
                listOf(6, 0) -> "weekend"
                else -> days.joinToString(", ") { names[it] }
            }
            text = "every ${if (interval == 2) "other " else ""}$list"
        } else {
            val every = when {
                interval == 1 -> unit
                interval == 2 && !after -> "other $unit"
                else -> "$interval ${unit}s"
            }
            text = "${if (after) "after" else "every"} ${if (after && interval == 1) "a $unit" else every}"
        }
        parts["COUNT"]?.let { text += " for $it times" }
        return text
    }

    // ── To the wire ─────────────────────────────────────────────────────────────────────────────

    /**
     * A parsed date as the server stores it: a date-only value is `<day>T00:00:00Z` (read back in
     * UTC, so it names the same day everywhere), a timed one is the instant in `context`'s zone.
     */
    fun wire(value: SmartDate, context: CalendarContext): Pair<String, Boolean> {
        val time = value.time ?: return "${value.date}T00:00:00Z" to false
        val day = runCatching { LocalDate.parse(value.date) }.getOrNull() ?: return "${value.date}T00:00:00Z" to false
        val hm = time.split(":").mapNotNull { it.toIntOrNull() }
        val instant = context.instant(day, LocalTime.of(hm[0], hm[1]))
        return ServerDate.format(instant) to true
    }

    /** The create request for a parsed line. Only what was typed is sent. */
    fun request(result: SmartAddResult, context: CalendarContext): CreateTaskRequest {
        val due = result.due?.let { wire(it, context) }
        val start = result.start?.let { wire(it, context) }
        return CreateTaskRequest(
            title = result.title,
            notes = result.note,
            dueDate = due?.first,
            dueHasTime = if (due?.second == true) true else null,
            startDate = start?.first,
            startHasTime = if (start?.second == true) true else null,
            priority = result.priority,
            tags = result.tags.ifEmpty { null },
            recurrenceRule = result.recurrenceRule,
            repeatAfterCompletion = if (result.recurrenceRule != null && result.repeatAfterCompletion) true else null,
            estimateMinutes = result.estimateMinutes,
            location = result.location,
        )
    }

    // ── Calendar arithmetic on plain dates ──────────────────────────────────────────────────────

    /** A date with no zone, in days since 1970-01-01: the web does the same through `Date.UTC`. */
    private data class Ymd(val y: Int, val m: Int, val d: Int) : Comparable<Ymd> {
        val days: Long get() = LocalDate.of(y, m, d).toEpochDay()
        /** 0 = Sunday … 6 = Saturday. */
        val weekday: Int get() = LocalDate.of(y, m, d).dayOfWeek.value % 7
        val formatted: String get() = "%04d-%02d-%02d".rootFormat(y, m, d)
        override fun compareTo(other: Ymd) = compareValuesBy(this, other, { it.y }, { it.m }, { it.d })

        companion object {
            fun parse(s: String): Ymd {
                val p = s.split("-").map { it.toInt() }
                return Ymd(p[0], p[1], p[2])
            }
            fun ofDays(days: Long): Ymd = LocalDate.ofEpochDay(days).let { Ymd(it.year, it.monthValue, it.dayOfMonth) }
        }
    }

    private fun daysInMonth(y: Int, m: Int): Int = when (m) {
        2 -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private fun addDays(x: Ymd, n: Int) = Ymd.ofDays(x.days + n)

    /** Months clamp to the end of a shorter month: Jan 31 + 1 month is Feb 28. */
    private fun addMonths(x: Ymd, n: Int): Ymd {
        val total = x.y * 12 + (x.m - 1) + n
        val y = Math.floorDiv(total, 12)
        val m = total - y * 12 + 1
        return Ymd(y, m, minOf(x.d, daysInMonth(y, m)))
    }

    private fun isValidDate(y: Int, m: Int, d: Int) = m in 1..12 && d >= 1 && d <= daysInMonth(y, m)

    /** The first `weekday` on or after `from`. */
    private fun onOrAfter(from: Ymd, weekday: Int) = addDays(from, (weekday - from.weekday + 7) % 7)

    private fun pad(n: Int) = "%02d".rootFormat(n)

    // ── Vocabulary ──────────────────────────────────────────────────────────────────────────────

    /** Full names are unambiguous; the abbreviations are also words ("sat", "sun", "wed"). */
    private val WEEKDAYS: Map<String, Pair<Int, Boolean>> = mapOf(
        "sunday" to (0 to true), "sun" to (0 to false),
        "monday" to (1 to true), "mon" to (1 to false),
        "tuesday" to (2 to true), "tue" to (2 to false), "tues" to (2 to false),
        "wednesday" to (3 to true), "wed" to (3 to false),
        "thursday" to (4 to true), "thu" to (4 to false), "thur" to (4 to false), "thurs" to (4 to false),
        "friday" to (5 to true), "fri" to (5 to false),
        "saturday" to (6 to true), "sat" to (6 to false),
    )

    private val MONTHS = mapOf(
        "january" to 1, "jan" to 1, "february" to 2, "feb" to 2, "march" to 3, "mar" to 3, "april" to 4, "apr" to 4,
        "may" to 5, "june" to 6, "jun" to 6, "july" to 7, "jul" to 7, "august" to 8, "aug" to 8, "september" to 9,
        "sep" to 9, "sept" to 9, "october" to 10, "oct" to 10, "november" to 11, "nov" to 11, "december" to 12, "dec" to 12,
    )

    private enum class Unit { MINUTE, HOUR, DAY, WEEK, MONTH, YEAR }

    private val UNITS = mapOf(
        "min" to Unit.MINUTE, "mins" to Unit.MINUTE, "minute" to Unit.MINUTE, "minutes" to Unit.MINUTE,
        "h" to Unit.HOUR, "hr" to Unit.HOUR, "hrs" to Unit.HOUR, "hour" to Unit.HOUR, "hours" to Unit.HOUR,
        "day" to Unit.DAY, "days" to Unit.DAY, "week" to Unit.WEEK, "weeks" to Unit.WEEK, "wk" to Unit.WEEK, "wks" to Unit.WEEK,
        "month" to Unit.MONTH, "months" to Unit.MONTH, "year" to Unit.YEAR, "years" to Unit.YEAR, "yr" to Unit.YEAR, "yrs" to Unit.YEAR,
    )

    private val RRULE_DAYS = listOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")

    /** Words that may introduce an un-prefixed date and are dropped from the title with it. */
    private val CONNECTORS = setOf("on", "by", "due")

    private fun List<String>.at(i: Int): String? = if (i in indices) this[i] else null

    /** The capture groups of `pattern`'s first match in `s` (index 0 the whole match), or null. */
    private fun firstMatch(pattern: String, s: String): List<String?>? {
        val m = Regex(pattern).find(s) ?: return null
        return m.groups.map { it?.value }
    }

    // ── Date phrases ────────────────────────────────────────────────────────────────────────────

    private data class DatePart(val ymd: Ymd, val time: String? = null, val n: Int, val strong: Boolean)
    private data class TimePart(val time: String, val n: Int, val strong: Boolean)
    private data class DateMatch(val value: SmartDate, val n: Int)
    private data class RepeatMatch(val rule: String, val after: Boolean, val n: Int)

    private fun parseCount(word: String?): Int? {
        word ?: return null
        if (word == "a" || word == "an" || word == "one") return 1
        return if (Regex("^\\d{1,4}$").containsMatchIn(word)) word.toInt() else null
    }

    private fun parseOrdinal(word: String?): Int? {
        word ?: return null
        val m = firstMatch("^(\\d{1,2})(st|nd|rd|th)?$", word) ?: return null
        return m[1]?.toInt()
    }

    /** Monday of the week after `today`'s (weeks start on Monday). */
    private fun nextWeekStart(today: Ymd) = addDays(today, 7 - (today.weekday + 6) % 7)

    private fun endOfMonth(today: Ymd) = Ymd(today.y, today.m, daysInMonth(today.y, today.m))

    private fun datePart(w: List<String>, j: Int, today: Ymd, now: String): DatePart? {
        val a = w.at(j) ?: return null
        val b = w.at(j + 1)

        when (a) {
            "today", "tonight" -> return DatePart(today, n = 1, strong = true)
            "tod" -> return DatePart(today, n = 1, strong = false)
            "tomorrow", "tmrw" -> return DatePart(addDays(today, 1), n = 1, strong = true)
            "tom", "tmr" -> return DatePart(addDays(today, 1), n = 1, strong = false)
            "eom" -> return DatePart(endOfMonth(today), n = 1, strong = true)
        }
        if (a == "day" && b == "after" && w.at(j + 2) == "tomorrow") return DatePart(addDays(today, 2), n = 3, strong = true)
        if (a == "end" && b == "of") {
            val k = if (w.at(j + 2) == "the") j + 3 else j + 2
            if (w.at(k) == "month") return DatePart(endOfMonth(today), n = k - j + 1, strong = true)
        }

        if (a == "this" || a == "next") {
            val wd = b?.let { WEEKDAYS[it] }
            if (wd != null) {
                val ymd = if (a == "this") onOrAfter(today, wd.first) else addDays(nextWeekStart(today), (wd.first + 6) % 7)
                return DatePart(ymd, n = 2, strong = true)
            }
            if (a == "next") {
                if (b == "week") return DatePart(addDays(today, 7), n = 2, strong = true)
                if (b == "month") return DatePart(addMonths(today, 1), n = 2, strong = true)
                if (b == "year") return DatePart(addMonths(today, 12), n = 2, strong = true)
            }
            return null
        }

        WEEKDAYS[a]?.let { return DatePart(onOrAfter(today, it.first), n = 1, strong = it.second) }

        // "in 3 days", "in a week", "in 2 hours"; "3 days" without "in" only when prefixed.
        val hasIn = a == "in"
        val count = parseCount(if (hasIn) b else a)
        val unit = (if (hasIn) w.at(j + 2) else b)?.let { UNITS[it] }
        if (count != null && unit != null && (hasIn || a !in setOf("a", "an", "one"))) {
            val n = if (hasIn) 3 else 2
            return when (unit) {
                Unit.DAY -> DatePart(addDays(today, count), n = n, strong = hasIn)
                Unit.WEEK -> DatePart(addDays(today, 7 * count), n = n, strong = hasIn)
                Unit.MONTH -> DatePart(addMonths(today, count), n = n, strong = hasIn)
                Unit.YEAR -> DatePart(addMonths(today, 12 * count), n = n, strong = hasIn)
                Unit.HOUR, Unit.MINUTE -> {
                    val hm = now.split(":").map { it.toInt() }
                    val total = hm[0] * 60 + hm[1] + count * (if (unit == Unit.HOUR) 60 else 1)
                    val rest = total % 1440
                    DatePart(addDays(today, total / 1440), "${pad(rest / 60)}:${pad(rest % 60)}", n, hasIn)
                }
            }
        }

        // 2026-10-03
        firstMatch("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$", a)?.let { m ->
            val y = m[1]!!.toInt()
            val mo = m[2]!!.toInt()
            val d = m[3]!!.toInt()
            return if (isValidDate(y, mo, d)) DatePart(Ymd(y, mo, d), n = 1, strong = true) else null
        }

        // 10/3, 10/3/27, 10/3/2027 — month first.
        firstMatch("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{2}|\\d{4}))?$", a)?.let { m ->
            val mo = m[1]!!.toInt()
            val d = m[2]!!.toInt()
            val y = m[3]?.let { if (it.length == 2) 2000 + it.toInt() else it.toInt() }
            return resolveMonthDay(mo, d, y, today)?.let { DatePart(it, n = 1, strong = false) }
        }

        // "oct 3", "october 3rd", "oct 3 2027"
        MONTHS[a]?.let { month ->
            val d = parseOrdinal(b) ?: return null
            val year = w.at(j + 2)?.let { if (Regex("^\\d{4}$").containsMatchIn(it)) it.toInt() else null }
            return resolveMonthDay(month, d, year, today)?.let { DatePart(it, n = if (year != null) 3 else 2, strong = true) }
        }

        // "3 oct", "3rd of october", "the 15th"
        val skipThe = if (a == "the") 1 else 0
        val day = parseOrdinal(w.at(j + skipThe))
        if (day != null) {
            var k = j + skipThe + 1
            if (w.at(k) == "of") k += 1
            val m = w.at(k)?.let { MONTHS[it] }
            if (m != null) {
                val year = w.at(k + 1)?.let { if (Regex("^\\d{4}$").containsMatchIn(it)) it.toInt() else null }
                return resolveMonthDay(m, day, year, today)?.let { DatePart(it, n = k - j + 1 + (if (year != null) 1 else 0), strong = true) }
            }
            // A day of the month on its own: this month's, or next month's once it has passed.
            if (Regex("\\d(st|nd|rd|th)$").containsMatchIn(w[j + skipThe]) && day in 1..31) {
                for (i in 0 until 12) {
                    val base = addMonths(Ymd(today.y, today.m, 1), i)
                    val candidate = Ymd(base.y, base.m, day)
                    if (day <= daysInMonth(base.y, base.m) && candidate >= today) {
                        return DatePart(candidate, n = skipThe + 1, strong = false)
                    }
                }
                return null
            }
        }
        return null
    }

    /** A month and day with no year is the next one on or after today. */
    private fun resolveMonthDay(m: Int, d: Int, y: Int?, today: Ymd): Ymd? {
        if (y != null) return if (isValidDate(y, m, d)) Ymd(y, m, d) else null
        for (year in today.y..today.y + 4) {
            if (isValidDate(year, m, d) && Ymd(year, m, d) >= today) return Ymd(year, m, d)
        }
        return null
    }

    private fun timePart(w: List<String>, j: Int): TimePart? {
        val hasAt = w.at(j) == "at" || w.at(j) == "@"
        val k = if (hasAt) j + 1 else j
        val a = w.at(k) ?: return null
        val used = if (hasAt) 2 else 1

        if (a == "noon") return TimePart("12:00", used, hasAt)
        if (a == "midnight") return TimePart("00:00", used, hasAt)

        fun ampm(h: Int, mi: Int, suffix: String): String? {
            if (h !in 1..12 || mi > 59) return null
            return "${pad(h % 12 + if (suffix.startsWith("p")) 12 else 0)}:${pad(mi)}"
        }

        // 3pm, 3:30pm, 3p
        firstMatch("^(\\d{1,2})(?::(\\d{2}))?(am|pm|a|p)$", a)?.let { m ->
            return ampm(m[1]!!.toInt(), m[2]?.toInt() ?: 0, m[3]!!)?.let { TimePart(it, used, true) }
        }
        // 3 pm, 3:30 pm
        val next = w.at(k + 1)
        firstMatch("^(\\d{1,2})(?::(\\d{2}))?$", a)?.let { m ->
            if (next == "am" || next == "pm") {
                return ampm(m[1]!!.toInt(), m[2]?.toInt() ?: 0, next)?.let { TimePart(it, used + 1, true) }
            }
        }
        // 15:00
        firstMatch("^(\\d{1,2}):(\\d{2})$", a)?.let { m ->
            val h = m[1]!!.toInt()
            val mi = m[2]!!.toInt()
            return if (h <= 23 && mi <= 59) TimePart("${pad(h)}:${pad(mi)}", used, true) else null
        }
        return null
    }

    /**
     * The longest date phrase at `w[j]`: a date, a time, or both in either order. `bare` is for text
     * with no `^`, where every part must be unambiguous — unless an on/by/due introduced it.
     */
    private fun matchDate(w: List<String>, j: Int, ctx: Context, bare: Boolean): DateMatch? {
        val today = Ymd.parse(ctx.today)
        val lead = if (CONNECTORS.contains(w.at(j) ?: "")) 1 else 0
        val start = j + lead
        fun ok(strong: Boolean) = !bare || strong || lead == 1

        val candidates = mutableListOf<DateMatch>()

        val d = datePart(w, start, today, ctx.now)
        if (d != null && ok(d.strong)) {
            val t = if (d.time == null) timePart(w, start + d.n) else null
            val withTime = t?.takeIf { ok(it.strong) }
            candidates += DateMatch(SmartDate(d.ymd.formatted, d.time ?: withTime?.time), lead + d.n + (withTime?.n ?: 0))
        }

        val t = timePart(w, start)
        if (t != null && ok(t.strong)) {
            val after = datePart(w, start + t.n, today, ctx.now)
            if (after != null && after.time == null && ok(after.strong)) {
                candidates += DateMatch(SmartDate(after.ymd.formatted, t.time), lead + t.n + after.n)
            } else {
                // A time on its own is today's, or tomorrow's once it has gone by.
                val date = if (t.time > ctx.now) today else addDays(today, 1)
                candidates += DateMatch(SmartDate(date.formatted, t.time), lead + t.n)
            }
        }

        return candidates.fold(null as DateMatch?) { best, c -> if (best == null || c.n > best.n) c else best }
    }

    // ── Repeat phrases ──────────────────────────────────────────────────────────────────────────

    private fun buildRule(freq: String, interval: Int, byDay: List<Int>?, count: Int?): String {
        val parts = mutableListOf("FREQ=$freq")
        if (interval > 1) parts += "INTERVAL=$interval"
        if (byDay != null) {
            val days = byDay.distinct().sortedBy { (it + 6) % 7 }
            parts += "BYDAY=" + days.joinToString(",") { RRULE_DAYS[it] }
        }
        if (count != null) parts += "COUNT=$count"
        return parts.joinToString(";")
    }

    /** "mon", "mon,wed", "mon and wed", "mon, wed and fri". */
    private fun weekdayList(w: List<String>, j: Int): Pair<List<Int>, Int>? {
        fun pieces(word: String) = word.split(",").filter { it.isNotEmpty() }
        val days = mutableListOf<Int>()
        var k = j
        while (k < w.size) {
            val p = pieces(w[k])
            if (p.isEmpty() || !p.all { WEEKDAYS[it] != null }) break
            days += p.map { WEEKDAYS[it]!!.first }
            k++
            val next = w.at(k + 1)
            if (w.at(k) == "and" && next != null && pieces(next).isNotEmpty() && pieces(next).all { WEEKDAYS[it] != null }) k++
        }
        return if (days.isEmpty()) null else days to (k - j)
    }

    private fun matchRepeat(w: List<String>, j: Int): RepeatMatch? {
        val a = w.at(j) ?: return null
        var freq: String? = null
        var interval = 1
        var byDay: List<Int>? = null
        var after = false
        var n = 0

        val simple = mapOf(
            "daily" to ("DAILY" to 1), "weekly" to ("WEEKLY" to 1), "monthly" to ("MONTHLY" to 1),
            "yearly" to ("YEARLY" to 1), "annually" to ("YEARLY" to 1), "biweekly" to ("WEEKLY" to 2),
            "fortnightly" to ("WEEKLY" to 2),
        )
        val freqs = mapOf(Unit.DAY to "DAILY", Unit.WEEK to "WEEKLY", Unit.MONTH to "MONTHLY", Unit.YEAR to "YEARLY")

        val s = simple[a]
        if (s != null) {
            freq = s.first
            interval = s.second
            n = 1
        } else if (a == "weekdays") {
            freq = "WEEKLY"
            byDay = listOf(1, 2, 3, 4, 5)
            n = 1
        } else if (a == "every" || a == "after") {
            after = a == "after"
            var k = j + 1
            val c = parseCount(w.at(k))
            if (!after && w.at(k) == "other") {
                interval = 2
                k++
            } else if (c != null && w.at(k + 1)?.let { UNITS[it] } != null) {
                interval = c
                k++
            }
            val unit = w.at(k)?.let { UNITS[it] }
            val f = unit?.let { freqs[it] }
            val list = if (!after) weekdayList(w, k) else null
            if (f != null) {
                freq = f
                n = k - j + 1
            } else if (!after && (w.at(k) == "weekday" || w.at(k) == "weekdays") && interval == 1) {
                freq = "WEEKLY"
                byDay = listOf(1, 2, 3, 4, 5)
                n = k - j + 1
            } else if (!after && (w.at(k) == "weekend" || w.at(k) == "weekends") && interval == 1) {
                freq = "WEEKLY"
                byDay = listOf(6, 0)
                n = k - j + 1
            } else if (list != null && (interval == 1 || interval == 2)) {
                freq = "WEEKLY"
                byDay = list.first
                n = k - j + list.second
            }
        }
        freq ?: return null

        // "for 5 times"
        var count: Int? = null
        val c = parseCount(w.at(j + n + 1))
        if (w.at(j + n) == "for" && c != null && c > 0 && (w.at(j + n + 2) == "times" || w.at(j + n + 2) == "time")) {
            count = c
            n += 3
        }
        return RepeatMatch(buildRule(freq, interval, byDay, count), after, n)
    }

    // ── Estimates ───────────────────────────────────────────────────────────────────────────────

    private val ESTIMATE = Regex("^(?:(\\d+(?:\\.\\d+)?)\\s*(?:h|hr|hrs|hour|hours))?\\s*(?:(\\d+)\\s*(?:m|min|mins|minute|minutes))?$")

    private fun matchEstimate(w: List<String>, j: Int): Pair<Int, Int>? {
        var len = minOf(4, w.size - j)
        while (len >= 1) {
            val text = w.subList(j, j + len).joinToString(" ")
            val m = ESTIMATE.find(text)
            if (m != null && (m.groups[1] != null || m.groups[2] != null)) {
                val hours = m.groups[1]?.value?.toDoubleOrNull() ?: 0.0
                val minutes = m.groups[2]?.value?.toIntOrNull() ?: 0
                return (Math.round(hours * 60).toInt() + minutes) to len
            }
            len--
        }
        return null
    }

    // ── Words ───────────────────────────────────────────────────────────────────────────────────

    private fun normalise(raw: String): String =
        raw.lowercase().replace(Regex("[.,;:!?)]+$"), "").replace(Regex("^\\(+"), "")

    private fun tokenize(text: String): List<Word> {
        val words = mutableListOf<Word>()
        var quoted = false
        for (raw in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val inQuote = quoted || raw.startsWith("\"")
            if (raw.count { it == '"' } % 2 == 1) quoted = !quoted
            words += Word(raw, normalise(raw), inQuote)
        }
        return words
    }
}
