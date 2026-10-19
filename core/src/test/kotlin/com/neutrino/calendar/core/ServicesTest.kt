package com.neutrino.calendar.core

import com.neutrino.calendar.core.api.CalendarApi
import com.neutrino.calendar.core.logic.CalendarMode
import com.neutrino.calendar.core.logic.EventDraft
import com.neutrino.calendar.core.logic.HolidayEngine
import com.neutrino.calendar.core.model.CalendarContext
import com.neutrino.calendar.core.model.CalendarTask
import com.neutrino.calendar.core.model.CreateTaskRequest
import com.neutrino.calendar.core.model.EditConflict
import com.neutrino.calendar.core.model.GeoPoint
import com.neutrino.calendar.core.model.Patch
import com.neutrino.calendar.core.model.RecurrenceScope
import com.neutrino.calendar.core.model.TaskGeofence
import com.neutrino.calendar.core.service.AttachmentException
import com.neutrino.calendar.core.service.AttachmentFiles
import com.neutrino.calendar.core.service.CalendarSync
import com.neutrino.calendar.core.service.CalendarsService
import com.neutrino.calendar.core.service.EventsService
import com.neutrino.calendar.core.service.PlacesService
import com.neutrino.calendar.core.service.RemindersService
import com.neutrino.calendar.core.service.TasksService
import com.neutrino.calendar.core.sync.PendingWrites
import com.neutrino.shared.core.crypto.Sodium
import com.neutrino.shared.core.keys.Keyring
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The services against an in-memory server: real HTTP, real JSON, real crypto. */
class ServicesTest {
    private lateinit var server: FakeCalendar
    private lateinit var api: CalendarApi
    private lateinit var tmp: File
    private val zone = ZoneId.of("America/Los_Angeles")
    private val now = Instant.parse("2026-10-05T12:00:00Z")
    private val context = CalendarContext(zone)

    @Before
    fun setUp() {
        server = FakeCalendar()
        api = server.signedIn()
        tmp = Files.createTempDirectory("ncal-test").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tmp.deleteRecursively()
    }

    private fun events() = EventsService(api, { zone }, holidays = HolidayEngine(), now = { now })

    private fun sync(events: EventsService, pending: PendingWrites = PendingWrites(File(tmp, "pending.json"))): CalendarSync =
        CalendarSync(api, null, pending, events, RemindersService(api) { zone }, TasksService(api) { zone }, scope = kotlinx.coroutines.GlobalScope)

    @Test
    fun aMonthLoadsExpandedAndChangesElsewhereArriveThroughTheFeed() = runBlocking {
        server.putEvent("weekly") { mapOf("recurrenceRule" to JsonPrimitive("FREQ=WEEKLY")) }
        server.putEvent("gone") { mapOf("startTime" to JsonPrimitive("2026-10-07T16:00:00Z"), "endTime" to JsonPrimitive("2026-10-07T17:00:00Z")) }
        val events = events()
        events.ensureLoaded(CalendarMode.MONTH)
        assertEquals(4, events.byMonth.value.values.flatten().count { it.event.id == "weekly" })
        assertEquals(1, events.occurrencesOn(LocalDate.of(2026, 10, 7)).size)
        // Every request names this running app, so it can skip its own changes' signals.
        assertEquals(api.clientId, server.lastClientId)

        // Changed on the web: a new title, and one event deleted.
        server.putEvent("weekly") { mapOf("recurrenceRule" to JsonPrimitive("FREQ=WEEKLY"), "title" to JsonPrimitive("Stand-up")) }
        server.events.remove("gone")
        server.changeCounter++
        server.deletions += server.changeCounter to "gone"
        events.pullChanges()
        assertTrue(events.occurrencesOn(LocalDate.of(2026, 10, 12)).single().event.title == "Stand-up")
        assertTrue(events.occurrencesOn(LocalDate.of(2026, 10, 7)).isEmpty())
    }

