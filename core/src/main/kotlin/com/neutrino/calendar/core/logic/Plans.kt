package com.neutrino.calendar.core.logic

import com.neutrino.calendar.core.model.CalendarContext
import com.neutrino.calendar.core.model.CalendarTask
import com.neutrino.calendar.core.model.GeoPoint
import com.neutrino.calendar.core.model.Reminder
import com.neutrino.calendar.core.model.TaskGeofence
import com.neutrino.calendar.core.model.TaskPlace
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

/**
 * Which reminders get a local notification, and what has to change among those already scheduled.
 * Pure, so the rules are testable without an alarm manager.
 *
 * The server can't push yet (its reminder engine only logs), so the device is the alarm: every open
 * reminder due in the future is scheduled, event alerts included, since an event's alert is a
 * reminder linked to it.
 */
object NotificationPlan {
    /**
     * The nearest this many are scheduled; the rest are picked up as the nearer ones fire and the plan
     * is made again. iOS caps an app at 64 pending notifications; Android caps an app's alarms at 500,
     * but a plan this size keeps a sync cheap.
     */
    const val LIMIT = 60

    /** Every identifier this app schedules starts with one of these, so a plan never touches another's. */
    const val REMINDER_PREFIX = "reminder."
    const val SNOOZE_PREFIX = "snooze."

    @Serializable
    data class Alert(val identifier: String, val reminderId: String, val fireAtEpochMillis: Long, val title: String) {
        val fireAt: Instant get() = Instant.ofEpochMilli(fireAtEpochMillis)
    }

    /** The identifier carries the due time, so a reminder moved is a different alert. */
    fun identifier(reminderId: String, due: Instant) = "$REMINDER_PREFIX$reminderId.${due.epochSecond}"

    /** Open reminders due after `now`, soonest first, at most `limit`. */
    fun plan(reminders: List<Reminder>, now: Instant, limit: Int = LIMIT): List<Alert> =
        reminders.filter { !it.completed && it.due > now }
            .sortedBy { it.due }
            .take(limit)
            .map { Alert(identifier(it.id, it.due), it.id, it.due.toEpochMilli(), it.title) }

    /** What to add and what to remove, given the identifiers already pending. A snooze stays until it fires. */
    fun changes(pending: List<String>, planned: List<Alert>): Pair<List<Alert>, List<String>> {
        val ours = pending.filter { it.startsWith(REMINDER_PREFIX) }.toSet()
        val wanted = planned.map { it.identifier }.toSet()
        return planned.filter { it.identifier !in ours } to (ours - wanted).sorted()
    }
}

/** One circle the device may watch, and the open tasks that remind you there. */
@Serializable
data class GeofenceRegion(
    /** Stable across launches, so a region already watched is left alone. */
    val identifier: String,
    val center: GeoPoint,
    /** What the arrival alert says you reached: the place's name, or the task's location text. */
    val name: String,
    val alerts: List<Alert>,
) {
    @Serializable
    data class Alert(val taskId: String, val title: String)
}

/**
 * Which geofences the device watches. No location services here, so it tests on its own; the
 * app's `GeofenceMonitor` hands the result to Play services.
 *
 * Every task at a saved place shares that place's region, so "Home" costs one region however many
 * tasks use it. A one-off point is a region of its own.
 */
object GeofencePlan {
    /** Play services monitors at most 100 geofences per app; iOS, 20. Calendar watches nothing else. */
    const val REGION_LIMIT = 100
    /** Prefixes every region this app registers, so nothing else's is ever removed. */
    const val REGION_PREFIX = "ncal.geo."

    fun placeIdentifier(placeId: String) = "${REGION_PREFIX}place.$placeId"
    fun taskIdentifier(taskId: String) = "${REGION_PREFIX}task.$taskId"

