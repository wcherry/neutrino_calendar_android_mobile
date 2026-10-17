package com.neutrino.calendar.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * One task from `GET /api/v1/calendar/tasks` (`TaskResponse` in `neutrino/src/calendar/tasks/dto.rs`).
 *
 * Tasks are one flat list in `position` order, as on the web. The server still has task *lists*,
 * but the web stopped grouping by them because tags replace them, so this app never shows them.
 */
data class CalendarTask(
    val id: String,
    val title: String,
    val notes: String? = null,
    val done: Boolean = false,
    /**
     * A date, not an instant: the web stores `<date>T00:00:00Z` and reads the date part back, so it
     * is read in UTC and means the same day everywhere — unless [dueHasTime].
     */
    val dueDate: Instant? = null,
    val position: Int = 0,
    /** The calendar event this task is scheduled as, if it is on the calendar. */
    val eventId: String? = null,
    /** `dueDate` is an instant ("^fri 3pm") rather than a `<day>T00:00:00Z` date. */
    val dueHasTime: Boolean = false,
    /** 1 (high) to 3 (low). */
    val priority: Int? = null,
    /** Lowercase, without the `#`. */
    val tags: List<String> = emptyList(),
    /** An RRULE body. Completing the task creates the next occurrence as a new task. */
    val recurrenceRule: String? = null,
    val repeatAfterCompletion: Boolean = false,
    val estimateMinutes: Int? = null,
    val location: String? = null,
    /** Where the task reminds you on arrival, if anywhere: a saved place, or a one-off point. */
    val geofence: TaskGeofence? = null,
) {
    /** The due date as the calendar day it names: the UTC date, or for a timed due the local day. */
    fun dueDay(context: CalendarContext): LocalDate? {
        val due = dueDate ?: return null
        return if (dueHasTime) context.day(due) else due.atZone(ZoneOffset.UTC).toLocalDate()
    }

    companion object {
        fun from(wire: TaskWire): CalendarTask = CalendarTask(
            id = wire.id,
            title = wire.title,
            notes = wire.notes?.takeIf { it.isNotEmpty() },
            done = wire.done ?: false,
            dueDate = ServerDate.parse(wire.dueDate),
            position = wire.position ?: 0,
            eventId = wire.eventId,
            dueHasTime = wire.dueHasTime ?: false,
            priority = wire.priority,
            tags = wire.tags ?: emptyList(),
            recurrenceRule = wire.recurrenceRule,
            repeatAfterCompletion = wire.repeatAfterCompletion ?: false,
            estimateMinutes = wire.estimateMinutes,
            location = wire.location,
            // A saved place wins over a point, though the server never stores both.
            geofence = when {
                wire.geoPlaceId != null -> TaskGeofence.Place(wire.geoPlaceId)
                wire.geoLat != null && wire.geoLng != null ->
                    TaskGeofence.Point(GeoPoint(wire.geoLat, wire.geoLng, wire.geoRadiusM ?: GeoPoint.DEFAULT_RADIUS))
                else -> null
            },
        )

        /** The wire form of a picked day: `2026-09-24T00:00:00Z`, as the web writes it. */
        fun dueDateValue(day: LocalDate): String = "${day}T00:00:00Z"
    }
}

/** Absent from servers older than Smart Add and geofencing, so everything past the title is optional. */
@Serializable
data class TaskWire(
    val id: String,
    val title: String,
    val notes: String? = null,
    val done: Boolean? = null,
    val dueDate: String? = null,
    val position: Int? = null,
    val eventId: String? = null,
    val dueHasTime: Boolean? = null,
    val priority: Int? = null,
    val tags: List<String>? = null,
    val recurrenceRule: String? = null,
    val repeatAfterCompletion: Boolean? = null,
    val estimateMinutes: Int? = null,
    val location: String? = null,
    val geoPlaceId: String? = null,
    val geoLat: Double? = null,
    val geoLng: Double? = null,
    val geoRadiusM: Int? = null,
    /** On the answer to completing a repeating task: the task created for its next occurrence. */
    val nextTask: TaskWire? = null,
)

/** Tag rules shared by the task editor and the requests it sends. */
object TaskTags {
    /**
     * What the server stores: trimmed, lowercase, without a leading `#`, de-duplicated and sorted
     * (`normalize_tags` in `neutrino/src/calendar/tasks/service.rs`). Whitespace and commas separate.
     */
    fun normalize(tags: List<String>): List<String> = tags.flatMap(::split).toSortedSet().toList()

    /** "#Errands, home" → ["errands", "home"], in the order typed. */
    fun split(text: String): List<String> {
        val seen = LinkedHashSet<String>()
        text.split(Regex("[\\s,]+")).forEach { raw ->
            val tag = raw.trimStart('#').lowercase()
            if (tag.isNotEmpty()) seen += tag
        }
        return seen.toList()
    }