    @Test
    fun anEditStopsOnlyWhenTheSameFieldChangedElsewhere() = runBlocking {
        server.putEvent("e1")
        val events = events()
        val event = api.event("e1")
        val original = EventDraft.editing(event, context)

        // The web renamed it; this device moves it. Different fields: no conflict, and the rename survives.
        server.events["e1"] = kotlinx.serialization.json.JsonObject(server.events["e1"]!! + ("title" to JsonPrimitive("Renamed")))
        events.update(event, original, original.withStart(original.start.plusSeconds(3600)))
        assertEquals("Renamed", server.events["e1"]!!.str("title"))
        assertEquals("2026-10-05T17:00:00Z", server.events["e1"]!!.str("startTime"))

        // Both renamed it: a conflict, about the title, until the user says to save anyway.
        val fresh = api.event("e1")
        val draft = EventDraft.editing(fresh, context)
        server.events["e1"] = kotlinx.serialization.json.JsonObject(server.events["e1"]!! + ("title" to JsonPrimitive("Theirs")))
        try {
            events.update(fresh, draft, draft.copy(title = "Mine")); fail()
        } catch (e: EditConflict.ChangedElsewhere) {
            assertEquals(listOf("title"), e.fields)
        }
        events.update(fresh, draft, draft.copy(title = "Mine"), overwrite = true)
        assertEquals("Mine", server.events["e1"]!!.str("title"))

        server.events.remove("e1")
        try {
            events.update(fresh, draft, draft.copy(title = "Again")); fail()
        } catch (_: EditConflict.DeletedElsewhere) {
        }
    }

    @Test
    fun offlineEditsAreQueuedShownAndReplayedAndARefusedOneIsReported() = runBlocking {
        server.putEvent("e1")
        server.putEvent("e2")
        val events = events()
        val sync = sync(events)
        events.ensureLoaded(CalendarMode.MONTH)
        val e1 = api.event("e1")
        val draft = EventDraft.editing(e1, context)

        server.offline = true
        val local = events.update(e1, draft, draft.copy(title = "Offline title"), overwrite = true)
        assertEquals("Offline title", local.title)
        assertEquals("Offline title", events.occurrencesOn(LocalDate.of(2026, 10, 5)).first { it.event.id == "e1" }.event.title)
        events.delete(events.occurrencesOn(LocalDate.of(2026, 10, 5)).first { it.event.id == "e2" }.event)
        assertEquals(2, sync.pending.writes.value.size)
        assertFalse(events.occurrencesOn(LocalDate.of(2026, 10, 5)).any { it.event.id == "e2" })

        // Back online: both reach the server, in order, and the queue empties.
        server.offline = false
        sync.catchUp()
        assertTrue(sync.pending.isEmpty)
        assertEquals("Offline title", server.events["e1"]!!.str("title"))
        assertNull(server.events["e2"])

        // A write the server now refuses for good is dropped, and the user told why.
        val now1 = api.event("e1")
        val draft1 = EventDraft.editing(now1, context)
        server.offline = true
        events.update(now1, draft1, draft1.copy(title = "Locked"), overwrite = true)
        server.offline = false
        server.failWritesWith = 403
        sync.catchUp()
        assertTrue(sync.pending.isEmpty)
        assertTrue(sync.pending.notice.value!!.contains("read-only"))
        assertEquals("Offline title", server.events["e1"]!!.str("title"))

        // The queue survives the app being closed.
        server.failWritesWith = null
        server.offline = true
        events.update(now1, draft1, draft1.copy(title = "Kept"), overwrite = true)
        assertEquals(1, PendingWrites(File(tmp, "pending.json")).writes.value.size)
    }

    @Test
    fun aServerErrorShowsAsAFailedSyncUntilTheNextReplay() = runBlocking {
        server.putEvent("e1")
        val events = events()
        val sync = sync(events)
        events.ensureLoaded(CalendarMode.MONTH)
        val e1 = api.event("e1")
        val draft = EventDraft.editing(e1, context)
        server.offline = true
        events.update(e1, draft, draft.copy(title = "Offline title"), overwrite = true)

        server.offline = false
        server.failWritesWith = 500
        sync.catchUp()
        assertEquals(1, sync.pending.writes.value.size)
        assertEquals("Server error (500).", sync.pending.lastFailure.value)
        assertFalse(sync.pending.isReplaying.value)

        server.failWritesWith = null
        sync.catchUp()
        assertTrue(sync.pending.isEmpty)
        assertNull(sync.pending.lastFailure.value)
    }

