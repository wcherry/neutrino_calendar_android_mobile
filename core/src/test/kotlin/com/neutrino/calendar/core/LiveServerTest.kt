package com.neutrino.calendar.core

import com.neutrino.calendar.core.api.CalendarApi
import com.neutrino.calendar.core.logic.CalendarMode
import com.neutrino.calendar.core.logic.EventDraft
import com.neutrino.calendar.core.logic.HolidayEngine
import com.neutrino.calendar.core.model.CalendarContext
import com.neutrino.calendar.core.model.CreateCalendarRequest
import com.neutrino.calendar.core.model.CreateTaskRequest
import com.neutrino.calendar.core.model.GeoPoint
import com.neutrino.calendar.core.model.RecurrenceScope
import com.neutrino.calendar.core.model.RepeatOption
import com.neutrino.calendar.core.service.AttachmentFiles
import com.neutrino.calendar.core.service.CalendarsService
import com.neutrino.calendar.core.service.EventsService
import com.neutrino.calendar.core.service.PlacesService
import com.neutrino.calendar.core.service.RemindersService
import com.neutrino.calendar.core.service.TasksService
import com.neutrino.shared.core.MemorySessionStore
import com.neutrino.shared.core.api.AuthClient
import com.neutrino.shared.core.api.NeutrinoApps
import com.neutrino.shared.core.keys.DeviceKeyCheck
import com.neutrino.shared.core.keys.InMemoryKeyringStore
import com.neutrino.shared.core.keys.KeyManager
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Against a real server, when `NEUTRINO_LIVE_URL` names one (a throwaway database, with the
 * `neutrino-calendar-android` migration). Registers a fresh account each run.
 */
class LiveServerTest {
    @Test
    fun theWholeAppAgainstARealServer() = runBlocking {
        val url = System.getenv("NEUTRINO_LIVE_URL")
        assumeTrue("set NEUTRINO_LIVE_URL to run", url != null)
        val http = OkHttpClient()
        val auth = AuthClient(NeutrinoApps.calendar, http, MemorySessionStore(), url!!) { "JVM test - Neutrino Calendar" }
        val api = CalendarApi(http, auth)
        val email = "cal-${UUID.randomUUID().toString().take(8)}@example.com"
        auth.register("Calendar Test", email, "correct horse battery")
        assertTrue(auth.isSignedIn)

        val zone = ZoneId.of("America/Los_Angeles")
        val context = CalendarContext(zone)
        val keys = InMemoryKeyringStore()
        val manager = KeyManager(api, keys, DeviceKeyCheck(api, keys))
        manager.provision()

        // Calendars: the server makes a default; add one and a country's holidays.
        val calendars = CalendarsService(api, null)
        calendars.reload()
        assertTrue(calendars.defaultCalendar != null)
        val work = calendars.create(CreateCalendarRequest.local("Work", "#16a34a"))
        calendars.create(CreateCalendarRequest.holidays("US", "United States", "#e11d48"))

        // Events: a weekly series in Work, one occurrence edited on its own, then listed and expanded.
        val events = EventsService(api, { zone }, holidays = HolidayEngine(), now = { Instant.now() })
        events.setCalendars(calendars.calendars.value)
        val day = context.day(Instant.now()).plusDays(1)
        val created = events.create(EventDraft.newOn(day, Instant.now(), context).copy(title = "Stand-up", repeatOption = RepeatOption.Weekly, calendarId = work.id))
        assertEquals(work.id, created.calendarId)
        events.select(day)
        events.ensureLoaded(CalendarMode.MONTH)
        val first = events.occurrencesOn(day).single { it.event.id == created.id }
        val draft = EventDraft.editing(first, RecurrenceScope.THIS, context)
        events.update(first, RecurrenceScope.THIS, draft, draft.copy(title = "Stand-up (moved)"))
        events.ensureLoaded(CalendarMode.MONTH)
        assertEquals("Stand-up (moved)", events.occurrencesOn(day).single { it.series?.id == created.id }.event.title)
        assertTrue(api.eventChanges(null).cursor.isNotEmpty())

        // Reminders and tasks.
        val reminders = RemindersService(api) { zone }
        val r = reminders.create("Call back", Instant.now().plusSeconds(3600), null, eventId = created.id)
        reminders.setCompleted(r, true)
        assertTrue(reminders.reminders.value.single().completed)
        val tasks = TasksService(api) { zone }
        val t = tasks.create(CreateTaskRequest("Buy milk", tags = listOf("errands")))
        tasks.update(t, "Buy oat milk", "", day, context)
        tasks.reload()
        assertEquals("Buy oat milk", tasks.task(t.id)!!.title)
        tasks.schedule(tasks.task(t.id)!!, context.startOf(day), context.startOf(day), true, context)
        assertTrue(tasks.task(t.id)!!.eventId != null)

        // A saved place, sealed to the account key, read back.
        val places = PlacesService(api) { manager.keyring() }
        places.create("Home", GeoPoint.of(37.77, -122.42, 200))
        val again = PlacesService(api) { manager.keyring() }
        again.reload()
        assertEquals("Home", again.places.value.single().name)

        // An attachment: encrypted up, decrypted back, attached to the event.
        val files = AttachmentFiles(api, { manager.keyring() }, Files.createTempDirectory("ncal-live").toFile())
        val file = files.upload("agenda".toByteArray(), "agenda.txt", "text/plain")
        events.attachmentOwner(created).add(com.neutrino.calendar.core.model.CreateAttachmentRequest.file(file.id, file.name))
        assertEquals("agenda", files.localCopy(api.fileMetadata(file.id)).readText())
        assertEquals(1, api.eventAttachments(created.id).size)
        println("live: ${auth.email} ok")
    }
}
