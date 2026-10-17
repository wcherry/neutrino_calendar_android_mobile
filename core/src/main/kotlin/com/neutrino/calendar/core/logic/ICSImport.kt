package com.neutrino.calendar.core.logic

import com.neutrino.calendar.core.model.CalendarContext
import com.neutrino.calendar.core.model.RepeatOption
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Reads an `.ics` file shared from Gmail, Files or another app into a new event's form. Nothing is
 * saved: the editor opens filled in and the user adds it, as the web's "Import .ics" does.
 *
 * Honours `TZID`, floating times, the exclusive `DTEND` of an all-day event and `DURATION`. One
 * event is read: the first `VEVENT` that isn't an override of a single occurrence (`RECURRENCE-ID`).
 * A port of the iOS `ICSImport.swift`, held to its tests.
 */
object ICSImport {

    sealed class Failure(message: String) : Exception(message) {
        class Unreadable : Failure("That file couldn't be read as a calendar file.")
        class NoEvent : Failure("That calendar file has no event in it.")
        class Cancelled : Failure("That file cancels an event rather than adding one.")
    }

    /** Bytes as UTF-8, or Latin-1 when they aren't valid UTF-8. */
    fun decode(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
    }

    /** The event in `text` as a new event's form. `context` supplies the zone for floating times and all-day dates. */
    fun draft(text: String, context: CalendarContext, now: Instant = Instant.now()): EventDraft {
        val file = ICSFile(text)
        if (file.components.none { it.name == "VCALENDAR" || it.name == "VEVENT" }) throw Failure.Unreadable()
        if (file.method == "CANCEL") throw Failure.Cancelled()

        val events = file.components.filter { it.name == "VEVENT" }
        val event = events.firstOrNull { it["RECURRENCE-ID"] == null } ?: events.firstOrNull() ?: throw Failure.NoEvent()
        val dtstart = event["DTSTART"] ?: throw Failure.NoEvent()
        val start = file.time(dtstart, context) ?: throw Failure.NoEvent()

        val end: Instant = event["DTEND"]?.let { file.time(it, context)?.instant }
            ?: event["DURATION"]?.let { duration(it.value) }?.let { start.instant.plus(it) }
            // RFC 5545 §3.6.1: no end is one day for a date, and no time at all for a date-time.
            ?: if (start.isDate) start.instant.plus(Duration.ofDays(1)) else start.instant

        val zone = if (start.isDate) context.zone else start.zone
        val draftEnd = if (start.isDate) {
            // The form holds an all-day event's *last* day; the file gives the day after it.
            val last = end.atZone(zone).minusDays(1).toInstant()
            maxOf(last, start.instant)
        } else {
            maxOf(end, start.instant)
        }

        var draft = EventDraft.newOn(context.day(now), now, context).copy(
            title = event["SUMMARY"]?.let { unescape(it.value) } ?: "",
            location = event["LOCATION"]?.let { unescape(it.value) } ?: "",
            notes = event["DESCRIPTION"]?.let { unescape(it.value) } ?: "",
            allDay = start.isDate,
            timeZone = zone,
            start = start.instant,
            end = draftEnd,
        )
        event["RRULE"]?.value?.takeIf { it.isNotEmpty() }?.let { draft = draft.copy(repeatOption = RepeatOption.of(it)) }
        for (attendee in event.all("ATTENDEE")) {
            if (!attendee.value.startsWith("mailto:", ignoreCase = true)) continue
            draft.addingAttendee(attendee.value.substring("mailto:".length))?.let { draft = it }
        }
        return draft
    }

    /** TEXT unescaping, RFC 5545 §3.3.11. */
    fun unescape(s: String): String {
        val out = StringBuilder()
        var escaping = false
        for (c in s) {
            if (escaping) {
                out.append(if (c == 'n' || c == 'N') '\n' else c)
                escaping = false
            } else if (c == '\\') {
                escaping = true
            } else {
                out.append(c)
            }
        }
        return out.toString().trim()
    }

