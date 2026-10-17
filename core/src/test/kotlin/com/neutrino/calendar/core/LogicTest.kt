package com.neutrino.calendar.core

import com.neutrino.calendar.core.logic.EventDraft
import com.neutrino.calendar.core.logic.EventOccurrence
import com.neutrino.calendar.core.logic.GeofencePlan
import com.neutrino.calendar.core.logic.ICSImport
import com.neutrino.calendar.core.logic.NearbyTasks
import com.neutrino.calendar.core.logic.NotificationPlan
import com.neutrino.calendar.core.logic.RecurrenceExpander
import com.neutrino.calendar.core.logic.TaskFilter
import com.neutrino.calendar.core.logic.TaskOccurrences
import com.neutrino.calendar.core.logic.TimeGridLayout
import com.neutrino.calendar.core.logic.WidgetSnapshot
import com.neutrino.calendar.core.model.CalendarContext
import com.neutrino.calendar.core.model.CalendarEvent
import com.neutrino.calendar.core.model.CalendarJson
import com.neutrino.calendar.core.model.CalendarRules
import com.neutrino.calendar.core.model.CalendarTask
import com.neutrino.calendar.core.model.EventFilter
import com.neutrino.calendar.core.model.GeoPoint
import com.neutrino.calendar.core.model.Patch
import com.neutrino.calendar.core.model.RecurrenceScope
import com.neutrino.calendar.core.model.Reminder
import com.neutrino.calendar.core.model.RepeatOption
import com.neutrino.calendar.core.model.SourceFilter
import com.neutrino.calendar.core.model.TaskGeofence
import com.neutrino.calendar.core.model.TaskPlace
import com.neutrino.calendar.core.model.TaskTags
import com.neutrino.calendar.core.model.UpdateTaskRequest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private fun date(iso: String): Instant = Instant.parse(iso)
private val pacific = CalendarContext(ZoneId.of("America/Los_Angeles"))
private val utc = CalendarContext(ZoneOffset.UTC)

/** The web's `recurrenceExceptions.test.ts` cases, as the iOS app runs them. */
class RecurrenceExceptionTest {
    private fun standup(rule: String = "FREQ=DAILY;COUNT=5", start: String = "2027-01-04T09:00:00Z") =
        CalendarEvent(id = "series", title = "Standup", start = date(start), end = date(start).plusSeconds(1800), recurrenceRule = rule)

    private fun exception(id: String, original: String, title: String = "Standup", start: String? = null, cancelled: Boolean = false, series: String = "series"): CalendarEvent {
        val at = date(start ?: original)
        return CalendarEvent(id = id, title = title, start = at, end = at.plusSeconds(1800), recurringEventId = series, originalStart = date(original), cancelled = cancelled)
    }

    private fun january(events: List<CalendarEvent>) = RecurrenceExpander.expand(events, date("2027-01-01T00:00:00Z"), date("2027-01-31T23:59:59Z"), utc)
    private fun summary(events: List<CalendarEvent>) = january(events).map { "${it.start.toString().substring(5, 16)} ${it.event.title}" }.sorted()

    @Test
    fun exceptionsReplaceCancelAndMoveOccurrencesAsTheWebDoes() {
        val first = january(listOf(standup()))[0]
        assertEquals("series", first.series?.id)
        assertEquals(date("2027-01-04T09:00:00Z"), first.originalStart)
        assertEquals(
            listOf("01-04T09:00 Standup", "01-05T14:00 Planning", "01-06T09:00 Standup", "01-07T09:00 Standup", "01-08T09:00 Standup"),
            summary(listOf(standup(), exception("ex", "2027-01-05T09:00:00Z", "Planning", "2027-01-05T14:00:00Z"))),
        )
        val shown = january(listOf(standup(), exception("ex", "2027-01-05T09:00:00Z", "Planning"))).first { it.event.title == "Planning" }
        assertEquals("ex", shown.event.id)
        assertEquals("series", shown.series?.id)
        assertEquals(
            listOf("01-04T09:00 Standup", "01-05T09:00 Standup", "01-07T09:00 Standup", "01-08T09:00 Standup"),
            summary(listOf(standup(), exception("ex", "2027-01-06T09:00:00Z", cancelled = true))),
        )
        assertFalse(summary(listOf(standup(), exception("ex", "2027-01-06T08:00:00Z", cancelled = true))).contains("01-06T09:00 Standup"))
        assertTrue(summary(listOf(standup(), exception("ex", "2027-01-06T06:00:00Z", cancelled = true))).contains("01-06T09:00 Standup"))
        assertEquals(
            listOf("01-02T09:00 Moved in", "01-04T09:00 Standup", "01-11T09:00 Standup", "01-18T09:00 Standup"),
            summary(listOf(
                standup("FREQ=WEEKLY", "2026-12-28T09:00:00Z"),
                exception("in", "2026-12-28T09:00:00Z", "Moved in", "2027-01-02T09:00:00Z"),
                exception("out", "2027-01-25T09:00:00Z", "Moved out", "2027-02-02T09:00:00Z"),
            )),
        )
        assertFalse(summary(listOf(standup(), exception("orphan", "2027-01-20T09:00:00Z", "Orphan"))).contains("01-20T09:00 Orphan"))
        assertEquals(5, summary(listOf(standup(), exception("ex", "2027-01-05T09:00:00Z", cancelled = true, series = "other"))).size)
    }