    @Test
    fun repeatingEventsEditOneOccurrenceOrSplit() = runBlocking {
        server.putEvent("series") { mapOf("recurrenceRule" to JsonPrimitive("FREQ=DAILY;COUNT=10")) }
        val events = events()
        events.ensureLoaded(CalendarMode.MONTH)
        val third = events.occurrencesOn(LocalDate.of(2026, 10, 7)).single()
        val draft = EventDraft.editing(third, RecurrenceScope.THIS, context)
        events.update(third, RecurrenceScope.THIS, draft, draft.copy(title = "Just this one"))
        assertTrue(server.log.any { it.startsWith("PUT /api/v1/calendar/events/series/occurrences/2026-10-07T16:00:00Z") })
        events.ensureLoaded(CalendarMode.MONTH)
        assertEquals("Just this one", events.occurrencesOn(LocalDate.of(2026, 10, 7)).single().event.title)
        assertEquals("series", events.occurrencesOn(LocalDate.of(2026, 10, 8)).single().event.title)

        // "This and following" from the 4th sends the rule with the COUNT left: 10 less the three before.
        val fourth = events.occurrencesOn(LocalDate.of(2026, 10, 8)).single()
        val following = EventDraft.editing(fourth, RecurrenceScope.FOLLOWING, context)
        assertEquals("FREQ=DAILY;COUNT=7", following.repeatOption.rule)
        events.update(fourth, RecurrenceScope.FOLLOWING, following, following.copy(title = "Later"))
        assertTrue(server.log.any { it == "POST /api/v1/calendar/events/series/split" })
    }

    @Test
    fun remindersMoveOnWhenARepeatingOneIsDoneOrSkipped() = runBlocking {
        val reminders = RemindersService(api) { zone }
        val due = Instant.parse("2026-10-06T16:00:00Z")
        val daily = reminders.create("Pills", due, "FREQ=DAILY")
        val once = reminders.create("Call", due, null)
        reminders.setCompleted(daily, true)
        assertEquals("the server's answer is the new state", Instant.parse("2026-10-07T16:00:00Z"), reminders.reminders.value.first { it.id == daily.id }.due)
        assertFalse(reminders.reminders.value.first { it.id == daily.id }.completed)
        reminders.setCompleted(once, true)
        assertTrue(reminders.reminders.value.first { it.id == once.id }.completed)

        val current = reminders.reminders.value.first { it.id == daily.id }
        reminders.delete(current, RecurrenceScope.THIS)
        assertEquals(Instant.parse("2026-10-08T16:00:00Z"), reminders.reminders.value.first { it.id == daily.id }.due)
        reminders.update(reminders.reminders.value.first { it.id == daily.id }, RecurrenceScope.THIS, "Pills, late", Instant.parse("2026-10-08T18:00:00Z"), "FREQ=DAILY")
        assertEquals(3, reminders.reminders.value.size)
        assertTrue(reminders.reminders.value.any { it.title == "Pills, late" && it.recurrenceRule == null })

        val (open, done) = reminders.visible(com.neutrino.calendar.core.model.ReminderRange.ALL, "", now, context)
        assertEquals(listOf("Pills, late", "Pills").sorted(), open.map { it.title }.sorted())
        assertEquals(listOf("Call"), done.map { it.title })
    }

    @Test
    fun tasksCompleteSendClearsAndQueueOffline() = runBlocking {
        val tasks = TasksService(api) { zone }
        tasks.pending = PendingWrites(null)
        val weekly = tasks.create(CreateTaskRequest("Bins", recurrenceRule = "FREQ=WEEKLY", notes = "Blue one"))
        tasks.setDone(weekly, true)
        assertEquals("completing a repeating task brings its next", 2, tasks.tasks.value.size)

        // Emptying the notes clears them on the server (a JSON null), and the tags are normalised.
        val task = tasks.tasks.value.first { it.id == weekly.id }
        tasks.update(task, "Bins", "", null, context, tags = listOf("#Home", "chores"))
        assertTrue(server.tasks[weekly.id]!!["notes"] is kotlinx.serialization.json.JsonNull)
        assertEquals(listOf("chores", "home"), (server.tasks[weekly.id]!!["tags"] as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content })

        // A point geofence, then a saved place in its stead: all four fields go, so one replaces the other.
        val t2 = tasks.task(weekly.id)!!
        tasks.update(t2, t2.title, "", null, context, geofence = Patch.Set(TaskGeofence.Point(GeoPoint.of(51.5, -0.12))))
        assertEquals("51.5", server.tasks[weekly.id]!!.str("geoLat"))
        tasks.update(tasks.task(weekly.id)!!, t2.title, "", null, context, geofence = Patch.Set(TaskGeofence.Place("p1")))
        assertTrue(server.tasks[weekly.id]!!["geoLat"] is kotlinx.serialization.json.JsonNull)
        assertEquals("p1", server.tasks[weekly.id]!!.str("geoPlaceId"))

        server.offline = true
        tasks.setDone(tasks.tasks.value.first { !it.done }, true)
        assertTrue("ticked at once, and kept offline", tasks.tasks.value.all { it.done })
        assertEquals(1, tasks.pending!!.writes.value.size)
    }

