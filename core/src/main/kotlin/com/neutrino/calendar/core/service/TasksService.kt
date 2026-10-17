package com.neutrino.calendar.core.service

import com.neutrino.calendar.core.api.CalendarApi
import com.neutrino.calendar.core.api.isNetwork
import com.neutrino.calendar.core.logic.SmartAdd
import com.neutrino.calendar.core.logic.SmartAddResult
import com.neutrino.calendar.core.model.CalendarContext
import com.neutrino.calendar.core.model.CalendarEvent
import com.neutrino.calendar.core.model.CalendarTask
import com.neutrino.calendar.core.model.CreateTaskRequest
import com.neutrino.calendar.core.model.EditConflict
import com.neutrino.calendar.core.model.Patch
import com.neutrino.calendar.core.model.ScheduleTaskRequest
import com.neutrino.calendar.core.model.ServerDate
import com.neutrino.calendar.core.model.TaskGeofence
import com.neutrino.calendar.core.model.TaskTags
import com.neutrino.calendar.core.model.UpdateTaskRequest
import com.neutrino.calendar.core.sync.PendingWrite
import com.neutrino.calendar.core.sync.PendingWrites
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Every task the user has, in the order they arranged them, and the changes made to them. Like
 * [RemindersService], the server's answer to each change replaces the local copy.
 */
