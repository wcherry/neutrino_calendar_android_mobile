package com.neutrino.calendar.core

import com.neutrino.calendar.core.logic.HolidayEngine
import com.neutrino.calendar.core.logic.Holidays
import com.neutrino.calendar.core.model.CalendarEvent
import com.neutrino.calendar.core.model.CalendarJson
import com.neutrino.calendar.core.model.CalendarKind
import com.neutrino.calendar.core.model.CalendarRules
import com.neutrino.calendar.core.model.CalendarSource
import com.neutrino.calendar.core.model.EventFilter
import com.neutrino.calendar.core.model.EventSource
import com.neutrino.calendar.core.model.ListCalendarsWire
import com.neutrino.calendar.core.model.ServerDate
import com.neutrino.calendar.core.model.SourceFilter
import com.neutrino.calendar.core.model.UserCalendar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

fun calendar(
    id: String, visible: Boolean = true, readOnly: Boolean = false, kind: CalendarKind = CalendarKind.LOCAL,
    color: String = UserCalendar.PALETTE[0], country: String? = null, region: String? = null, observances: Boolean = false,
    isDefault: Boolean = false,
) = UserCalendar(id, id, color, visible, readOnly, kind, isDefault, null, country, region, observances)

fun event(id: String, inCalendar: String?, source: EventSource = EventSource.Local, start: String = "2026-10-01T10:00:00Z") =
    CalendarEvent(id = id, title = id, start = Instant.parse(start), end = Instant.parse(start).plusSeconds(3600), source = source, calendarId = inCalendar)

/** The same expectations as the web's `calendars.test.ts` and the iOS tests, against the same `date-holidays` rules. */
class HolidayEngineTest {
    private val us = calendar("us", readOnly = true, kind = CalendarKind.HOLIDAYS, country = "US")

    private fun holidays(c: UserCalendar, from: String, to: String): List<CalendarEvent> = runBlocking {
        Holidays.events(c, Instant.parse(from), Instant.parse(to)) { year -> engine.holidays(c.country!!, c.region, year, "en-US") }
    }

    private fun days(events: List<CalendarEvent>, title: String) = events.filter { it.title == title }.map { ServerDate.utcDay(it.start) }

    @Test
    fun thanksgivingAndLaborDayFallWhereTheWebPutsThem() {
        val two = holidays(us, "2026-01-01T00:00:00Z", "2027-12-31T23:59:59Z")
        assertEquals(listOf("2026-11-26", "2027-11-25"), days(two, "Thanksgiving Day"))
        assertEquals("en-US says Labor, not Labour", listOf("2026-09-07", "2027-09-06"), days(two, "Labor Day"))
        val thanksgiving = two.first { it.title == "Thanksgiving Day" }
        assertTrue(thanksgiving.allDay)
        assertEquals(Instant.parse("2026-11-26T00:00:00Z"), thanksgiving.start)
        assertEquals(Instant.parse("2026-11-26T23:59:59Z"), thanksgiving.end)
        assertEquals("us", thanksgiving.calendarId)
        assertEquals(EventSource.Holidays, thanksgiving.source)
        assertTrue(CalendarRules(listOf(us)).isReadOnly(thanksgiving))
        assertFalse(CalendarRules(listOf(us)).isEditable(thanksgiving))
    }

    @Test
    fun observancesOnlyWhenAskedForAndOnlyTheRange() {
        assertEquals(emptyList<String>(), days(holidays(us, "2026-01-01T00:00:00Z", "2027-12-31T23:59:59Z"), "Mother's Day"))
        val with = holidays(us.copy(includeObservances = true), "2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z")
        assertEquals(listOf("2026-05-10"), days(with, "Mother's Day"))
        assertEquals(listOf("2026-10-31"), days(with, "Halloween"))
        val november = holidays(us, "2026-11-01T00:00:00Z", "2026-11-30T23:59:59Z")
        assertTrue(november.all { ServerDate.utcDay(it.start).startsWith("2026-11") })
        assertEquals(listOf("2026-11-26"), days(november, "Thanksgiving Day"))
    }

    @Test
    fun aRegionAddsItsOwnAndAPlainCalendarHasNone() {
        val de = calendar("de", readOnly = true, kind = CalendarKind.HOLIDAYS, country = "DE")
        val nationwide = holidays(de, "2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z")
        val bavaria = holidays(de.copy(region = "BY"), "2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z")
        assertTrue(bavaria.size > nationwide.size)
        assertEquals(emptyList<CalendarEvent>(), holidays(calendar("mine", country = "US"), "2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z"))
    }

