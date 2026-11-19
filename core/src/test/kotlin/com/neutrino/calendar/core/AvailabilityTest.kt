package com.neutrino.calendar.core

import com.neutrino.calendar.core.logic.EventDraft
import com.neutrino.calendar.core.logic.ICSImport
import com.neutrino.calendar.core.logic.editedTo
import com.neutrino.calendar.core.model.CalendarContext
import com.neutrino.calendar.core.model.CalendarEvent
import com.neutrino.calendar.core.model.CalendarJson
import com.neutrino.calendar.core.model.EditConflict
import com.neutrino.calendar.core.model.EventAvailability
import com.neutrino.calendar.core.model.EventAvailability.AWAY
import com.neutrino.calendar.core.model.EventAvailability.BUSY
import com.neutrino.calendar.core.model.EventAvailability.FREE
import com.neutrino.calendar.core.model.EventAvailability.TENTATIVE
import com.neutrino.calendar.core.model.EventWire
import com.neutrino.calendar.core.logic.UpdateEventRequest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Busy, free, tentative or away: what the server sends, and what the form and an `.ics` send back. */
class AvailabilityTest {
    private val utc = CalendarContext(ZoneId.of("UTC"))
    private fun date(iso: String) = Instant.parse(iso)

    private fun decode(extra: String): CalendarEvent = CalendarEvent.from(
        CalendarJson.decodeFromString(
            EventWire.serializer(),
            """{"id":"e","title":"Lunch","startTime":"2026-11-20T20:00:00Z","endTime":"2026-11-20T21:00:00Z"$extra}""",
        ),
    )!!

    private val lunch = CalendarEvent(id = "e", title = "Lunch", start = date("2026-11-20T20:00:00Z"),
        end = date("2026-11-20T21:00:00Z"), availability = TENTATIVE)

    @Test
    fun decodesEachKindAndReadsAnOlderServerOrAnUnknownValueAsBusy() {
        assertEquals(TENTATIVE, decode(""","availability":"tentative"""").availability)
        assertEquals(AWAY, decode(""","availability":"away"""").availability)
        assertEquals("a server older than availability", BUSY, decode("").availability)
        assertEquals(BUSY, decode(""","availability":"oof"""").availability)
    }

    @Test
    fun aNewBusyEventDoesNotSendItButAnyOtherDoes() {
        val draft = EventDraft.newOn(utc.day(date("2026-11-20T20:00:00Z")), date("2026-11-20T08:00:00Z"), utc).copy(title = "Gym")
        assertNull("busy is the server's default", draft.createRequest().toJson()["availability"])
        assertEquals("free", draft.copy(availability = FREE).createRequest().toJson()["availability"]?.jsonPrimitive?.content)
    }

    @Test
    fun anEditStartsFromTheEventsAndSendsItOnlyWhenChanged() {
        val original = EventDraft.editing(lunch, utc)
        assertEquals(TENTATIVE, original.availability)
        assertNull(original.copy(title = "Long lunch").updateRequest(original).toJson()["availability"])
        assertEquals("busy", original.copy(availability = BUSY).updateRequest(original).toJson()["availability"]?.jsonPrimitive?.content)
    }

    @Test
    fun anOccurrenceAndALocalEditKeepIt() {
        assertEquals(TENTATIVE, lunch.with(date("2026-11-27T20:00:00Z"), date("2026-11-27T21:00:00Z"), null).availability)
        val draft = EventDraft.editing(lunch, utc).copy(availability = AWAY)
        assertEquals(AWAY, lunch.editedTo(draft).availability)
    }

    @Test
    fun aChangeToAvailabilityOnBothDevicesIsAConflict() {
        assertEquals(listOf("availability"), EditConflict.clashes(UpdateEventRequest(availability = FREE), UpdateEventRequest(availability = AWAY)))
        assertEquals(emptyList<String>(), EditConflict.clashes(UpdateEventRequest(availability = FREE), UpdateEventRequest(title = "Gym")))
    }

    @Test
    fun icalMatchesTheServer() {
        assertEquals(BUSY, EventAvailability.fromICal(null, null))
        assertEquals(FREE, EventAvailability.fromICal("TRANSPARENT", null))
        assertEquals(FREE, EventAvailability.fromICal(null, "free"))
        assertEquals(TENTATIVE, EventAvailability.fromICal("OPAQUE", "TENTATIVE"))
        assertEquals(AWAY, EventAvailability.fromICal(null, "OOF"))
        assertEquals("Apple left Outlook's status behind", BUSY, EventAvailability.fromICal("OPAQUE", "FREE"))
        assertEquals(FREE, EventAvailability.fromICal("TRANSPARENT", "OOF"))
    }

    @Test
    fun anIcsFileCarriesIt() {
        val ics = listOf("BEGIN:VCALENDAR", "VERSION:2.0", "BEGIN:VEVENT", "SUMMARY:Holiday",
            "DTSTART:20261123T090000Z", "DTEND:20261123T170000Z", "X-MICROSOFT-CDO-BUSYSTATUS:OOF",
            "END:VEVENT", "END:VCALENDAR").joinToString("\r\n")
        assertEquals(AWAY, ICSImport.draft(ics, utc).availability)
    }
}
