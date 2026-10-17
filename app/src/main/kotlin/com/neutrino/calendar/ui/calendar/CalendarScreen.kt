package com.neutrino.calendar.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neutrino.calendar.EventEditorMode
import com.neutrino.calendar.Route
import com.neutrino.calendar.Tab
import com.neutrino.calendar.core.api.isNotFound
import com.neutrino.calendar.core.logic.CalendarMode
import com.neutrino.calendar.core.logic.EventDayRange
import com.neutrino.calendar.core.logic.EventFormatting
import com.neutrino.calendar.core.logic.EventOccurrence
import com.neutrino.calendar.core.model.CalendarRules
import com.neutrino.calendar.graph
import com.neutrino.calendar.ui.Badge
import com.neutrino.calendar.ui.DayPickerDialog
import com.neutrino.calendar.ui.EventStyle
import com.neutrino.calendar.ui.hexColor
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * The Calendar tab: the same events as Day, Week, Month, Year or Agenda, with arrows that step by the
 * view's own unit, a Today button, and a date picker behind the title. The mode is remembered per
 * device. Each mode reads what it needs from `EventsService`, which loads the months the mode covers
 * and keeps them, so switching modes over the same weeks costs no requests.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen() {
    val graph = LocalContext.current.graph
    val events = graph.events
    val router = graph.router
    val mode by graph.prefs.mode.collectAsState()
    val focus by events.focus.collectAsState()
    val generation by events.generation.collectAsState()
    WatchEvents()
    val scope = rememberCoroutineScope()
    var jumping by remember { mutableStateOf(false) }
    var choosingMode by remember { mutableStateOf(false) }

    // Loads whatever the mode now covers: a new mode, a new focus, or a cache thrown away after an
    // edit. In the app's scope, not this screen's: leaving the screen mid-load mustn't cancel it.
    LaunchedEffect(mode, focus, generation) { graph.scope.launch { events.ensureLoaded(mode) } }

    // A widget, a notification or a shortcut asked for an event: read from the server, so what opens
    // is the event as it is now, on the day it falls on.
    LaunchedEffect(router.openEvent) {
        val link = router.openEvent ?: return@LaunchedEffect
        router.openEvent = null
        try {
            val event = events.event(link.eventId)
            val series = event.recurringEventId?.let { events.event(it) }
            val occurrence = link.occurrenceOf(event, series)
            events.select(EventDayRange.of(occurrence, events.context).first)
            router.push(Route.Event(occurrence), Tab.CALENDAR)
        } catch (e: Exception) {
            events.error.value = if (e.isNotFound) "That event has been deleted." else e.message
        }
    }
    LaunchedEffect(router.openDay) {
        val day = router.openDay ?: return@LaunchedEffect
        router.openDay = null
        events.select(day)
    }
    // An event read from a shared `.ics` file opens in the new-event form, on the day it starts.
    LaunchedEffect(router.importedEvent) {
        val draft = router.importedEvent ?: return@LaunchedEffect
        router.importedEvent = null
        events.select(events.context.day(draft.start))
        router.push(Route.EditEvent(EventEditorMode.Imported(draft)), Tab.CALENDAR)
    }

    val title = when (mode) {
        CalendarMode.DAY -> DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault()).format(focus)
        CalendarMode.WEEK -> events.visibleDays(CalendarMode.WEEK).let { EventFormatting.span(it.first(), it.last()) }
        CalendarMode.MONTH, CalendarMode.AGENDA -> DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()).format(focus)
        CalendarMode.YEAR -> focus.year.toString()
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                TextButton(onClick = events::goToToday, enabled = !events.isShowingToday(mode)) { Text("Today") }
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { events.move(mode, -1) }) { Icon(Icons.Filled.ChevronLeft, "Previous") }
                    Row(Modifier.weight(1f, fill = false).clip(RoundedCornerShape(8.dp)).clickable { jumping = true }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Filled.ArrowDropDown, "Choose a date", Modifier.size(18.dp))
                    }
                    IconButton(onClick = { events.move(mode, 1) }) { Icon(Icons.Filled.ChevronRight, "Next") }
                }
            },
            actions = {
                // A new event starts on the day in view.
                IconButton(onClick = { router.push(Route.EditEvent(EventEditorMode.Create(focus))) }) { Icon(Icons.Filled.Add, "New event") }
                Box {
                    IconButton(onClick = { choosingMode = true }) { Icon(icon(mode), "View: ${mode.label}") }
                    DropdownMenu(choosingMode, { choosingMode = false }) {
                        CalendarMode.entries.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.label, fontWeight = if (m == mode) FontWeight.Bold else FontWeight.Normal) },
                                leadingIcon = { Icon(icon(m), null) },
                                onClick = {
                                    choosingMode = false
                                    graph.prefs.setMode(m)
                                },
                            )
                        }
                    }
                }
            },
        )
        val open: (EventOccurrence) -> Unit = { o ->
            // A task drawn on the calendar opens the task, not an event it doesn't have.
            if (o.task != null) router.push(Route.Task(o.task!!.id)) else router.push(Route.Event(o))
        }
        val refresh: () -> Unit = { graph.scope.launch { events.refreshFromProviders(mode) } }
        when (mode) {
            CalendarMode.DAY -> TimeGridView(listOf(focus), open, null, refresh)
            CalendarMode.WEEK -> TimeGridView(events.visibleDays(CalendarMode.WEEK), open, { day -> events.select(day); graph.prefs.setMode(CalendarMode.DAY) }, refresh)
            CalendarMode.MONTH -> MonthView(open, refresh)
            CalendarMode.YEAR -> YearView { month ->
                events.select(if (month.withDayOfMonth(1) == events.today.withDayOfMonth(1)) events.today else month)
                graph.prefs.setMode(CalendarMode.MONTH)
            }
            CalendarMode.AGENDA -> AgendaView(open, refresh)
        }
    }
    if (jumping) DayPickerDialog(focus, { jumping = false }) { events.select(it) }
}