    /** Every tag on `tasks`, most used first, then alphabetically. */
    fun all(tasks: List<CalendarTask>): List<String> {
        val counts = tasks.flatMap { it.tags }.groupingBy { it }.eachCount()
        return counts.keys.sortedWith(compareByDescending<String> { counts[it] }.thenBy { it })
    }

    /**
     * The tags in `known` worth offering for `query`, leaving out those `chosen`: those starting with
     * it first, then those containing it. An empty query offers everything.
     */
    fun suggestions(query: String, known: List<String>, chosen: List<String>): List<String> {
        val q = split(query).lastOrNull() ?: ""
        val open = known.filter { it !in chosen }
        if (q.isEmpty()) return open
        return open.filter { it.startsWith(q) } + open.filter { !it.startsWith(q) && it.contains(q) }
    }
}

/** Only the fields that are set are encoded, so a plain title is still just `{"title": …}`. */
@Serializable
data class CreateTaskRequest(
    val title: String,
    val notes: String? = null,
    val dueDate: String? = null,
    val dueHasTime: Boolean? = null,
    val startDate: String? = null,
    val startHasTime: Boolean? = null,
    val priority: Int? = null,
    val tags: List<String>? = null,
    val recurrenceRule: String? = null,
    val repeatAfterCompletion: Boolean? = null,
    val estimateMinutes: Int? = null,
    val location: String? = null,
    val geoPlaceId: String? = null,
    val geoLat: Double? = null,
    val geoLng: Double? = null,
    val geoRadiusM: Int? = null,
) : JsonBody {
    override fun toJson(): JsonObject = encodeObject(serializer(), this)
}

data class UpdateTaskRequest(
    val title: String? = null,
    val notes: Patch<String> = Patch.Keep,
    val done: Boolean? = null,
    val dueDate: Patch<String> = Patch.Keep,
    /** Sent with a new `dueDate`: a date picked on the task screen is a day, never a time. */
    val dueHasTime: Boolean? = null,
    /** The zone a repeating task is stepped in when this completes it. The server reads it only then. */
    val timezone: String? = null,
    /** The task's whole tag set, replacing the old one; absent leaves the tags alone. */
    val tags: List<String>? = null,
    val location: Patch<String> = Patch.Keep,
    val geoPlaceId: Patch<String> = Patch.Keep,
    val geoLat: Patch<Double> = Patch.Keep,
    val geoLng: Patch<Double> = Patch.Keep,
    val geoRadiusM: Patch<Int> = Patch.Keep,
) : JsonBody {
    val isEmpty: Boolean get() = this == UpdateTaskRequest()

    /** Sets, replaces or clears the arrival geofence. All four fields are sent, so one kind replaces the other. */
    fun withGeofence(geofence: TaskGeofence?): UpdateTaskRequest = when (geofence) {
        is TaskGeofence.Place -> copy(geoPlaceId = Patch.Set(geofence.id), geoLat = Patch.Clear, geoLng = Patch.Clear, geoRadiusM = Patch.Clear)
        is TaskGeofence.Point -> copy(
            geoPlaceId = Patch.Clear, geoLat = Patch.Set(geofence.point.latitude),
            geoLng = Patch.Set(geofence.point.longitude), geoRadiusM = Patch.Set(geofence.point.radius),
        )
        null -> copy(geoPlaceId = Patch.Clear, geoLat = Patch.Clear, geoLng = Patch.Clear, geoRadiusM = Patch.Clear)
    }

    override fun toJson(): JsonObject = buildJsonObject {
        title?.let { put("title", it) }
        done?.let { put("done", it) }
        dueHasTime?.let { put("dueHasTime", it) }
        timezone?.let { put("timezone", it) }
        tags?.let { put("tags", jsonValue(it)) }
        patch("notes", notes)
        patch("dueDate", dueDate)
        patch("location", location)
        patch("geoPlaceId", geoPlaceId)
        patch("geoLat", geoLat)
        patch("geoLng", geoLng)
        patch("geoRadiusM", geoRadiusM)
    }
}

@Serializable
data class ReorderTasksRequest(val taskIds: List<String>) : JsonBody {
    override fun toJson(): JsonObject = encodeObject(serializer(), this)
}

/** Puts a task on the calendar, or moves the event it is already on. */
@Serializable
data class ScheduleTaskRequest(
    val startTime: String,
    val endTime: String,
    val allDay: Boolean,
    val timezone: String? = null,
) : JsonBody {
    override fun toJson(): JsonObject = encodeObject(serializer(), this)

    companion object {
        /**
         * The web's shapes: an all-day slot is dates, `T00:00:00Z` to `T23:59:59Z` on the last day,
         * as an all-day event is everywhere; a timed one is two instants and the zone it was set in.
         */
        fun of(start: Instant, end: Instant, allDay: Boolean, context: CalendarContext): ScheduleTaskRequest =
            if (allDay) {
                ScheduleTaskRequest("${context.day(start)}T00:00:00Z", "${context.day(end)}T23:59:59Z", true, null)
            } else {
                ScheduleTaskRequest(ServerDate.format(start), ServerDate.format(end), false, context.zone.id)
            }
    }
}
