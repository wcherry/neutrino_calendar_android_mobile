package com.neutrino.calendar.ui.tasks

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationAdd
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.neutrino.calendar.ReminderEditorMode
import com.neutrino.calendar.Route
import com.neutrino.calendar.core.logic.EventDayRange
import com.neutrino.calendar.core.logic.NearbyTasks
import com.neutrino.calendar.core.model.CalendarTask
import com.neutrino.calendar.core.model.EditConflict
import com.neutrino.calendar.core.model.Patch
import com.neutrino.calendar.core.model.TaskGeofence
import com.neutrino.calendar.geofence.LocationAccess
import com.neutrino.calendar.graph
import com.neutrino.calendar.ui.ActionRow
import com.neutrino.calendar.ui.DateTimeRow
import com.neutrino.calendar.ui.DayPickerDialog
import com.neutrino.calendar.ui.ErrorText
import com.neutrino.calendar.ui.FormRow
import com.neutrino.calendar.ui.FormSection
import com.neutrino.calendar.ui.PageBar
import com.neutrino.calendar.ui.SwitchRow
import com.neutrino.calendar.ui.ValueRow
import com.neutrino.calendar.ui.attachments.AttachmentsSection
import com.neutrino.calendar.ui.event.ConflictDialog
import com.neutrino.calendar.ui.event.PlainField
import com.neutrino.calendar.ui.mediumDate
import com.neutrino.calendar.ui.places.PlacePickerDialog
import com.neutrino.calendar.ui.reminders.ReminderRow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * The task editor: the one place a task is more than a line of text. The web's `TaskDetailModal`.
 * Save applies the task row and the calendar slot; reminders and attachments are written the moment
 * they are added or removed, since the task exists already.
 */
