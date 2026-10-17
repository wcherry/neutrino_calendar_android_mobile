package com.neutrino.calendar.core.service

import com.neutrino.calendar.core.api.CalendarApi
import com.neutrino.calendar.core.api.isNetwork
import com.neutrino.calendar.core.api.isNotFound
import com.neutrino.calendar.core.logic.titleOrder
import com.neutrino.calendar.core.model.CalendarContext
import com.neutrino.calendar.core.model.CalendarTask
import com.neutrino.calendar.core.model.CreateReminderRequest
import com.neutrino.calendar.core.model.EditConflict
import com.neutrino.calendar.core.model.RecurrenceScope
import com.neutrino.calendar.core.model.Reminder
import com.neutrino.calendar.core.model.ReminderOccurrenceRequest
import com.neutrino.calendar.core.model.ReminderRange
import com.neutrino.calendar.core.model.ServerDate
import com.neutrino.calendar.core.model.UpdateReminderRequest
import com.neutrino.calendar.core.sync.PendingWrite
import com.neutrino.calendar.core.sync.PendingWrites
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId

/**
 * Every reminder the user has, and the changes made to them.
 *
 * One list serves the Reminders tab and the event and task screens alike. After every change the
 * server's answer replaces the local copy rather than the change being applied locally: completing a
 * recurring reminder comes back open, at its next due time, and only the server knows which.
 */