    /**
     * Every region an open, geofenced task needs, in the order the tasks are listed. A task at a saved
     * place `places` doesn't hold (deleted, or not decryptable here) has nowhere to be watched.
     */
    fun candidates(tasks: List<CalendarTask>, places: List<TaskPlace>): List<GeofenceRegion> {
        val byId = places.reversed().associateBy { it.id }
        val order = mutableListOf<String>()
        val regions = mutableMapOf<String, Triple<GeoPoint, String, MutableList<GeofenceRegion.Alert>>>()
        for (task in tasks) {
            if (task.done) continue
            val (id, center, name) = when (val g = task.geofence) {
                is TaskGeofence.Place -> {
                    val place = byId[g.id] ?: continue
                    Triple(placeIdentifier(g.id), place.point, place.name)
                }
                is TaskGeofence.Point -> Triple(taskIdentifier(task.id), g.point, task.location ?: "this place")
                null -> continue
            }
            val alert = GeofenceRegion.Alert(task.id, task.title)
            val existing = regions[id]
            if (existing == null) {
                order += id
                regions[id] = Triple(center, name, mutableListOf(alert))
            } else {
                existing.third += alert
            }
        }
        return order.map { id -> regions[id]!!.let { GeofenceRegion(id, it.first, it.second, it.third.toList()) } }
    }

    /**
     * The `limit` regions to watch: the nearest to `location`, or the first listed while the location
     * isn't known. Nearest by the circle's edge, so a large region you are almost inside isn't dropped.
     */
    fun nearest(candidates: List<GeofenceRegion>, location: GeoPoint?, limit: Int = REGION_LIMIT): List<GeofenceRegion> {
        if (location == null || candidates.size <= limit) return candidates.take(limit)
        return candidates.withIndex()
            .sortedWith(compareBy({ location.distanceTo(it.value.center) - it.value.center.radius }, { it.index }))
            .take(limit)
            .map { it.value }
    }

    /** What to tell the platform: regions to stop watching, and those to start. A moved circle is replaced. */
    fun changes(monitored: Map<String, GeoPoint>, planned: List<GeofenceRegion>): Pair<List<String>, List<GeofenceRegion>> {
        val plannedIds = planned.map { it.identifier }.toSet()
        val remove = monitored.keys
            .filter { it.startsWith(REGION_PREFIX) }
            .filter { id -> id !in plannedIds || planned.first { it.identifier == id }.center != monitored[id] }
            .sorted()
        val add = planned.filter { monitored[it.identifier] != it.center }
        return remove to add
    }
}

/**
 * The Tasks tab's Nearby filter: open tasks with a geofence within a range of you, nearest first.
 * On the device, because the server can't read where a saved place is.
 */
object NearbyTasks {
    enum class Range(val meters: Double, val title: String) {
        WALK(500.0, "Within 500 m"), NEIGHBORHOOD(2_000.0, "Within 2 km"), TOWN(10_000.0, "Within 10 km"),
    }

    data class Entry(val task: CalendarTask, /** Metres to the edge of the task's region; 0 inside it. */ val distance: Double)

    fun list(tasks: List<CalendarTask>, places: List<TaskPlace>, from: GeoPoint, within: Double): List<Entry> {
        val byId = places.reversed().associateBy { it.id }
        return tasks.withIndex().mapNotNull { (i, task) ->
            if (task.done) return@mapNotNull null
            val center = center(task, byId) ?: return@mapNotNull null
            val distance = maxOf(0.0, from.distanceTo(center) - center.radius)
            if (distance > within) null else Triple(i, task, distance)
        }.sortedWith(compareBy({ it.third }, { it.first })).map { Entry(it.second, it.third) }
    }

    fun center(task: CalendarTask, places: Map<String, TaskPlace>): GeoPoint? = when (val g = task.geofence) {
        is TaskGeofence.Place -> places[g.id]?.point
        is TaskGeofence.Point -> g.point
        null -> null
    }

    /** "Here", "350 m", "1.2 km". */
    fun format(meters: Double): String = when {
        meters < 1 -> "Here"
        meters < 1000 -> "${Math.round(meters)} m"
        else -> {
            val km = Math.round(meters / 100.0) / 10.0
            if (km == Math.floor(km)) "${km.toLong()} km" else "$km km"
        }
    }
}

/**
 * What the Tasks tab narrows its list to: words in the search field, plus the due range, priority,
 * tags and place picked from the filter menu. Applied on the device to the tasks loaded.
 */
