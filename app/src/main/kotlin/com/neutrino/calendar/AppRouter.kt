package com.neutrino.calendar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.neutrino.calendar.core.logic.EventDraft
import com.neutrino.calendar.core.logic.EventLink
import com.neutrino.calendar.core.logic.EventOccurrence
import com.neutrino.calendar.core.model.RecurrenceScope
import com.neutrino.calendar.core.model.Reminder
import java.time.LocalDate

enum class Tab(val label: String) { CALENDAR("Calendar"), REMINDERS("Reminders"), TASKS("Tasks"), SETTINGS("Settings") }

/** A screen pushed over a tab. */
sealed class Route {
    data class Event(val occurrence: EventOccurrence) : Route()
    data class EditEvent(val mode: EventEditorMode) : Route()
    data class Task(val id: String) : Route()
    data class EditReminder(val mode: ReminderEditorMode) : Route()
    object Calendars : Route()
    data class EditCalendar(val id: String) : Route()
    data class HolidayCalendar(val id: String) : Route()
    object AddCountry : Route()
    object SavedPlaces : Route()
    object KeyOptions : Route()
    object Pairing : Route()
    object RecoveryKit : Route()
    object KeyCode : Route()
    object SetUpEncryption : Route()
    object CalendarFilter : Route()
}

sealed class EventEditorMode {
    data class Create(val day: LocalDate) : EventEditorMode()
    /** A new event filled in from a shared `.ics` file. */
    data class Imported(val draft: EventDraft) : EventEditorMode()
    /** An edit of an occurrence, and for a repeating event which occurrences it is for. */
    data class Edit(val occurrence: EventOccurrence, val scope: RecurrenceScope?) : EventEditorMode()
}

sealed class ReminderEditorMode {
    data class Create(val eventId: String? = null, val taskId: String? = null, val due: java.time.Instant? = null) : ReminderEditorMode()
    data class Edit(val reminder: Reminder, val scope: RecurrenceScope? = null) : ReminderEditorMode()
}

/**
 * Where the app is showing, for things outside the tabs that need to move it: a tapped reminder
 * opens it, a widget opens its event or day, an `.ics` file opens the new-event form. Held in the
 * process, so a link that arrives during sign-in still lands once the calendar shows.
 */
class AppRouter {
    var tab by mutableStateOf(Tab.CALENDAR)
    private val stacks = Tab.entries.associateWith { mutableStateListOf<Route>() }

    fun stack(tab: Tab): List<Route> = stacks.getValue(tab)
    val current: Route? get() = stacks.getValue(tab).lastOrNull()

    fun push(route: Route, on: Tab = tab) {
        tab = on
        stacks.getValue(on).add(route)
    }

    fun pop(): Boolean {
        val stack = stacks.getValue(tab)
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Pops while the top is one of `routes`: a finished flow leaves whatever opened it. */
    fun popWhile(predicate: (Route) -> Boolean) {
        val stack = stacks.getValue(tab)
        while (stack.isNotEmpty() && predicate(stack.last())) stack.removeAt(stack.lastIndex)
    }

    fun select(tab: Tab) {
        // A second tap on the tab showing goes back to its root, as tab bars do.
        if (this.tab == tab) stacks.getValue(tab).clear()
        this.tab = tab
    }

    fun reset() {
        stacks.values.forEach { it.clear() }
        tab = Tab.CALENDAR
        openEvent = null
        openReminderId = null
        openTaskId = null
        openDay = null
        importedEvent = null
        newTask = false
    }

    /** An event to open, taken by the Calendar tab once it is showing. */
    var openEvent by mutableStateOf<EventLink?>(null)
    var openDay by mutableStateOf<LocalDate?>(null)
    var openReminderId by mutableStateOf<String?>(null)
    var openTaskId by mutableStateOf<String?>(null)
    var importedEvent by mutableStateOf<EventDraft?>(null)
    /** "New task" from a shortcut: the Tasks tab focuses its box. */
    var newTask by mutableStateOf(false)
}
