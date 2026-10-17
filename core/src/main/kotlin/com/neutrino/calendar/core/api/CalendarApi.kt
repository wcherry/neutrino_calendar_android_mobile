package com.neutrino.calendar.core.api

import com.neutrino.calendar.core.logic.CreateEventRequest
import com.neutrino.calendar.core.logic.SplitEventRequest
import com.neutrino.calendar.core.logic.UpdateEventRequest
import com.neutrino.calendar.core.model.Attachment
import com.neutrino.calendar.core.model.CalendarEvent
import com.neutrino.calendar.core.model.CalendarJson
import com.neutrino.calendar.core.model.CalendarTask
import com.neutrino.calendar.core.model.CreateAttachmentRequest
import com.neutrino.calendar.core.model.CreateCalendarRequest
import com.neutrino.calendar.core.model.CreateReminderRequest
import com.neutrino.calendar.core.model.CreateTaskRequest
import com.neutrino.calendar.core.model.EventChangesWire
import com.neutrino.calendar.core.model.EventWire
import com.neutrino.calendar.core.model.JsonBody
import com.neutrino.calendar.core.model.ListAttachmentsWire
import com.neutrino.calendar.core.model.ListCalendarsWire
import com.neutrino.calendar.core.model.ListEventsWire
import com.neutrino.calendar.core.model.ListRemindersWire
import com.neutrino.calendar.core.model.Reminder
import com.neutrino.calendar.core.model.ReminderOccurrenceRequest
import com.neutrino.calendar.core.model.ReminderOccurrenceWire
import com.neutrino.calendar.core.model.ReminderWire
import com.neutrino.calendar.core.model.ReorderTasksRequest
import com.neutrino.calendar.core.model.SaveTaskPlaceRequest
import com.neutrino.calendar.core.model.ScheduleTaskRequest
import com.neutrino.calendar.core.model.ServerDate
import com.neutrino.calendar.core.model.SkipReminderRequest
import com.neutrino.calendar.core.model.SkipReminderWire
import com.neutrino.calendar.core.model.TaskPlaceRecord
import com.neutrino.calendar.core.model.TaskPlacesWire
import com.neutrino.calendar.core.model.TaskWire
import com.neutrino.calendar.core.model.UpdateCalendarRequest
import com.neutrino.calendar.core.model.UpdateReminderRequest
import com.neutrino.calendar.core.model.UpdateTaskRequest
import com.neutrino.calendar.core.model.UserCalendar
import com.neutrino.shared.core.api.ApiException
import com.neutrino.shared.core.api.AuthClient
import com.neutrino.shared.core.api.NeutrinoApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.UUID

/** The request never got an answer: offline, or the server unreachable. The one failure worth queuing a write for. */
val Throwable.isNetwork: Boolean get() = this is ApiException.Network

/** The server answered 404: the thing is gone, deleted here or elsewhere. */
val Throwable.isNotFound: Boolean get() = (this as? ApiException.Http)?.status == 404

val Throwable.httpStatus: Int? get() = (this as? ApiException.Http)?.status

/**
 * Authorized requests to `/api/v1/calendar`, on top of the shared client's token plumbing, keys and
 * Drive endpoints (attachments are Drive files). The iOS app's `CalendarAPIClient`, endpoint for
 * endpoint.
 */
open class CalendarApi(http: OkHttpClient, auth: AuthClient) : NeutrinoApi(http, auth) {

    /**
     * Sent as `X-Neutrino-Client-Id` on every request and compared with a live signal's
     * `originClientId`, so the app can skip the echo of its own writes. New each process: it names
     * this running app, not the device.
     */
    val clientId: String = UUID.randomUUID().toString()

    override fun decorate(builder: Request.Builder): Request.Builder = builder.header(CLIENT_ID_HEADER, clientId)

    private suspend fun <T> read(serializer: KSerializer<T>, path: String): T = decode(serializer, text(get(path)))