data class TaskFilter(
    /** Matched against the title, notes, location and tags, ignoring case and accents. */
    val text: String = "",
    val due: Due = Due.ANY,
    /** 1 (high) to 3 (low); null is any priority. */
    val priority: Int? = null,
    /** A task matches if it carries any one of these. */
    val tags: Set<String> = emptySet(),
    val showDone: Boolean = true,
    /** Open tasks with a place within this range, nearest first; applied by [NearbyTasks] after [apply]. */
    val nearby: NearbyTasks.Range? = null,
) {
    enum class Due(val title: String) {
        ANY("Any date"), OVERDUE("Overdue"), TODAY("Due today"), WEEK("Next 7 days"), NONE("No due date"),
    }

    private val query: String get() = text.trim()

    /** Anything other than the whole list, search included. */
    val isActive: Boolean get() = hasMenuFilters || query.isNotEmpty()

    /** The filters picked from the menu, not counting the search field. */
    val hasMenuFilters: Boolean get() = this != TaskFilter(text = text)

    /** The tasks that pass, keeping the order they came in. */
    fun apply(tasks: List<CalendarTask>, now: Instant, context: CalendarContext): List<CalendarTask> {
        val today = context.day(now)
        val weekEnd = today.plusDays(7)
        val words = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return tasks.filter { task ->
            if (task.done && !showDone) return@filter false
            if (priority != null && task.priority != priority) return@filter false
            if (tags.isNotEmpty() && tags.intersect(task.tags.toSet()).isEmpty()) return@filter false
            val day = task.dueDay(context)
            val dueOk = when (due) {
                Due.ANY -> true
                Due.NONE -> day == null
                // A done task is never overdue: it was finished, whatever its date says.
                Due.OVERDUE -> !task.done && day != null && day < today
                Due.TODAY -> day == today
                Due.WEEK -> day != null && day >= today && day < weekEnd
            }
            dueOk && words.all { contains(task, it) }
        }
    }

    private fun contains(task: CalendarTask, word: String): Boolean {
        if (word.startsWith("#")) {
            val tag = word.trimStart('#').lowercase()
            return tag.isEmpty() || task.tags.any { it.startsWith(tag) }
        }
        val needle = fold(word)
        return (listOfNotNull(task.title, task.notes, task.location) + task.tags).any { fold(it).contains(needle) }
    }

    companion object {
        /** Case- and accent-insensitive form, for searching. */
        fun fold(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
    }
}

/**
 * Tasks with a due date, drawn on the calendar beside events with a checkbox to complete them: the
 * web's `taskEvents`. Nothing is stored; each is an occurrence of a made-up event with source `task`.
 */
object TaskOccurrences {
    /** How long a task due at a time is drawn on the time grid, unless it has an estimate. */
    const val DEFAULT_MINUTES = 30

    /**
     * The tasks due from `from` to `to` (inclusive). A task due on a date is all-day on that date; one
     * due at a time starts then and lasts its estimate, or half an hour. A task scheduled onto the
     * calendar is left out (its event is drawn); done tasks stay, ticked.
     */
    fun occurrences(tasks: List<CalendarTask>, from: Instant, to: Instant): List<EventOccurrence> {
        val first = com.neutrino.calendar.core.model.ServerDate.utcDay(from)
        val last = com.neutrino.calendar.core.model.ServerDate.utcDay(to)
        return tasks.mapNotNull { task ->
            val due = task.dueDate ?: return@mapNotNull null
            if (task.eventId != null) return@mapNotNull null
            val start: Instant
            val end: Instant
            if (task.dueHasTime) {
                if (due < from || due > to) return@mapNotNull null
                val minutes = task.estimateMinutes?.takeIf { it > 0 } ?: DEFAULT_MINUTES
                start = due
                end = due.plusSeconds(minutes * 60L)
            } else {
                val day = com.neutrino.calendar.core.model.ServerDate.utcDay(due)
                if (day < first || day > last) return@mapNotNull null
                start = Instant.parse("${day}T00:00:00Z")
                end = Instant.parse("${day}T23:59:59Z")
            }
            val event = com.neutrino.calendar.core.model.CalendarEvent(
                id = "task:${task.id}", title = task.title, description = task.notes, start = start, end = end,
                allDay = !task.dueHasTime, location = task.location, source = com.neutrino.calendar.core.model.EventSource.Task,
            )
            EventOccurrence(event, start, end, task = task)
        }
    }
}