    /** `P1W`, `P1D`, `PT1H30M`, `-PT15M`: a DURATION value. */
    fun duration(s: String): Duration? {
        var rest = s.uppercase()
        var sign = 1L
        if (rest.startsWith("-")) {
            sign = -1
            rest = rest.drop(1)
        } else if (rest.startsWith("+")) {
            rest = rest.drop(1)
        }
        if (!rest.startsWith("P")) return null
        rest = rest.drop(1)
        var total = 0.0
        var number = ""
        var sawUnit = false
        for (c in rest) {
            if (c.isDigit()) {
                number += c
                continue
            }
            if (c == 'T') continue
            val n = number.toDoubleOrNull() ?: return null
            total += when (c) {
                'W' -> n * 604_800
                'D' -> n * 86_400
                'H' -> n * 3_600
                'M' -> n * 60
                'S' -> n
                else -> return null
            }
            number = ""
            sawUnit = true
        }
        return if (sawUnit && number.isEmpty()) Duration.ofSeconds(sign * total.toLong()) else null
    }
}

/**
 * The components and properties of an iCalendar file, flattened: each component keeps only its own
 * properties, so a `VALARM`'s `DESCRIPTION` doesn't become the event's notes.
 */
class ICSFile(text: String) {
    data class Property(val name: String, val params: Map<String, String>, val value: String)

    class Component(val name: String) {
        val properties = mutableListOf<Property>()
        operator fun get(name: String): Property? = properties.firstOrNull { it.name == name }
        fun all(name: String) = properties.filter { it.name == name }
    }

    /** A DTSTART or DTEND, read. */
    data class Time(val instant: Instant, val isDate: Boolean, val zone: ZoneId)

    val components = mutableListOf<Component>()

    init {
        val stack = ArrayDeque<Component>()
        for (line in unfold(text)) {
            val property = parse(line) ?: continue
            when (property.name) {
                "BEGIN" -> stack.addLast(Component(property.value.uppercase()))
                "END" -> stack.removeLastOrNull()?.let { components += it }
                else -> stack.lastOrNull()?.properties?.add(property)
            }
        }
    }

    /** `REQUEST`, `PUBLISH`, `CANCEL`… from the calendar object, uppercased. */
    val method: String? get() = components.firstOrNull { it.name == "VCALENDAR" }?.get("METHOD")?.value?.uppercase()

    fun time(property: Property, context: CalendarContext): Time? {
        val raw = property.value.trim()
        val isDate = property.params["VALUE"]?.uppercase() == "DATE" || raw.length == 8
        val digits = raw.filter { it.isDigit() }
        if (digits.length < 8) return null
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val zone: ZoneId
        val local: LocalDateTime
        if (isDate) {
            zone = context.zone
            local = runCatching { LocalDateTime.of(year, month, day, 0, 0) }.getOrNull() ?: return null
        } else {
            if (digits.length < 12) return null
            val second = if (digits.length >= 14) digits.substring(12, 14).toInt() else 0
            local = runCatching { LocalDateTime.of(year, month, day, digits.substring(8, 10).toInt(), digits.substring(10, 12).toInt(), second) }
                .getOrNull() ?: return null
            zone = when {
                raw.uppercase().endsWith("Z") -> ZoneOffset.UTC
                property.params["TZID"] != null -> timeZone(property.params["TZID"]!!) ?: context.zone
                else -> context.zone // floating: the same clock time wherever it's opened
            }
        }
        val instant = java.time.ZonedDateTime.ofLocal(local, zone, null).toInstant()
        // A UTC time is shown in the device's zone; a zoned one keeps its own, as it was sent.
        val shown = if (raw.uppercase().endsWith("Z")) context.zone else zone
        return Time(instant, isDate, shown)
    }

    /** A `TZID` as a zone: an IANA name, its `VTIMEZONE`'s `X-LIC-LOCATION`, or a Windows name Exchange sends. */
    fun timeZone(tzid: String): ZoneId? {
        val id = tzid.removePrefix("/")
        zone(id)?.let { return it }
        components.firstOrNull { it.name == "VTIMEZONE" && it["TZID"]?.value == tzid }?.get("X-LIC-LOCATION")
            ?.let { zone(it.value) }?.let { return it }
        return WINDOWS_ZONES[id]?.let(::zone)
    }

    private fun zone(id: String): ZoneId? = runCatching { ZoneId.of(id) }.getOrNull()