    @Test
    fun theFormForEachScope() {
        assertEquals("FREQ=DAILY;COUNT=3", RecurrenceExpander.ruleFrom(standup(), date("2027-01-06T09:00:00Z"), utc))
        assertEquals("FREQ=WEEKLY;BYDAY=MO", RecurrenceExpander.ruleFrom(standup("FREQ=WEEKLY;BYDAY=MO"), date("2027-01-11T09:00:00Z"), utc))
        fun on(day: String) = january(listOf(standup())).first { it.start.toString().startsWith(day) }
        val third = on("2027-01-06")
        assertEquals(date("2027-01-06T09:00:00Z"), EventDraft.editing(third, RecurrenceScope.THIS, utc).start)
        val following = EventDraft.editing(third, RecurrenceScope.FOLLOWING, utc)
        assertEquals(date("2027-01-06T09:00:00Z"), following.start)
        assertEquals("FREQ=DAILY;COUNT=3", following.repeatOption.rule)
        assertEquals(date("2027-01-04T09:00:00Z"), EventDraft.editing(third, RecurrenceScope.ALL, utc).start)
        assertEquals(RecurrenceScope.ALL, EventDraft.effectiveScope(on("2027-01-04"), RecurrenceScope.FOLLOWING))
        assertEquals(RecurrenceScope.FOLLOWING, EventDraft.effectiveScope(on("2027-01-05"), RecurrenceScope.FOLLOWING))
        assertNull(EventDraft.effectiveScope(EventOccurrence(standup(""), date("2027-01-04T09:00:00Z"), date("2027-01-04T09:30:00Z")), RecurrenceScope.THIS))
    }
}

class EventDraftTest {
    @Test
    fun aNewEventTodayStartsAtTheNextHourAndOtherDaysAtNine() {
        val now = date("2026-09-25T17:20:00Z") // 10:20 PDT
        val today = EventDraft.newOn(pacific.day(now), now, pacific)
        assertEquals(date("2026-09-25T18:00:00Z"), today.start)
        assertEquals(date("2026-09-25T19:00:00Z"), today.end)
        val later = EventDraft.newOn(LocalDate.of(2026, 9, 30), now, pacific)
        assertEquals("09:00 PDT", date("2026-09-30T16:00:00Z"), later.start)
    }

    @Test
    fun editingSeedsFromTheSeriesAndWritesAllDayAsDates() {
        val series = CalendarEvent(id = "s", title = "Standup", start = date("2026-09-01T16:00:00Z"), end = date("2026-09-01T16:15:00Z"),
            recurrenceRule = "FREQ=DAILY", attendees = listOf("ada@example.com"), timezone = "America/New_York")
        val draft = EventDraft.editing(series, pacific)
        assertEquals(series.start, draft.start)
        assertEquals(RepeatOption.Daily, draft.repeatOption)
        assertEquals("America/New_York", draft.timeZone.id)

        val offsite = CalendarEvent(id = "o", title = "Offsite", start = date("2026-09-28T00:00:00Z"), end = date("2026-09-30T23:59:59Z"), allDay = true)
        val body = EventDraft.editing(offsite, pacific).createRequest().toJson()
        assertEquals("2026-09-28T00:00:00Z", body["startTime"]!!.jsonPrimitive.content)
        assertEquals("2026-09-30T23:59:59Z", body["endTime"]!!.jsonPrimitive.content)
        assertEquals("true", body["allDay"]!!.jsonPrimitive.content)
        assertNull("an all-day event carries no zone", body["timezone"])

        val timed = EventDraft.newOn(LocalDate.of(2026, 9, 30), date("2026-09-25T00:00:00Z"), pacific).copy(title = "Review", timeZone = ZoneId.of("America/New_York"))
        val t = timed.createRequest().toJson()
        assertEquals("America/New_York", t["timezone"]!!.jsonPrimitive.content)
        assertEquals("[]", t["attendees"].toString())
        assertNull("an empty location is not sent on create", t["location"])
    }