@Composable
fun TaskDetailScreen(taskId: String, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val tasks = graph.tasks
    val reminders = graph.reminders
    val places = graph.places
    val events = graph.events
    val coroutines = rememberCoroutineScope()
    val context = events.context
    val zone = context.zone
    val live by tasks.tasks.collectAsState()
    val allReminders by reminders.reminders.collectAsState()
    val savedPlaces by places.places.collectAsState()
    val placesLoaded by places.hasLoaded.collectAsState()
    val unreadable by places.unreadable.collectAsState()
    val access by graph.geofences.access.collectAsState()
    val task = live.firstOrNull { it.id == taskId }
    val askArrivals = rememberArrivalPermissions()

    // The task as the form was filled from it: an edit is measured from this, not from the live list.
    var base by remember { mutableStateOf<CalendarTask?>(null) }
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var hasDue by remember { mutableStateOf(false) }
    var due by remember { mutableStateOf(context.day(Instant.now())) }
    var tags by remember { mutableStateOf(listOf<String>()) }
    var tagDraft by remember { mutableStateOf("") }
    var geofence by remember { mutableStateOf<TaskGeofence?>(null) }
    var location by remember { mutableStateOf("") }
    var onCalendar by remember { mutableStateOf(false) }
    var allDay by remember { mutableStateOf(false) }
    var start by remember { mutableStateOf(Instant.now()) }
    var end by remember { mutableStateOf(Instant.now()) }
    var original by remember { mutableStateOf<Triple<Instant, Instant, Boolean>?>(null) }
    var seeded by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf<EditConflict?>(null) }
    var pickingDue by remember { mutableStateOf(false) }
    var pickingPlace by remember { mutableStateOf(false) }

    fun nextHour(): Instant = context.dateTime(Instant.now()).truncatedTo(ChronoUnit.HOURS).plusHours(1).let(context::instant)

    /**
     * Fills the form once. Without seeding the slot from the event, Save would move the event to a
     * default slot: a screen changing what it describes just by being opened.
     */
    suspend fun seed() {
        val t = tasks.task(taskId) ?: return
        seeded = true
        base = t
        title = t.title
        notes = t.notes ?: ""
        tags = t.tags
        tagDraft = ""
        geofence = t.geofence
        location = t.location ?: ""
        t.dueDay(context)?.let { hasDue = true; due = it }
        val defaultStart = t.dueDay(context)?.let { context.instant(it, LocalTime.of(9, 0)) } ?: nextHour()
        start = defaultStart
        end = defaultStart.plus(Duration.ofHours(1))
        onCalendar = t.eventId != null
        if (t.geofence?.placeId != null && !places.hasLoaded.value) coroutines.launch { places.reload() }
        if (!reminders.hasLoaded.value) reminders.reload()
        runCatching { tasks.event(t) }.getOrNull()?.let { event ->
            allDay = event.allDay
            if (event.allDay) {
                val range = EventDayRange.of(event.start, event.end, true, context)
                start = context.startOf(range.first)
                end = context.startOf(range.last)
            } else {
                start = event.start
                end = event.end
            }
            original = Triple(start, end, allDay)
        }
    }

    LaunchedEffect(taskId) { if (!seeded) seed() }

    fun save(overwrite: Boolean = false) {
        val t = base ?: task ?: return
        saving = true
        error = null
        coroutines.launch {
            try {
                // The row first: the event carries the task's title, read from the stored row.
                val saved = tasks.update(
                    t, title.trim(), notes, if (hasDue) due else null, context,
                    // A tag typed but not yet added is still meant.
                    tags = tags + com.neutrino.calendar.core.model.TaskTags.split(tagDraft),
                    geofence = geofence?.let { Patch.Set(it) } ?: Patch.Clear,
                    location = location.trim().takeIf { it.isNotEmpty() }?.let { Patch.Set(it) } ?: Patch.Clear,
                    overwrite = overwrite,
                )
                val slot = Triple(start, end, allDay)
                var calendarChanged = false
                if (onCalendar && (saved.eventId == null || slot != original)) {
                    tasks.schedule(saved, start, end, allDay, context)
                    calendarChanged = true
                } else if (!onCalendar && saved.eventId != null) {
                    tasks.unschedule(saved)
                    calendarChanged = true
                } else if (saved.eventId != null && saved.title != t.title) {
                    // A rename retitles the event on the server.
                    calendarChanged = true
                }
                if (calendarChanged) events.invalidate()
                onBack()
            } catch (c: EditConflict) {
                conflict = c
            } catch (e: Exception) {
                error = e.message
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        PageBar("Task", onBack) {
            TextButton(onClick = { save() }, enabled = title.isNotBlank() && !saving && task != null) { Text("Save") }
        }
        if (task == null) {
            Text("This task is no longer available.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FormSection {
                PlainField(title, { title = it }, "Title", capitalize = true)
                PlainField(notes, { notes = it }, "Notes", singleLine = false, divider = false)
            }
            FormSection {
                SwitchRow("Due date", hasDue) { hasDue = it }
                if (hasDue) ValueRow("Date", mediumDate.format(due), onClick = { pickingDue = true })
            }
            FormSection("Tags") { TagField(tags, { tags = it }, tagDraft, { tagDraft = it }, tasks.allTags) }

            // Where the task reminds you on arrival. Arrival alerts are this phone's job, so the
            // footer says plainly when they can't fire; the task saves either way.
            val g = geofence
            FormSection("Location", footer = when {
                g != null && graph.geofences.inactiveReason != null -> graph.geofences.inactiveReason
                g is TaskGeofence.Place && placesLoaded && savedPlaces.none { it.id == g.id } ->
                    if (unreadable > 0) "This saved place can't be opened on this phone without your encryption key." else "This saved place was deleted."
                else -> null
            }) {
                if (g != null) {
                    FormRow {
                        Icon(Icons.Filled.MyLocation, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        val name = when (g) {
                            is TaskGeofence.Place -> savedPlaces.firstOrNull { it.id == g.id }?.name ?: location.ifEmpty { "Saved place" }
                            is TaskGeofence.Point -> "${location.ifEmpty { "Pinned location" }} · ${NearbyTasks.format(g.point.radius.toDouble())}"
                        }
                        Text(name, Modifier.weight(1f))
                        TextButton(onClick = { geofence = null }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
                    }
                    ActionRow("Change Place…") { pickingPlace = true }
                    if (access != LocationAccess.ALWAYS) {
                        val ctx = LocalContext.current
                        ActionRow(if (access == LocationAccess.NOT_ASKED) "Allow Location" else "Open Settings") {
                            if (access == LocationAccess.NOT_ASKED || access == LocationAccess.WHILE_USING) askArrivals()
                            else ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                        }
                    }
                } else {
                    if (location.isNotEmpty()) FormRow {
                        Icon(Icons.Filled.LocationOn, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(location, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ActionRow("Remind Me When I Arrive…", Icons.Filled.MyLocation) { pickingPlace = true }
                }
            }

            FormSection(footer = if (task.eventId != null && !onCalendar) "Saving takes it off the calendar and deletes its event." else null) {
                SwitchRow("Add to calendar", onCalendar) { onCalendar = it }
                if (onCalendar) {
                    SwitchRow("All day", allDay) { allDay = it }
                    DateTimeRow("Starts", start, zone, allDay) { newStart ->
                        // Moving the start keeps the slot's length rather than inverting it.
                        val length = Duration.between(start, end)
                        start = newStart
                        end = newStart.plus(if (length.isNegative || length.isZero) Duration.ofHours(1) else length)
                    }
                    DateTimeRow("Ends", end, zone, allDay, minimum = context.day(start)) { end = it }
                }
            }
            ErrorText(error)

            FormSection("Reminders") {
                allReminders.filter { it.linkedTaskId == task.id }.sortedBy { it.due }.forEach { r ->
                    ReminderRow(r, showsLink = false, onDelete = { coroutines.launch { reminders.delete(r) } }) {
                        graph.router.push(Route.EditReminder(ReminderEditorMode.Edit(r)))
                    }
                }
                ActionRow("Add Reminder", Icons.Filled.NotificationAdd) {
                    // 09:00 on its due day, or the top of the next hour.
                    val morning = if (hasDue) context.instant(due, LocalTime.of(9, 0)).takeIf { it > Instant.now() } else null
                    graph.router.push(Route.EditReminder(ReminderEditorMode.Create(taskId = task.id, due = morning ?: nextHour())))
                }
            }
            AttachmentsSection(tasks.attachmentOwner(task.id))
            Spacer(Modifier.height(32.dp))
        }
    }
    if (pickingDue) DayPickerDialog(due, { pickingDue = false }) { due = it }
    if (pickingPlace) PlacePickerDialog({ pickingPlace = false }) { name, picked ->
        location = name
        geofence = picked
        // In context: the first place picked is when an arrival alert starts to matter.
        askArrivals()
    }
    conflict?.let { c ->
        ConflictDialog(c, onOverwrite = { conflict = null; save(true) }, onDiscard = {
            conflict = null
            // Shows the task as it is now, or "no longer available".
            coroutines.launch {
                tasks.reload()
                seed()
            }
        })
    }
}