    @Test
    fun savedPlacesAreSealedToTheKeyAndOthersCannotReadThem() = runBlocking {
        val mine = Keyring.fromKeyPair(server.fake.userId, Sodium.boxKeyPair().secretKey, 1)
        val places = PlacesService(api) { mine }
        assertTrue(places.canSave)
        places.create("Home", GeoPoint.of(51.5, -0.12, 200))
        assertFalse("the server holds ciphertext only", server.places.values.single().toString().contains("Home"))
        val again = PlacesService(api) { mine }
        again.reload()
        assertEquals("Home", again.places.value.single().name)
        assertEquals(200, again.places.value.single().point.radius)
        assertEquals("Home", again.match("home")?.name)

        val stranger = PlacesService(api) { Keyring.fromKeyPair(server.fake.userId, Sodium.boxKeyPair().secretKey, 1) }
        stranger.reload()
        assertTrue(stranger.places.value.isEmpty())
        assertEquals(1, stranger.unreadable.value)
        assertFalse(PlacesService(api) { null }.canSave)
    }

    @Test
    fun attachmentsAreEncryptedOnTheWayUpAndOpenOnTheWayDown() = runBlocking {
        val ring = Keyring.fromKeyPair(server.fake.userId, Sodium.boxKeyPair().secretKey, 1)
        val files = AttachmentFiles(api, { ring }, File(tmp, "attachments"))
        val uploaded = files.upload("meeting notes".toByteArray(), "notes", "text/plain")
        assertEquals("notes.txt", uploaded.name)
        val stored = server.fake.files[uploaded.id]!!
        assertFalse("ciphertext on the server", stored.blob.decodeToString().contains("meeting"))
        assertEquals("Attachments", api.folder(server.fake.userId).folders.firstOrNull()?.name ?: "Attachments")

        val copy = files.localCopy(api.fileMetadata(uploaded.id))
        assertEquals("meeting notes", copy.readText())
        files.clearCache()
        assertFalse(copy.exists())

        try {
            AttachmentFiles(api, { null }, File(tmp, "x")).upload(ByteArray(1), "a", "text/plain"); fail()
        } catch (_: AttachmentException.NoKey) {
        }
        try {
            AttachmentFiles(api, { Keyring.fromKeyPair(server.fake.userId, Sodium.boxKeyPair().secretKey, 2) }, File(tmp, "y"))
                .localCopy(api.fileMetadata(uploaded.id)); fail()
        } catch (_: AttachmentException.MissingKeyVersion) {
        }
    }

    @Test
    fun calendarsHideAtOnceRollBackOnErrorAndAreKeptForOffline() = runBlocking {
        val cache = File(tmp, "calendars.json")
        val calendars = CalendarsService(api, cache)
        calendars.reload()
        val personal = calendars.calendars.value.single()
        server.failWritesWith = 500
        calendars.setVisible(personal, false)
        assertTrue("put back when the server refuses", calendars.calendars.value.single().visible)
        assertTrue(calendars.error.value!!.startsWith("Couldn't hide"))
        server.failWritesWith = null
        calendars.setVisible(personal, false)
        assertFalse(calendars.calendars.value.single().visible)
        assertFalse("read from disk with no network", CalendarsService(api, cache).calendars.value.single().visible)
        calendars.reset()
        assertFalse(cache.exists())
    }

    @Test
    fun aTaskWithADueDateIsDrawnOnTheCalendar() = runBlocking {
        val events = events()
        events.setTasks(listOf(CalendarTask("t1", "File taxes", dueDate = Instant.parse("2026-10-09T00:00:00Z"))))
        events.ensureLoaded(CalendarMode.MONTH)
        val drawn = events.occurrencesOn(LocalDate.of(2026, 10, 9)).single()
        assertEquals("t1", drawn.task?.id)
        assertTrue(drawn.event.allDay)
    }
}