    @Test
    fun anEditSendsOnlyChangesAndClearsWithAnEmptyString() {
        val event = CalendarEvent(id = "e", title = "Review", description = "Bring notes", start = date("2026-09-30T18:00:00Z"),
            end = date("2026-09-30T19:00:00Z"), location = "Room 4", recurrenceRule = "FREQ=WEEKLY", timezone = "America/Los_Angeles")
        val original = EventDraft.editing(event, pacific)
        assertTrue("nothing changed", original.updateRequest(original).isEmpty)
        val body = original.copy(location = "  ", repeatOption = RepeatOption.Never).updateRequest(original).toJson()
        assertEquals(listOf("location", "recurrenceRule"), body.keys.sorted())
        assertEquals("", body["location"]!!.jsonPrimitive.content)
        assertEquals("", body["recurrenceRule"]!!.jsonPrimitive.content)
        val moved = original.copy(start = original.start.plusSeconds(3600), end = original.end.plusSeconds(3600)).updateRequest(original).toJson()
        assertEquals(listOf("allDay", "endTime", "startTime"), moved.keys.sorted())
        assertEquals("2026-09-30T19:00:00Z", moved["startTime"]!!.jsonPrimitive.content)
    }

    @Test
    fun zoneGuestsAndProblems() {
        var draft = EventDraft.newOn(LocalDate.of(2026, 9, 30), date("2026-09-25T00:00:00Z"), pacific)
        draft = draft.withTimeZone(ZoneId.of("America/New_York"))
        assertEquals("09:00 EDT", date("2026-09-30T13:00:00Z"), draft.start)
        assertEquals(EventDraft.DEFAULT_LENGTH, Duration.between(draft.start, draft.end))
        draft = draft.addingAttendee(" ada@example.com ")!!
        assertNull("case-insensitive duplicate", draft.addingAttendee("ADA@example.com"))
        assertNull(draft.addingAttendee("ada"))
        assertNull(draft.addingAttendee("ada@localhost"))
        assertEquals(listOf("ada@example.com"), draft.attendees)
        assertEquals("An event needs a title.", draft.problem)
        draft = draft.copy(title = "Review")
        assertNull(draft.problem)
        assertEquals("The event ends before it starts.", draft.copy(end = draft.start.minusSeconds(60)).problem)
        assertNull("an all-day event may end on its start day", draft.copy(allDay = true, end = draft.start).problem)
    }
}

class ICSImportTest {
    private fun ics(vararg lines: String) = (listOf("BEGIN:VCALENDAR", "VERSION:2.0") + lines + "END:VCALENDAR").joinToString("\r\n")

    @Test
    fun aZonedInviteKeepsItsZoneAndText() {
        val draft = ICSImport.draft(ics(
            "METHOD:REQUEST", "BEGIN:VEVENT", "SUMMARY:Design review\\, round 2", "LOCATION:Room 4\\; east wing",
            "DESCRIPTION:Agenda:\\n1. Mocks\\n2. Copy", "DTSTART;TZID=America/New_York:20261005T100000",
            "DTEND;TZID=America/New_York:20261005T113000", "ATTENDEE;CN=\"Lovelace, Ada\";ROLE=REQ-PARTICIPANT:mailto:ada@example.com",
            "ATTENDEE:MAILTO:grace@example.com", "BEGIN:VALARM", "DESCRIPTION:Reminder", "TRIGGER:-PT15M", "END:VALARM", "END:VEVENT",
        ), pacific)
        assertEquals("Design review, round 2", draft.title)
        assertEquals("Room 4; east wing", draft.location)
        assertEquals("the alarm's DESCRIPTION is not the event's", "Agenda:\n1. Mocks\n2. Copy", draft.notes)
        assertEquals(date("2026-10-05T14:00:00Z"), draft.start)
        assertEquals(date("2026-10-05T15:30:00Z"), draft.end)
        assertEquals("America/New_York", draft.timeZone.id)
        assertEquals(listOf("ada@example.com", "grace@example.com"), draft.attendees)
        assertEquals(RepeatOption.Never, draft.repeatOption)
    }

