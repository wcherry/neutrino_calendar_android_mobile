package com.neutrino.calendar.core.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The zone days are counted in and the day weeks start on: what a Swift `Calendar` carries in the
 * iOS app. Every rule that turns an instant into a day goes through one of these, so the zone and
 * the first weekday always come from the caller, and tests fix both.
 *
 * Days are [LocalDate]s where the iOS app uses a `Date` at local midnight. Java 8 `java.time` only
 * (no `LocalDate.ofInstant`): the app runs on Android 8, which has nothing newer.
 */
data class CalendarContext(val zone: ZoneId, val firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY) {

    /** The local day `instant` falls on. */
    fun day(instant: Instant): LocalDate = instant.atZone(zone).toLocalDate()

    /** The first instant of `day` here — not always 00:00 (a zone may skip midnight for DST). */
    fun startOf(day: LocalDate): Instant = day.atStartOfDay(zone).toInstant()

    fun dateTime(instant: Instant): LocalDateTime = LocalDateTime.ofInstant(instant, zone)

    /**
     * The instant a wall-clock time names here. A time in a DST gap moves forward by the gap and an
     * ambiguous one takes the earlier offset — what a JavaScript `Date` does with local fields.
     */
    fun instant(dateTime: LocalDateTime): Instant = ZonedDateTime.ofLocal(dateTime, zone, null).toInstant()

    fun instant(day: LocalDate, time: LocalTime): Instant = instant(LocalDateTime.of(day, time))

    fun isSameDay(a: Instant, b: Instant): Boolean = day(a) == day(b)

    /** 0 for Sunday through 6 for Saturday: JavaScript's `Date.getDay()`, in this zone. */
    fun jsDay(instant: Instant): Int = day(instant).dayOfWeek.value % 7

    companion object {
        fun system(firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY) = CalendarContext(ZoneId.systemDefault(), firstDayOfWeek)
        val UTC: ZoneId = ZoneOffset.UTC
    }
}

/**
 * The server writes `%Y-%m-%dT%H:%M:%SZ`; the web writes back `toISOString()`, which adds
 * milliseconds. Both have to read. Writing drops the fraction, as the iOS app's formatter does.
 */
object ServerDate {
    private val plain: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun parse(text: String?): Instant? {
        if (text.isNullOrBlank()) return null
        return runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
    }

    fun format(instant: Instant): String = plain.format(instant.truncatedTo(ChronoUnit.SECONDS))

    /** `yyyy-MM-dd` of an instant in UTC, as the web's `iso.slice(0, 10)` reads its ISO strings. */
    fun utcDay(instant: Instant): String = format(instant).substring(0, 10)
}

/** `yyyy-MM-dd`. */
fun LocalDate.key(): String = toString()

/** The day weeks start on, as the web stores it: a JavaScript day number. */
fun DayOfWeek.jsNumber(): Int = value % 7

fun dayOfWeekFromJs(n: Int): DayOfWeek = DayOfWeek.of(if (n == 0) 7 else n)

/** `String.format` in the root locale, so a wire value never picks up the device's digits. */
fun String.rootFormat(vararg args: Any?): String = String.format(java.util.Locale.ROOT, this, *args)