class TasksService(private val api: CalendarApi, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {

    private val _tasks = MutableStateFlow<List<CalendarTask>>(emptyList())
    /** All tasks, open and done, in the server's order. */
    val tasks: StateFlow<List<CalendarTask>> = _tasks
    val isLoading = MutableStateFlow(false)
    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded
    val error = MutableStateFlow<String?>(null)
    var pending: PendingWrites? = null

    val open: List<CalendarTask> get() = _tasks.value.filter { !it.done }
    fun task(id: String) = _tasks.value.firstOrNull { it.id == id }

    /** Every tag in use, most used first. */
    val allTags: List<String> get() = TaskTags.all(_tasks.value)

    suspend fun reload() {
        isLoading.value = true
        error.value = null
        try {
            // In the server's order, not re-sorted: tasks made without a position share 0, and a
            // client-side tie-break would show a different order from the web's.
            _tasks.value = api.tasks()
            _hasLoaded.value = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = e.message
        } finally {
            isLoading.value = false
        }
    }

    fun reset() {
        _tasks.value = emptyList()
        _hasLoaded.value = false
        error.value = null
        isLoading.value = false
    }

    /** Creates the task a Smart Add line describes. */
    suspend fun create(parsed: SmartAddResult, context: CalendarContext): CalendarTask = create(SmartAdd.request(parsed, context))

    suspend fun create(request: CreateTaskRequest): CalendarTask {
        val created = api.createTask(request)
        _tasks.value = _tasks.value + created
        return created
    }

    /**
     * Ticked at once, wherever the box was, and unticked if the server refuses. Completing a repeating
     * task adds the next occurrence the server creates. Offline, the change is queued and stays.
     */
    suspend fun setDone(task: CalendarTask, done: Boolean) {
        val request = UpdateTaskRequest(done = done, timezone = zone().id)
        replace(task.copy(done = done))
        try {
            val (updated, next) = api.updateTask(task.id, request)
            replace(updated)
            next?.let(::replace)
        } catch (e: Exception) {
            val pending = pending
            if (e.isNetwork && pending != null) {
                pending.enqueue(PendingWrite.of("PATCH", path(task), request))
            } else {
                replace(task)
                error.value = e.message
            }
        }
    }

    /**
     * Saves the task row. Only what changed is sent, and an emptied field is sent as a clear. `tags`
     * is the whole new tag set; null leaves the tags alone. `geofence` and `location`: null leaves
     * them alone, a [Patch] sets or clears. Unless `overwrite`, stops with [EditConflict].
     */
    suspend fun update(
        task: CalendarTask, title: String, notes: String, dueDay: LocalDate?, context: CalendarContext,
        tags: List<String>? = null, geofence: Patch<TaskGeofence>? = null, location: Patch<String>? = null, overwrite: Boolean = false,
    ): CalendarTask {
        val request = request(task, title, notes, dueDay, context, tags, geofence, location)
        if (request.isEmpty) return task
        return try {
            if (!overwrite) {
                // There is no single-task read; the list is small.
                val current = api.tasks().firstOrNull { it.id == task.id }
                if (current == null) {
                    _tasks.value = _tasks.value.filter { it.id != task.id }
                    throw EditConflict.DeletedElsewhere()
                }
                val theirs = request(
                    task, current.title, current.notes ?: "", current.dueDay(context), context,
                    tags = if (tags == null) null else current.tags,
                    geofence = if (geofence == null) null else current.geofence.toPatch(),
                    location = if (location == null) null else current.location.toPatch(),
                )
                val clashes = EditConflict.clashes(request, theirs)
                if (clashes.isNotEmpty()) throw EditConflict.ChangedElsewhere(clashes)
            }
            api.updateTask(task.id, request).first.also(::replace)
        } catch (e: Exception) {
            val pending = pending
            if (!e.isNetwork || pending == null) throw e
            pending.enqueue(PendingWrite.of("PATCH", path(task), request))
            val trimmedNotes = notes.trim()
            val local = task.copy(
                title = title,
                notes = trimmedNotes.ifEmpty { null },
                dueDate = dueDay?.let { ServerDate.parse(CalendarTask.dueDateValue(it)) },
                dueHasTime = request.dueHasTime ?: task.dueHasTime,
                tags = request.tags ?: task.tags,
                location = if (request.location.isKeep) task.location else (location as? Patch.Set)?.value?.trim(),
                geofence = if (request.geoPlaceId.isKeep) task.geofence else (geofence as? Patch.Set)?.value,
            )
            replace(local)
            local
        }
    }

    /** Attaches, replaces or removes the arrival geofence, and the text shown for it — what Smart Add's offer sends. */
    suspend fun setGeofence(task: CalendarTask, geofence: TaskGeofence?, location: String? = null): CalendarTask {
        var request = UpdateTaskRequest().withGeofence(geofence)
        if (location != null && location != task.location) request = request.copy(location = Patch.Set(location))
        return try {
            api.updateTask(task.id, request).first.also(::replace)
        } catch (e: Exception) {
            val pending = pending
            if (!e.isNetwork || pending == null) throw e
            pending.enqueue(PendingWrite.of("PATCH", path(task), request))
            task.copy(location = location ?: task.location, geofence = geofence).also(::replace)
        }
    }

    /** Takes a deleted saved place off the tasks that used it, as the server just did. */
    fun forgetPlace(id: String) {
        _tasks.value = _tasks.value.map { if (it.geofence == TaskGeofence.Place(id)) it.copy(geofence = null) else it }
    }

    /**
     * Moves open tasks. `ids` is the new order of the open tasks only, as the web sends it. Applied
     * locally first and not rolled back on failure: the next reload reconciles, as on the web.
     */
    suspend fun reorderOpen(ids: List<String>) {
        val byId = _tasks.value.associateBy { it.id }
        _tasks.value = ids.mapNotNull { byId[it] } + _tasks.value.filter { it.id !in ids }
        try {
            api.reorderTasks(ids)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = e.message
        }
    }

    /** Puts a task on the calendar at the slot given, or moves it there. */
    suspend fun schedule(task: CalendarTask, start: Instant, end: Instant, allDay: Boolean, context: CalendarContext) {
        val event = api.scheduleTask(task.id, ScheduleTaskRequest.of(start, end, allDay, context))
        // The endpoint answers with the event, not the task, so the task is re-read for its `eventId`.
        if (task.eventId != event.id) refresh(task)
    }

    suspend fun unschedule(task: CalendarTask) {
        replace(api.unscheduleTask(task.id))
    }

    suspend fun event(task: CalendarTask): CalendarEvent? = task.eventId?.let { api.event(it) }

    fun attachmentOwner(taskId: String) = AttachmentOwner(
        "task-$taskId",
        load = { api.taskAttachments(taskId) },
        add = { api.addTaskAttachment(taskId, it) },
        delete = { api.deleteTaskAttachment(taskId, it.id) },
    )

    private suspend fun refresh(task: CalendarTask) {
        runCatching { api.tasks() }.getOrNull()?.firstOrNull { it.id == task.id }?.let(::replace)
    }

    private fun replace(updated: CalendarTask) {
        val list = _tasks.value
        _tasks.value = if (list.any { it.id == updated.id }) list.map { if (it.id == updated.id) updated else it } else list + updated
    }

    companion object {
        private fun path(task: CalendarTask) = "/api/v1/calendar/tasks/${task.id}"

        private fun <T> T?.toPatch(): Patch<T> = if (this == null) Patch.Clear else Patch.Set(this)

        /** The fields that differ between `task` and the values given: an edit's request, or what was changed elsewhere. */
        fun request(
            task: CalendarTask, title: String, notes: String, dueDay: LocalDate?, context: CalendarContext,
            tags: List<String>? = null, geofence: Patch<TaskGeofence>? = null, location: Patch<String>? = null,
        ): UpdateTaskRequest {
            var request = UpdateTaskRequest()
            if (title != task.title) request = request.copy(title = title)
            val trimmedNotes = notes.trim()
            if (trimmedNotes != (task.notes ?: "")) request = request.copy(notes = if (trimmedNotes.isEmpty()) Patch.Clear else Patch.Set(trimmedNotes))
            val newDue = dueDay?.let(CalendarTask::dueDateValue)
            val oldDue = task.dueDay(context)?.let(CalendarTask::dueDateValue)
            if (newDue != oldDue) {
                request = request.copy(dueDate = newDue?.let { Patch.Set(it) } ?: Patch.Clear)
                // A day picked here is a day: a due time set by Smart Add goes with the old date.
                if (newDue != null && task.dueHasTime) request = request.copy(dueHasTime = false)
            }
            if (tags != null) {
                val newTags = TaskTags.normalize(tags)
                if (newTags != TaskTags.normalize(task.tags)) request = request.copy(tags = newTags)
            }
            if (geofence != null) {
                val g = (geofence as? Patch.Set)?.value
                if (g != task.geofence) request = request.withGeofence(g)
            }
            if (location != null) {
                val trimmed = (location as? Patch.Set)?.value?.trim() ?: ""
                if (trimmed != (task.location ?: "")) request = request.copy(location = if (trimmed.isEmpty()) Patch.Clear else Patch.Set(trimmed))
            }
            return request
        }
    }
}