    @Test
    fun utcFloatingDurationAndAllDay() {
        val utcDraft = ICSImport.draft(ics("BEGIN:VEVENT", "SUMMARY:Call", "DTSTART:20261005T170000Z", "DURATION:PT45M", "END:VEVENT"), pacific)
        assertEquals(date("2026-10-05T17:45:00Z"), utcDraft.end)
        assertEquals("America/Los_Angeles", utcDraft.timeZone.id)
        val floating = ICSImport.draft(ics("BEGIN:VEVENT", "SUMMARY:Lunch", "DTSTART:20261005T120000", "DTEND:20261005T130000", "END:VEVENT"), pacific)
        assertEquals(date("2026-10-05T19:00:00Z"), floating.start)
        val offsite = ICSImport.draft(ics("BEGIN:VEVENT", "SUMMARY:Offsite", "DTSTART;VALUE=DATE:20261012", "DTEND;VALUE=DATE:20261014", "END:VEVENT"), pacific)
        assertTrue(offsite.allDay)
        assertEquals("2026-10-12T00:00:00Z" to "2026-10-13T23:59:59Z", offsite.wireTimes)
        val oneDay = ICSImport.draft(ics("BEGIN:VEVENT", "SUMMARY:Holiday", "DTSTART;VALUE=DATE:20261012", "END:VEVENT"), pacific)
        assertEquals("2026-10-12T23:59:59Z", oneDay.wireTimes.second)
    }

    @Test
    fun outlookZonesFoldedLinesAndTheSeriesNotAnOverride() {
        val draft = ICSImport.draft(ics(
            "BEGIN:VEVENT", "SUMMARY:Quarterly planning with the whole", "  team",
            "DTSTART;TZID=W. Europe Standard Time:20261005T090000", "DTEND;TZID=W. Europe Standard Time:20261005T100000",
            "RRULE:FREQ=WEEKLY;BYDAY=MO", "END:VEVENT",
        ), pacific)
        assertEquals("Quarterly planning with the whole team", draft.title)
        assertEquals("Europe/Berlin", draft.timeZone.id)
        assertEquals(date("2026-10-05T07:00:00Z"), draft.start)
        assertEquals(RepeatOption.Custom("FREQ=WEEKLY;BYDAY=MO"), draft.repeatOption)
        val series = ICSImport.draft(ics(
            "BEGIN:VEVENT", "SUMMARY:Moved one", "RECURRENCE-ID:20261012T170000Z", "DTSTART:20261013T170000Z", "END:VEVENT",
            "BEGIN:VEVENT", "SUMMARY:Standup", "RRULE:FREQ=DAILY", "DTSTART:20261005T170000Z", "END:VEVENT",
        ), pacific)
        assertEquals("Standup", series.title)
        assertEquals(RepeatOption.Daily, series.repeatOption)
    }

    @Test
    fun failuresAndDurations() {
        fun fails(text: String, kind: Class<*>) {
            try {
                ICSImport.draft(text, pacific); fail()
            } catch (e: ICSImport.Failure) {
                assertEquals(kind, e.javaClass)
            }
        }
        fails("not a calendar", ICSImport.Failure.Unreadable::class.java)
        fails(ics("BEGIN:VTODO", "SUMMARY:x", "END:VTODO"), ICSImport.Failure.NoEvent::class.java)
        fails(ics("METHOD:CANCEL", "BEGIN:VEVENT", "SUMMARY:x", "DTSTART:20261005T170000Z", "END:VEVENT"), ICSImport.Failure.Cancelled::class.java)
        assertEquals(Duration.ofSeconds(5400), ICSImport.duration("PT1H30M"))
        assertEquals(Duration.ofDays(7), ICSImport.duration("P1W"))
        assertEquals(Duration.ofSeconds(-900), ICSImport.duration("-PT15M"))
        assertNull(ICSImport.duration("1H"))
    }
}