    private suspend fun <T> write(serializer: KSerializer<T>, method: String, path: String, body: JsonBody? = null): T =
        decode(serializer, send(method, path, body))

    private suspend fun send(method: String, path: String, body: JsonBody? = null): String =
        text(method(method, path, body?.toJson()?.toString()))

    private fun <T> decode(serializer: KSerializer<T>, body: String): T = try {
        CalendarJson.decodeFromString(serializer, body)
    } catch (e: Exception) {
        throw ApiException.Decode("${e.message?.take(200)}")
    }

    private fun events(wires: List<EventWire>) = wires.mapNotNull(CalendarEvent::from)

    // ── Events ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Events overlapping `[from, to]`, plus every recurring event that starts by `to` (its later
     * occurrences may fall in the range), and every exception of those. Expansion is the caller's.
     */
    suspend fun events(from: Instant, to: Instant): List<CalendarEvent> {
        val u = url("/calendar/events").newBuilder()
            .addQueryParameter("from", ServerDate.format(from))
            .addQueryParameter("to", ServerDate.format(to))
            .addQueryParameter("exceptions", "true")
            .build()
        return events(decode(ListEventsWire.serializer(), text(get(u))).events)
    }

    /** What changed since `since`; with no cursor, only a cursor to start from. */
    suspend fun eventChanges(since: String?): EventChanges {
        val u = url("/calendar/events/changes").newBuilder().apply {
            since?.let { addQueryParameter("since", it) }
            addQueryParameter("exceptions", "true")
        }.build()
        val wire = decode(EventChangesWire.serializer(), text(get(u)))
        return EventChanges(events(wire.events), wire.deletedIds, wire.cursor, wire.fullResyncRequired)
    }

    suspend fun event(id: String): CalendarEvent =
        CalendarEvent.from(read(EventWire.serializer(), "/calendar/events/$id")) ?: throw ApiException.Decode("event times")

    suspend fun createEvent(request: CreateEventRequest): CalendarEvent =
        CalendarEvent.from(write(EventWire.serializer(), "POST", "/calendar/events", request)) ?: throw ApiException.Decode("event times")

    /** A partial update, despite the verb: absent fields are left alone. */
    suspend fun updateEvent(id: String, request: UpdateEventRequest): CalendarEvent =
        CalendarEvent.from(write(EventWire.serializer(), "PUT", "/calendar/events/$id", request)) ?: throw ApiException.Decode("event times")

    /** Deletes the event, and with it every occurrence of a repeating one. */
    suspend fun deleteEvent(id: String) {
        send("DELETE", "/calendar/events/$id")
    }

    /** "Delete this and following events": ends the series before the occurrence. */
    suspend fun deleteEvent(id: String, fromOccurrence: Instant) {
        val u = url("/calendar/events/$id").newBuilder().addQueryParameter("fromOccurrence", ServerDate.format(fromOccurrence)).build()
        text { Request.Builder().url(u).delete() }
    }

    /** "This event": saves the changes to one occurrence only, and answers with its exception. */
    suspend fun editOccurrence(series: String, originalStart: Instant, request: UpdateEventRequest): CalendarEvent =
        CalendarEvent.from(write(EventWire.serializer(), "PUT", occurrencePath(series, originalStart), request)) ?: throw ApiException.Decode("event times")

    /** "Delete this event". */
    suspend fun cancelOccurrence(series: String, originalStart: Instant) {
        send("DELETE", occurrencePath(series, originalStart))
    }

    /** "This and following": ends the series before the occurrence and starts a new one there. Answers with the new series. */
    suspend fun splitEvent(series: String, request: SplitEventRequest): CalendarEvent =
        CalendarEvent.from(write(EventWire.serializer(), "POST", "/calendar/events/$series/split", request)) ?: throw ApiException.Decode("event times")

    /** Asks the server to pull from every connected provider now. Answers once the pull is done. */
    suspend fun triggerProviderSync() {
        send("POST", "/calendar/sync/trigger", EMPTY)
    }

    suspend fun eventAttachments(eventId: String): List<Attachment> =
        read(ListAttachmentsWire.serializer(), "/calendar/events/$eventId/attachments").attachments

    suspend fun addEventAttachment(eventId: String, request: CreateAttachmentRequest): Attachment =
        write(Attachment.serializer(), "POST", "/calendar/events/$eventId/attachments", request)

    suspend fun deleteEventAttachment(eventId: String, attachmentId: String) {
        send("DELETE", "/calendar/events/$eventId/attachments/$attachmentId")
    }

    // ── Reminders ───────────────────────────────────────────────────────────────────────────────

    /** Every reminder the user has, linked or not. */
    suspend fun reminders(): List<Reminder> = read(ListRemindersWire.serializer(), "/calendar/reminders").reminders.mapNotNull(Reminder::from)

    suspend fun reminder(id: String): Reminder = reminder(read(ReminderWire.serializer(), "/calendar/reminders/$id"))

    suspend fun createReminder(request: CreateReminderRequest): Reminder =
        reminder(write(ReminderWire.serializer(), "POST", "/calendar/reminders", request))

    /** The answer, not the request, is the new state: completing a repeating reminder comes back open at its next time. */
    suspend fun updateReminder(id: String, request: UpdateReminderRequest): Reminder =
        reminder(write(ReminderWire.serializer(), "PATCH", "/calendar/reminders/$id", request))

    suspend fun deleteReminder(id: String) {
        send("DELETE", "/calendar/reminders/$id")
    }

    /** "Delete this reminder" for a repeating one: moves it on, or deletes it once its rule has run out. */
    suspend fun skipReminder(id: String, timezone: String): Reminder? =
        write(SkipReminderWire.serializer(), "POST", "/calendar/reminders/$id/skip", SkipReminderRequest(timezone)).series?.let(Reminder::from)

    /** "Edit this reminder" for a repeating one: the occurrence becomes a one-off with the changes, and the series moves on. */
    suspend fun editReminderOccurrence(id: String, request: ReminderOccurrenceRequest): Pair<Reminder, Reminder?> {
        val wire = write(ReminderOccurrenceWire.serializer(), "POST", "/calendar/reminders/$id/occurrence", request)
        return reminder(wire.reminder) to wire.series?.let(Reminder::from)
    }

    private fun reminder(wire: ReminderWire) = Reminder.from(wire) ?: throw ApiException.Decode("reminder due time")

    // ── Calendars ───────────────────────────────────────────────────────────────────────────────

    /** Every calendar, the default first, hidden ones included: hiding is the client's to apply. */
    suspend fun calendars(): List<UserCalendar> = read(ListCalendarsWire.serializer(), "/calendar/calendars").calendars

    /** 409 for a country already added. */
    suspend fun createCalendar(request: CreateCalendarRequest): UserCalendar =
        write(UserCalendar.serializer(), "POST", "/calendar/calendars", JsonBodyOf(CreateCalendarRequest.serializer(), request))

    suspend fun updateCalendar(id: String, request: UpdateCalendarRequest): UserCalendar =
        write(UserCalendar.serializer(), "PATCH", "/calendar/calendars/$id", JsonBodyOf(UpdateCalendarRequest.serializer(), request))

    /** Deletes the calendar and its events. 400 for the default calendar, 409 for a provider's while connected. */
    suspend fun deleteCalendar(id: String) {
        send("DELETE", "/calendar/calendars/$id")
    }

    // ── Tasks ───────────────────────────────────────────────────────────────────────────────────

    /** Every task, in `position` order. A bare array, unlike the other list endpoints. */
    suspend fun tasks(): List<CalendarTask> = read(ListSerializer(TaskWire.serializer()), "/calendar/tasks").map(CalendarTask::from)

    suspend fun createTask(request: CreateTaskRequest): CalendarTask =
        CalendarTask.from(write(TaskWire.serializer(), "POST", "/calendar/tasks", request))

    /** The task, and the next occurrence the server creates when this completes a repeating one. */
    suspend fun updateTask(id: String, request: UpdateTaskRequest): Pair<CalendarTask, CalendarTask?> {
        val wire = write(TaskWire.serializer(), "PATCH", "/calendar/tasks/$id", request)
        return CalendarTask.from(wire) to wire.nextTask?.let(CalendarTask::from)
    }

    /** Sets the order of the open tasks named, first to last; done tasks keep their positions. */
    suspend fun reorderTasks(ids: List<String>) {
        send("POST", "/calendar/tasks/reorder", ReorderTasksRequest(ids))
    }

    /** Puts the task on the calendar, or moves the event it is already on; answers with the event. */
    suspend fun scheduleTask(id: String, request: ScheduleTaskRequest): CalendarEvent =
        CalendarEvent.from(write(EventWire.serializer(), "POST", "/calendar/tasks/$id/event", request)) ?: throw ApiException.Decode("event times")

    /** Takes the task off the calendar, deleting its event; answers with the task. */
    suspend fun unscheduleTask(id: String): CalendarTask = CalendarTask.from(write(TaskWire.serializer(), "DELETE", "/calendar/tasks/$id/event"))

    suspend fun taskAttachments(taskId: String): List<Attachment> =
        read(ListAttachmentsWire.serializer(), "/calendar/tasks/$taskId/attachments").attachments

    suspend fun addTaskAttachment(taskId: String, request: CreateAttachmentRequest): Attachment =
        write(Attachment.serializer(), "POST", "/calendar/tasks/$taskId/attachments", request)

    suspend fun deleteTaskAttachment(taskId: String, attachmentId: String) {
        send("DELETE", "/calendar/tasks/$taskId/attachments/$attachmentId")
    }

    // ── Saved places ────────────────────────────────────────────────────────────────────────────

    /** The user's saved places, as ciphertext; `PlacesService` decrypts them. */
    suspend fun taskPlaces(): List<TaskPlaceRecord> = read(TaskPlacesWire.serializer(), "/calendar/task-places").places

    suspend fun createTaskPlace(request: SaveTaskPlaceRequest): TaskPlaceRecord =
        write(TaskPlaceRecord.serializer(), "POST", "/calendar/task-places", request)

    suspend fun updateTaskPlace(id: String, request: SaveTaskPlaceRequest): TaskPlaceRecord =
        write(TaskPlaceRecord.serializer(), "PATCH", "/calendar/task-places/$id", request)

    /** The server clears the place from every task that used it. */
    suspend fun deleteTaskPlace(id: String) {
        send("DELETE", "/calendar/task-places/$id")
    }

    // ── Offline writes ──────────────────────────────────────────────────────────────────────────

    /** Sends a write that was queued while offline, exactly as it was first attempted. */
    suspend fun replay(method: String, path: String, body: String?) {
        text { Request.Builder().url(absolute(path)).method(method, body?.toRequestBody(JSON) ?: if (method == "DELETE") null else ByteArray(0).toRequestBody(null)) }
    }

    companion object {
        const val CLIENT_ID_HEADER = "X-Neutrino-Client-Id"

        /** An occurrence is named by its series and its start in it, before any edit: its exception's key. */
        fun occurrencePath(series: String, originalStart: Instant) = "/calendar/events/$series/occurrences/${ServerDate.format(originalStart)}"

        /** No connection id: sync every provider connected. */
        private val EMPTY = object : JsonBody {
            override fun toJson() = kotlinx.serialization.json.JsonObject(emptyMap())
        }
    }
}

private class JsonBodyOf<T>(private val serializer: KSerializer<T>, private val value: T) : JsonBody {
    override fun toJson() = com.neutrino.calendar.core.model.encodeObject(serializer, value)
}

/** `EventChangesResponse`, decoded. */
data class EventChanges(val events: List<CalendarEvent>, val deletedIds: List<String>, val cursor: String, val fullResyncRequired: Boolean)