    @Test
    fun eachCountryInItsOwnCalendarAndHidingOneHidesOnlyIt() = runBlocking {
        val ca = calendar("ca", readOnly = true, kind = CalendarKind.HOLIDAYS, color = "#e11d48", country = "CA")
        val both = engine.occurrences(listOf(us, ca), Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-31T23:59:59Z"), "en-US")
        assertEquals("us", both.first { it.event.title == "Independence Day" }.event.calendarId)
        assertEquals("ca", both.first { it.event.title == "Canada Day" }.event.calendarId)
        val filter = EventFilter(CalendarRules(listOf(us.copy(visible = false), ca)), SourceFilter.ALL)
        val shown = both.filter { filter.shows(it.event) }.map { it.event.title }
        assertFalse(shown.contains("Independence Day"))
        assertTrue(shown.contains("Canada Day"))
    }

    @Test
    fun listsCountriesAndRegions() = runBlocking {
        val countries = engine.countries("en-US")
        assertTrue(countries.size > 150)
        assertTrue(countries.any { it.code == "US" })
        assertTrue(engine.regions("US", "en-US").any { it.code == "CA" })
    }

    /** The bundle is the web's: the same major, and no older, whenever the sibling checkout is here. */
    @Test
    fun bundleIsTheWebsVersion() {
        val pkg = File("../../neutrino/web/apps/web/package.json")
        if (!pkg.exists()) return
        val wanted = Regex("\"date-holidays\"\\s*:\\s*\"[\\^~]?([\\d.]+)\"").find(pkg.readText())?.groupValues?.get(1) ?: return
        val ours = HolidayEngine.bundledVersion()!!
        assertEquals(wanted.substringBefore('.'), ours.substringBefore('.'))
        val cmp = ours.split('.').map { it.toInt() }.zip(wanted.split('.').map { it.toInt() }).firstOrNull { it.first != it.second }
        assertTrue("bundle $ours is older than the web's $wanted", cmp == null || cmp.first > cmp.second)
    }

    companion object {
        val engine = HolidayEngine()
    }
}

/** The web's `visibleEvents`, `isReadOnlyEvent` and `writableCalendars` cases. */
class CalendarRulesTest {
    @Test
    fun hiddenCalendarsEventsAreDroppedAndUnknownOnesKept() {
        val rules = CalendarRules(listOf(calendar("shown"), calendar("hidden", visible = false)))
        val events = listOf(event("a", "shown"), event("b", "hidden"), event("c", null), event("d", "unknown"))
        assertEquals(listOf("a", "c", "d"), events.filter(rules::isShown).map { it.id })
    }

    @Test
    fun readOnlyAndEditable() {
        val rules = CalendarRules(listOf(calendar("mine"), calendar("us", readOnly = true, kind = CalendarKind.HOLIDAYS, country = "US"), calendar("work", readOnly = true)))
        assertFalse(rules.isReadOnly(event("a", "mine")))
        assertTrue(rules.isReadOnly(event("b", "us")))
        assertTrue(rules.isReadOnly(event("c", null, EventSource.Holidays)))
        assertFalse(rules.isReadOnly(event("d", null)))
        assertTrue(rules.isEditable(event("a", "mine")))
        assertFalse(rules.isEditable(event("b", "work")))
        assertFalse(rules.isEditable(event("c", "mine", EventSource.Google)))
        assertFalse(rules.isEditable(event("d", null, EventSource.Task)))
        assertEquals(listOf("mine"), CalendarRules.writable(listOf(calendar("mine"), calendar("us", readOnly = true))).map { it.id })
    }

    @Test
    fun colourComesFromTheCalendar() {
        val rules = CalendarRules(listOf(calendar("red", color = "#e11d48")))
        assertEquals("#e11d48", rules.colorOf(event("a", "red")))
        assertNull(rules.colorOf(event("b", null)))
    }

    @Test
    fun theSourceFilterAndVisibilityBothHaveToAllowIt() {
        val rules = CalendarRules(listOf(calendar("shown"), calendar("hidden", visible = false)))
        val work = EventFilter(rules, SourceFilter(setOf(CalendarSource.NEUTRINO)))
        assertTrue(work.shows(event("a", "shown")))
        assertFalse(work.shows(event("b", "hidden")))
        assertFalse(work.shows(event("c", "shown", EventSource.Google)))
        assertTrue("holidays are Neutrino's own", work.shows(event("d", null, EventSource.Holidays)))
        assertTrue(work.ignoringFocus.shows(event("e", "shown", EventSource.Google)))
        assertFalse(work.ignoringFocus.shows(event("f", "hidden")))
        assertEquals("Neutrino and Google", SourceFilter(setOf(CalendarSource.GOOGLE, CalendarSource.NEUTRINO)).summary)
        assertFalse(SourceFilter(emptySet()).isActive)
    }

    @Test
    fun decodesTheServersShapes() {
        val body = """
        {"calendars":[{"id":"c1","name":"Personal","color":"#16a34a","visible":false,"readOnly":false,
          "kind":"local","isDefault":true,"source":null,"country":null,"region":null,"includeObservances":false,
          "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"},
         {"id":"c2","name":"United States","color":"#3b82f6","visible":true,"readOnly":true,"kind":"holidays",
          "isDefault":false,"country":"US","region":null,"includeObservances":true},
         {"id":"c3","name":"Later","kind":"something-new","color":null}]}
        """
        val list = CalendarJson.decodeFromString(ListCalendarsWire.serializer(), body).calendars
        assertEquals(3, list.size)
        assertFalse(list[0].visible)
        assertTrue(list[0].isDefault)
        assertEquals(CalendarKind.HOLIDAYS, list[1].kind)
        assertEquals("US", list[1].country)
        assertTrue(list[1].includeObservances)
        assertEquals("a kind added later reads as a plain calendar", CalendarKind.LOCAL, list[2].kind)
        assertEquals(UserCalendar.PALETTE[0], list[2].color)
        assertEquals("#f97316", UserCalendar.unusedColor(listOf(calendar("a", color = "#3B82F6"), calendar("b", color = "#16a34a"))))
    }
}