class PlansTest {
    @Test
    fun notificationsAreTheNearestOpenFutureOnesAndOnlyChangesAreSent() {
        val now = date("2026-10-01T12:00:00Z")
        val reminders = listOf(
            Reminder("past", "Past", date("2026-10-01T11:00:00Z")),
            Reminder("done", "Done", date("2026-10-02T11:00:00Z"), completed = true),
            Reminder("b", "B", date("2026-10-03T11:00:00Z")),
            Reminder("a", "A", date("2026-10-02T11:00:00Z")),
        )
        val plan = NotificationPlan.plan(reminders, now)
        assertEquals(listOf("a", "b"), plan.map { it.reminderId })
        assertEquals(60, NotificationPlan.plan((0 until 80).map { Reminder("r$it", "R", now.plusSeconds(60L * (it + 1))) }, now).size)
        val (add, remove) = NotificationPlan.changes(listOf(plan[0].identifier, "reminder.old.1", "snooze.x", "arrival.t"), plan)
        assertEquals(listOf("b"), add.map { it.reminderId })
        assertEquals("a snooze and an arrival are left alone", listOf("reminder.old.1"), remove)
        assertTrue(NotificationPlan.identifier("a", date("2026-10-02T11:00:00Z")) != NotificationPlan.identifier("a", date("2026-10-02T12:00:00Z")))
    }

    @Test
    fun tasksAtOneSavedPlaceShareARegionAndTheNearestAreWatched() {
        val home = TaskPlace("home", "Home", GeoPoint.of(51.5, -0.12, 150))
        val tasks = listOf(
            CalendarTask("t1", "Water plants", geofence = TaskGeofence.Place("home")),
            CalendarTask("t2", "Bins", geofence = TaskGeofence.Place("home")),
            CalendarTask("t3", "Milk", location = "Shop", geofence = TaskGeofence.Point(GeoPoint.of(51.6, -0.1))),
            CalendarTask("t4", "Done", done = true, geofence = TaskGeofence.Place("home")),
            CalendarTask("t5", "Gone place", geofence = TaskGeofence.Place("deleted")),
        )
        val regions = GeofencePlan.candidates(tasks, listOf(home))
        assertEquals(listOf("ncal.geo.place.home", "ncal.geo.task.t3"), regions.map { it.identifier })
        assertEquals(listOf("t1", "t2"), regions[0].alerts.map { it.taskId })
        assertEquals("Shop", regions[1].name)
        val near = GeofencePlan.nearest(regions, GeoPoint.of(51.6, -0.1), limit = 1)
        assertEquals(listOf("ncal.geo.task.t3"), near.map { it.identifier })
        val (remove, add) = GeofencePlan.changes(mapOf("ncal.geo.place.home" to home.point, "ncal.geo.task.old" to home.point, "other.app" to home.point), regions)
        assertEquals(listOf("ncal.geo.task.old"), remove)
        assertEquals(listOf("ncal.geo.task.t3"), add.map { it.identifier })
        val nearby = NearbyTasks.list(tasks, listOf(home), GeoPoint.of(51.5, -0.12), 1000.0)
        assertEquals(listOf("t1", "t2"), nearby.map { it.task.id })
        assertEquals("Here", NearbyTasks.format(0.0))
        assertEquals("350 m", NearbyTasks.format(350.0))
        assertEquals("1.2 km", NearbyTasks.format(1234.0))
        assertEquals("2 km", NearbyTasks.format(2000.0))
        assertEquals("the minimum radius", 100, GeoPoint.of(0.0, 0.0, 10).radius)
    }

    @Test
    fun taskFiltersTagsAndOccurrences() {
        val now = date("2026-10-05T19:00:00Z") // Monday, noon in LA
        val tasks = listOf(
            CalendarTask("over", "Taxes", dueDate = date("2026-10-01T00:00:00Z"), tags = listOf("money"), priority = 1),
            CalendarTask("today", "Café visit", dueDate = date("2026-10-05T00:00:00Z")),
            CalendarTask("week", "Plan", dueDate = date("2026-10-09T00:00:00Z"), notes = "Quarterly"),
            CalendarTask("none", "Someday"),
            CalendarTask("done", "Finished", done = true, dueDate = date("2026-10-01T00:00:00Z")),
        )
        fun ids(f: TaskFilter) = f.apply(tasks, now, pacific).map { it.id }
        assertEquals(tasks.map { it.id }, ids(TaskFilter()))
        assertEquals("a done task is never overdue", listOf("over"), ids(TaskFilter(due = TaskFilter.Due.OVERDUE)))
        assertEquals(listOf("today"), ids(TaskFilter(due = TaskFilter.Due.TODAY)))
        assertEquals(listOf("today", "week"), ids(TaskFilter(due = TaskFilter.Due.WEEK)))
        assertEquals(listOf("none"), ids(TaskFilter(due = TaskFilter.Due.NONE)))
        assertEquals(listOf("today"), ids(TaskFilter(text = "cafe")))
        assertEquals(listOf("week"), ids(TaskFilter(text = "QUARTER")))
        assertEquals(listOf("over"), ids(TaskFilter(text = "#mon")))
        assertEquals(listOf("over"), ids(TaskFilter(priority = 1, tags = setOf("money"))))
        assertFalse(TaskFilter(showDone = false).apply(tasks, now, pacific).any { it.done })
        assertTrue(TaskFilter(text = "x").isActive && !TaskFilter(text = "x").hasMenuFilters)

        assertEquals(listOf("errands", "home"), TaskTags.split("#Errands, home  #errands"))
        assertEquals(listOf("a", "b"), TaskTags.normalize(listOf("#B", "a,b")))
        assertEquals(listOf("home", "homework", "work"), TaskTags.suggestions("", listOf("home", "homework", "work"), emptyList()))
        assertEquals(listOf("home", "homework", "chome"), TaskTags.suggestions("#hom", listOf("chome", "home", "homework"), emptyList()))

        val drawn = TaskOccurrences.occurrences(
            tasks + CalendarTask("timed", "Call", dueDate = date("2026-10-05T22:00:00Z"), dueHasTime = true, estimateMinutes = 45) +
                CalendarTask("scheduled", "On calendar", dueDate = date("2026-10-05T00:00:00Z"), eventId = "e"),
            date("2026-10-05T00:00:00Z"), date("2026-10-11T23:59:59Z"),
        )
        assertEquals(listOf("today", "week", "timed"), drawn.map { it.task!!.id })
        assertEquals(date("2026-10-05T22:45:00Z"), drawn.last().end)
        assertFalse(drawn.last().event.allDay)
    }