    companion object {
        /** RFC 5545 §3.1: a line starting with a space or tab continues the one before. */
        fun unfold(text: String): List<String> {
            val lines = mutableListOf<String>()
            for (raw in text.split(Regex("\r\n|\r|\n"))) {
                if ((raw.startsWith(" ") || raw.startsWith("\t")) && lines.isNotEmpty()) {
                    lines[lines.size - 1] = lines.last() + raw.drop(1)
                } else if (raw.isNotEmpty()) {
                    lines += raw
                }
            }
            return lines
        }

        /** `NAME;PARAM=a;PARAM="b:c":value`. The value starts at the first colon outside quotes. */
        fun parse(line: String): Property? {
            var inQuotes = false
            var valueStart = -1
            for ((i, c) in line.withIndex()) {
                if (c == '"') inQuotes = !inQuotes
                if (c == ':' && !inQuotes) {
                    valueStart = i + 1
                    break
                }
            }
            if (valueStart < 0) return null
            val head = line.substring(0, valueStart - 1)
            val pieces = head.split(";")
            val name = pieces.first().uppercase()
            if (name.isEmpty()) return null
            val params = mutableMapOf<String, String>()
            for (piece in pieces.drop(1)) {
                val eq = piece.indexOf('=')
                if (eq < 0) continue
                params[piece.substring(0, eq).uppercase()] = piece.substring(eq + 1).trim('"')
            }
            return Property(name, params, line.substring(valueStart))
        }

        /** The Windows zone names Exchange writes as `TZID`, to IANA (CLDR `windowsZones.xml`, territory "001"). */
        val WINDOWS_ZONES = mapOf(
            "Dateline Standard Time" to "Etc/GMT+12",
            "Hawaiian Standard Time" to "Pacific/Honolulu",
            "Alaskan Standard Time" to "America/Anchorage",
            "Pacific Standard Time" to "America/Los_Angeles",
            "US Mountain Standard Time" to "America/Phoenix",
            "Mountain Standard Time" to "America/Denver",
            "Central Standard Time" to "America/Chicago",
            "Central America Standard Time" to "America/Guatemala",
            "Canada Central Standard Time" to "America/Regina",
            "Central Standard Time (Mexico)" to "America/Mexico_City",
            "Eastern Standard Time" to "America/New_York",
            "US Eastern Standard Time" to "America/Indianapolis",
            "Atlantic Standard Time" to "America/Halifax",
            "Newfoundland Standard Time" to "America/St_Johns",
            "SA Pacific Standard Time" to "America/Bogota",
            "E. South America Standard Time" to "America/Sao_Paulo",
            "Argentina Standard Time" to "America/Buenos_Aires",
            "UTC" to "Etc/UTC",
            "GMT Standard Time" to "Europe/London",
            "Greenwich Standard Time" to "Atlantic/Reykjavik",
            "W. Europe Standard Time" to "Europe/Berlin",
            "Central Europe Standard Time" to "Europe/Budapest",
            "Romance Standard Time" to "Europe/Paris",
            "Central European Standard Time" to "Europe/Warsaw",
            "E. Europe Standard Time" to "Europe/Chisinau",
            "FLE Standard Time" to "Europe/Kiev",
            "GTB Standard Time" to "Europe/Bucharest",
            "Israel Standard Time" to "Asia/Jerusalem",
            "South Africa Standard Time" to "Africa/Johannesburg",
            "Egypt Standard Time" to "Africa/Cairo",
            "Turkey Standard Time" to "Europe/Istanbul",
            "Russian Standard Time" to "Europe/Moscow",
            "Arabian Standard Time" to "Asia/Dubai",
            "Pakistan Standard Time" to "Asia/Karachi",
            "India Standard Time" to "Asia/Calcutta",
            "SE Asia Standard Time" to "Asia/Bangkok",
            "China Standard Time" to "Asia/Shanghai",
            "Singapore Standard Time" to "Asia/Singapore",
            "Taipei Standard Time" to "Asia/Taipei",
            "Tokyo Standard Time" to "Asia/Tokyo",
            "Korea Standard Time" to "Asia/Seoul",
            "W. Australia Standard Time" to "Australia/Perth",
            "Cen. Australia Standard Time" to "Australia/Adelaide",
            "AUS Eastern Standard Time" to "Australia/Sydney",
            "E. Australia Standard Time" to "Australia/Brisbane",
            "New Zealand Standard Time" to "Pacific/Auckland",
        )
    }
}