class RemindersService(private val api: CalendarApi, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {

    private val _reminders = MutableStateFlow<List<Reminder>>(emptyList())
    val reminders: StateFlow<List<Reminder>> = _reminders

    /** Titles of the tasks reminders are linked to, by task id, for labelling them. */
    private val _taskTitles = MutableStateFlow<Map<String, String>>(emptyMap())
    val taskTitles: StateFlow<Map<String, String>> = _taskTitles

    val isLoading = MutableStateFlow(false)
    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded
    val error = MutableStateFlow<String?>(null)

    /** Where an edit, completion or delete goes when the server can't be reached. */
    var pending: PendingWrites? = null

    private val byDue = Comparator<Reminder> { a, b -> if (a.due != b.due) a.due.compareTo(b.due) else titleOrder(a.title, b.title) }

    fun forEvent(eventId: String) = _reminders.value.filter { it.linkedEventId == eventId }.sortedWith(byDue)
    fun forTask(taskId: String) = _reminders.value.filter { it.linkedTaskId == taskId }.sortedWith(byDue)

    /**
     * What the Reminders tab shows: open reminders first, soonest due first, then completed ones.
     * Unlike the web's sidebar, linked reminders are included: on a phone this list is where "what do
     * I need to be reminded of" gets answered.
     */
    fun visible(range: ReminderRange, search: String, now: Instant, context: CalendarContext): Pair<List<Reminder>, List<Reminder>> {
        val query = search.trim()
        val shown = _reminders.value.filter { range.contains(it, now, context) && (query.isEmpty() || it.title.contains(query, ignoreCase = true)) }
        return shown.filter { !it.completed }.sortedWith(byDue) to shown.filter { it.completed }.sortedWith(byDue)
    }

    /** How many open reminders a range is holding back, so an empty list can say why. */
    fun hiddenCount(range: ReminderRange, now: Instant, context: CalendarContext) =
        _reminders.value.count { !it.completed && !range.contains(it, now, context) }

    suspend fun reload() {
        isLoading.value = true
        error.value = null
        try {
            val loaded = api.reminders()
            // Loaded before the list is published: whatever reacts to the list (the alarm plan)
            // checks this, and would otherwise see the first load as not loaded yet.
            _hasLoaded.value = true
            _reminders.value = loaded
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = e.message
            return
        } finally {
            isLoading.value = false
        }
        // Task titles only label linked reminders; failing to get them is not worth an error.
        if (_reminders.value.any { it.linkedTaskId != null }) {
            runCatching { api.tasks() }.getOrNull()?.let { tasks -> _taskTitles.value = tasks.associate { it.id to it.title } }
        }
    }

    suspend fun tasks(): List<CalendarTask> = api.tasks()

    fun reset() {
        _reminders.value = emptyList()
        _taskTitles.value = emptyMap()
        _hasLoaded.value = false
        error.value = null
        isLoading.value = false
    }

    suspend fun create(title: String, due: Instant, rule: String?, eventId: String? = null, task: CalendarTask? = null): Reminder {
        val created = api.createReminder(CreateReminderRequest(title, ServerDate.format(due), rule, eventId, task?.id))
        _reminders.value = _reminders.value + created
        if (task != null) _taskTitles.value = _taskTitles.value + (task.id to task.title)
        return created
    }

    /**
     * Saves an edit. Only what changed is sent, and unless `overwrite` the save stops with
     * [EditConflict] when someone changed one of the same fields. Offline, it is queued.
     */
    suspend fun update(reminder: Reminder, title: String, due: Instant, rule: String?, overwrite: Boolean = false) {
        val request = request(reminder, title, due, rule)
        if (request.isEmpty) return
        try {
            if (!overwrite) {
                val current = try {
                    api.reminder(reminder.id)
                } catch (e: Exception) {
                    if (e.isNotFound) {
                        _reminders.value = _reminders.value.filter { it.id != reminder.id }
                        throw EditConflict.DeletedElsewhere()
                    }
                    throw e
                }
                val theirs = request(reminder, current.title, current.due, current.recurrenceRule)
                val clashes = EditConflict.clashes(request, theirs)
                if (clashes.isNotEmpty()) throw EditConflict.ChangedElsewhere(clashes)
            }
            replace(api.updateReminder(reminder.id, request))
        } catch (e: Exception) {
            val pending = pending
            if (!e.isNetwork || pending == null) throw e
            pending.enqueue(PendingWrite.of("PATCH", path(reminder), request))
            replace(reminder.copy(title = title, due = due, recurrenceRule = rule))
        }
    }

    /** Ticks a reminder off or back on. The zone goes with a completion so the server steps a recurrence in local time. */
    suspend fun setCompleted(reminder: Reminder, completed: Boolean) {
        val request = UpdateReminderRequest(completed = completed, timezone = if (completed) zone().id else null)
        try {
            replace(api.updateReminder(reminder.id, request))
        } catch (e: Exception) {
            val pending = pending
            if (e.isNetwork && pending != null) {
                pending.enqueue(PendingWrite.of("PATCH", path(reminder), request))
                // A repeating one moves to its next time on the server; only a one-off can be shown ticked.
                if (reminder.recurrenceRule == null) replace(reminder.copy(completed = completed))
            } else {
                error.value = e.message
            }
        }
    }

    suspend fun delete(reminder: Reminder) {
        try {
            api.deleteReminder(reminder.id)
        } catch (e: Exception) {
            val pending = pending
            when {
                e.isNotFound -> Unit // deleted elsewhere already
                e.isNetwork && pending != null -> pending.enqueue(PendingWrite.of("DELETE", path(reminder)))
                else -> {
                    error.value = e.message
                    return
                }
            }
        }
        _reminders.value = _reminders.value.filter { it.id != reminder.id }
    }

    /**
     * Saves an edit to the occurrences `scope` names: THIS makes a one-off reminder of the current
     * occurrence with the changes and moves the series on (it needs the server); otherwise, [update].
     */
    suspend fun update(reminder: Reminder, scope: RecurrenceScope?, title: String, due: Instant, rule: String?, overwrite: Boolean = false) {
        if (scope != RecurrenceScope.THIS || reminder.recurrenceRule == null) return update(reminder, title, due, rule, overwrite)
        val request = ReminderOccurrenceRequest(
            title = title.takeIf { it != reminder.title },
            dueTime = ServerDate.format(due).takeIf { due != reminder.due },
            timezone = zone().id,
        )
        val (one, series) = api.editReminderOccurrence(reminder.id, request)
        _reminders.value = _reminders.value + one
        moveOn(reminder, series)
    }

    /** Deletes the occurrences `scope` names: THIS skips the series past the current one; otherwise, [delete]. */
    suspend fun delete(reminder: Reminder, scope: RecurrenceScope?) {
        if (scope != RecurrenceScope.THIS || reminder.recurrenceRule == null) return delete(reminder)
        try {
            moveOn(reminder, api.skipReminder(reminder.id, zone().id))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isNotFound) _reminders.value = _reminders.value.filter { it.id != reminder.id } else error.value = e.message
        }
    }

    private fun moveOn(reminder: Reminder, series: Reminder?) {
        if (series != null) replace(series) else _reminders.value = _reminders.value.filter { it.id != reminder.id }
    }

    private fun replace(updated: Reminder) {
        val list = _reminders.value
        _reminders.value = if (list.any { it.id == updated.id }) list.map { if (it.id == updated.id) updated else it } else list + updated
    }

    companion object {
        /** The fields that differ between `reminder` and the values given. */
        fun request(reminder: Reminder, title: String, due: Instant, rule: String?) = UpdateReminderRequest(
            title = title.takeIf { it != reminder.title },
            dueTime = ServerDate.format(due).takeIf { due != reminder.due },
            recurrenceRule = (rule ?: "").takeIf { rule != reminder.recurrenceRule },
        )

        private fun path(reminder: Reminder) = "/api/v1/calendar/reminders/${reminder.id}"
    }
}