    @Test
    fun anUntouchedFieldIsOmittedAndAClearedOneIsNull() {
        val body = UpdateTaskRequest(title = "x", notes = Patch.Clear).toJson()
        assertEquals(setOf("title", "notes"), body.keys)
        assertTrue(body["notes"] is JsonNull)
        val geo = UpdateTaskRequest().withGeofence(TaskGeofence.Place("p")).toJson()
        assertEquals(setOf("geoPlaceId", "geoLat", "geoLng", "geoRadiusM"), geo.keys)
    }

    @Test
    fun overlappingBlocksSitSideBySide() {
        val day = LocalDate.of(2026, 10, 5)
        fun at(id: String, start: String, end: String) = CalendarEvent(id = id, title = id, start = date(start), end = date(end)).let { EventOccurrence(it, it.start, it.end) }
        val placed = TimeGridLayout.layout(listOf(
            at("a", "2026-10-05T16:00:00Z", "2026-10-05T17:00:00Z"),
            at("b", "2026-10-05T16:30:00Z", "2026-10-05T17:30:00Z"),
            at("c", "2026-10-05T19:00:00Z", "2026-10-05T19:05:00Z"),
        ), day, pacific)
        assertEquals(listOf(0, 1, 0), placed.map { it.column })
        assertEquals(listOf(2, 2, 1), placed.map { it.columns })
        assertEquals("09:00 PDT", 540.0, placed[0].startMinute, 0.0)
        assertEquals("a short one is at least 24 minutes", 24.0, placed[2].endMinute - placed[2].startMinute, 0.0)
    }

    @Test
    fun widgetsLeaveOutHiddenCalendarsAndCarryColours() {
        val now = date("2026-10-05T19:00:00Z")
        val shown = calendar("shown", color = "#e11d48")
        val hidden = calendar("hidden", visible = false)
        val occurrences = listOf(event("a", "shown", start = "2026-10-05T20:00:00Z"), event("b", "hidden", start = "2026-10-05T21:00:00Z"),
            event("past", "shown", start = "2026-10-04T20:00:00Z"), event("far", "shown", start = "2026-10-25T20:00:00Z"))
            .map { EventOccurrence(it, it.start, it.end) }
        val snapshot = WidgetSnapshot.build(occurrences, now, pacific, EventFilter(CalendarRules(listOf(shown, hidden)), SourceFilter.ALL))
        assertEquals(listOf("a"), snapshot.events.map { it.title })
        assertEquals("#e11d48", snapshot.events.single().color)
        assertTrue(snapshot.busyDays.containsAll(listOf("2026-10-04", "2026-10-05", "2026-10-25")))
        assertEquals("a", snapshot.next(now)?.title)
        val json = CalendarJson.encodeToString(WidgetSnapshot.serializer(), snapshot)
        assertFalse("nothing the widgets don't draw", json.contains("description") || json.contains("attendees"))
        assertEquals(snapshot, CalendarJson.decodeFromString(WidgetSnapshot.serializer(), json))
    }
}
