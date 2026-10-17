package com.neutrino.calendar.core.service

import com.neutrino.calendar.core.api.CalendarApi
import com.neutrino.calendar.core.sync.CalendarSignalsClient
import com.neutrino.calendar.core.sync.PendingWrites
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps what the app shows in step with changes made on the web and other devices, and gets changes
 * made offline to the server.
 *
 * - A live signal pulls event changes and reloads reminders and tasks, which are small whole lists.
 * - Coming to the foreground, reconnecting, and getting back online do the same, after sending
 *   whatever writes were queued offline ([PendingWrites]).
 * - Either way, the widgets and the next-event notification are brought up to date ([onRefreshed]).
 */
class CalendarSync(
    private val api: CalendarApi,
    private val signals: CalendarSignalsClient?,
    val pending: PendingWrites,
    private val events: EventsService,
    private val reminders: RemindersService,
    private val tasks: TasksService,
    private val calendars: CalendarsService? = null,
    private val places: PlacesService? = null,
    private val scope: CoroutineScope,
) {
    /** After every refresh: the app updates its system surfaces here. */
    var onRefreshed: (suspend () -> Unit)? = null
    private var started = false
    private var wasOnline = true

    init {
        events.pending = pending
        reminders.pending = pending
        tasks.pending = pending
        signals?.onRemoteChange = { scope.launch { refresh() } }
        signals?.onReconnect = { scope.launch { catchUp() } }
    }

    /** Replays queued writes whenever the network comes back. */
    fun networkChanged(online: Boolean) {
        if (online && !wasOnline) scope.launch { catchUp() }
        wasOnline = online
    }

    /** Signed in and in the foreground. Safe to call again; only the first call after [suspend] does anything. */
    fun start() {
        if (started) return
        started = true
        signals?.start()
        scope.launch { catchUp() }
    }

    /** Leaving the foreground. */
    fun suspend() {
        started = false
        signals?.stop()
    }

    /** Signed out: the next account must not receive this one's signals or send its edits. */
    fun stop() {
        suspend()
        pending.clear()
    }

    /** Sends queued writes, then brings everything up to date. */
    suspend fun catchUp() {
        if (!pending.isEmpty) {
            // The queued edits were shown locally; reload so the server's version is shown.
            if (pending.replay(api) > 0) events.invalidate()
        }
        refresh()
    }

    /** Something changed elsewhere. */
    suspend fun refresh() {
        // Calendars aren't in the changes feed, and there are few: listed again every time.
        calendars?.let {
            it.reload()
            events.setCalendars(it.calendars.value)
        }
        events.pullChanges()
        reminders.reload()
        // Tasks and places load at launch too: tasks are drawn on the calendar, and arrival alerts
        // are planned from both.
        tasks.reload()
        events.setTasks(tasks.tasks.value)
        places?.reload()
        onRefreshed?.invoke()
    }
}