/**
 * Subscribes the calling composable to everything the views draw from: the loaded months, holidays,
 * calendars (hidden, colours), tasks, the source filter and the week start. Each view calls it
 * itself, so a change redraws it even when its parent is skipped. It returns a value on purpose: a
 * composable that returns one gets no recompose scope of its own, so these reads invalidate the caller.
 */
@Composable
fun WatchEvents(): Int {
    val graph = LocalContext.current.graph
    val events = graph.events
    return listOf(
        events.byMonth.collectAsState().value,
        events.calendars.collectAsState().value,
        events.holidaysByMonth.collectAsState().value,
        events.tasks.collectAsState().value,
        events.sourceFilter.collectAsState().value,
        events.weekStart.collectAsState().value,
        graph.tasks.tasks.collectAsState().value,
    ).hashCode()
}

private fun icon(mode: CalendarMode): ImageVector = when (mode) {
    CalendarMode.DAY -> Icons.Filled.CalendarViewDay
    CalendarMode.WEEK -> Icons.Filled.CalendarViewWeek
    CalendarMode.MONTH -> Icons.Filled.CalendarMonth
    CalendarMode.YEAR -> Icons.Filled.GridView
    CalendarMode.AGENDA -> Icons.AutoMirrored.Filled.ViewList
}

/** An event's colour on the calendar: its calendar's, or the task amber. */
fun colorOf(occurrence: EventOccurrence, rules: CalendarRules, fallback: Color): Color =
    if (occurrence.task != null) EventStyle.taskColor else hexColor(rules.colorOf(occurrence.event)) ?: fallback

/** "Thu 24 Today", over a list of one day's events. */
@Composable
fun DayHeader(day: LocalDate, today: LocalDate) {
    val isToday = day == today
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, top = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        Text(day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), color = color, style = MaterialTheme.typography.labelLarge)
        Text(day.dayOfMonth.toString(), color = color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (isToday) Text("Today", color = color, style = MaterialTheme.typography.labelLarge)
    }
}

/** One occurrence in a list: a bar in its calendar's colour, or a checkbox for a task, then title, time and place. */
@Composable
fun EventRow(occurrence: EventOccurrence, onClick: () -> Unit) {
    val graph = LocalContext.current.graph
    val rules = graph.events.rules
    val scope = rememberCoroutineScope()
    val task = occurrence.task?.let { graph.tasks.task(it.id) ?: it }
    graph.tasks.tasks.collectAsState().value
    val compact = com.neutrino.calendar.ui.LocalCompact.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (compact) 8.dp else 16.dp, vertical = 2.dp).clip(RoundedCornerShape(10.dp))
            .background(if (task != null) EventStyle.taskColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (task != null) {
            Icon(
                if (task.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                if (task.done) "Mark ${task.title} as not done" else "Mark ${task.title} as done",
                tint = EventStyle.taskColor,
                modifier = Modifier.size(22.dp).clickable { scope.launch { graph.tasks.setDone(task, !task.done) } },
            )
        } else {
            Box(Modifier.width(4.dp).size(4.dp, 40.dp).clip(RoundedCornerShape(2.dp)).background(colorOf(occurrence, rules, MaterialTheme.colorScheme.primary)))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    occurrence.event.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2,
                    textDecoration = if (task?.done == true) TextDecoration.LineThrough else null,
                    color = if (task?.done == true) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (occurrence.isRepeating) Icon(Icons.Filled.Repeat, "Repeats", Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                occurrence.event.source.badge?.let { Badge(it) }
            }
            Text(
                if (task == null) EventFormatting.timeSummary(occurrence) else if (occurrence.event.allDay) "Task due" else "Due ${EventFormatting.shortTime(occurrence.start)}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            occurrence.event.location?.takeIf { it.isNotEmpty() }?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LocationOn, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
