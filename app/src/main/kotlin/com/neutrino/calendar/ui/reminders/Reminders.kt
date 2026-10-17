package com.neutrino.calendar.ui.reminders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.neutrino.calendar.ReminderEditorMode
import com.neutrino.calendar.Route
import com.neutrino.calendar.core.model.CalendarTask
import com.neutrino.calendar.core.model.EditConflict
import com.neutrino.calendar.core.model.RecurrenceScope
import com.neutrino.calendar.core.model.Reminder
import com.neutrino.calendar.core.model.ReminderRange
import com.neutrino.calendar.core.model.RepeatOption
import com.neutrino.calendar.graph
import com.neutrino.calendar.ui.ActionRow
import com.neutrino.calendar.ui.ChoiceRow
import com.neutrino.calendar.ui.DateTimeRow
import com.neutrino.calendar.ui.ErrorText
import com.neutrino.calendar.ui.FormRow
import com.neutrino.calendar.ui.FormSection
import com.neutrino.calendar.ui.PageBar
import com.neutrino.calendar.ui.event.ConflictDialog
import com.neutrino.calendar.ui.event.PlainField
import com.neutrino.calendar.ui.event.RecurrenceScopeDialog
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The Reminders tab: open reminders soonest first, completed ones after, filtered by the web's
 * Today / 3 days / 7 days / All and by search. Linked reminders are listed too: on a phone this is
 * where "what do I need to be reminded of" gets answered.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemindersScreen() {
    val graph = LocalContext.current.graph
    val reminders = graph.reminders
    val router = graph.router
    val scope = rememberCoroutineScope()
    val all by reminders.reminders.collectAsState()
    val loading by reminders.isLoading.collectAsState()
    val loaded by reminders.hasLoaded.collectAsState()
    val error by reminders.error.collectAsState()
    val titles by reminders.taskTitles.collectAsState()
    // All by default, as on the web: a filter should not open by hiding what was there last time.
    var range by rememberSaveable { mutableStateOf(ReminderRange.ALL) }
    var search by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var editingRepeating by remember { mutableStateOf<Reminder?>(null) }
    var deletingRepeating by remember { mutableStateOf<Reminder?>(null) }
    val now = Instant.now()
    val context = graph.events.context
    val (open, done) = reminders.visible(range, search, now, context)

    fun edit(r: Reminder) {
        if (r.recurrenceRule != null) editingRepeating = r else router.push(Route.EditReminder(ReminderEditorMode.Edit(r)))
    }

    LaunchedEffect(Unit) { reminders.reload() }
    // A tapped notification opens its reminder.
    LaunchedEffect(router.openReminderId, all) {
        val id = router.openReminderId ?: return@LaunchedEffect
        val r = all.firstOrNull { it.id == id } ?: return@LaunchedEffect
        router.openReminderId = null
        edit(r)
    }

    Column(Modifier.fillMaxSize()) {
        PageBar("Reminders", null) {
            IconButton(onClick = { searching = !searching; if (!searching) search = "" }) { Icon(Icons.Filled.Search, "Search") }
            IconButton(onClick = { router.push(Route.EditReminder(ReminderEditorMode.Create())) }) { Icon(Icons.Filled.Add, "New reminder") }
        }
        if (searching) OutlinedTextField(search, { search = it }, placeholder = { Text("Search") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            ReminderRange.entries.forEachIndexed { i, r ->
                SegmentedButton(range == r, { range = r }, SegmentedButtonDefaults.itemShape(i, ReminderRange.entries.size)) { Text(r.label) }
            }
        }
        ErrorText(error)
        PullToRefreshBox(isRefreshing = loading, onRefresh = { scope.launch { reminders.reload() } }, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (open.isEmpty() && done.isEmpty()) item {
                    if (loading && !loaded) {
                        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) { CircularProgressIndicator() }
                    } else {
                        // The web's wording: a range that hides everything says how much it hides.
                        val hidden = reminders.hiddenCount(range, now, context)
                        Text(
                            when {
                                search.isNotEmpty() -> "No matches"
                                hidden > 0 -> "Nothing due — $hidden later ${if (hidden == 1) "reminder" else "reminders"}"
                                else -> "No reminders"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(24.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                if (open.isNotEmpty()) item {
                    FormSection {
                        open.forEach { r ->
                            ReminderRow(r, titles[r.linkedTaskId], onDelete = { if (r.recurrenceRule != null) deletingRepeating = r else scope.launch { reminders.delete(r) } }) { edit(r) }
                        }
                    }
                }
                if (done.isNotEmpty()) item {
                    FormSection("Completed") {
                        done.forEach { r -> ReminderRow(r, titles[r.linkedTaskId], onDelete = { scope.launch { reminders.delete(r) } }) { edit(r) } }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
    editingRepeating?.let { r ->
        RecurrenceScopeDialog("Edit Repeating Reminder", RecurrenceScope.Kind.REMINDER, onDismiss = { editingRepeating = null }) {
            router.push(Route.EditReminder(ReminderEditorMode.Edit(r, it)))
        }
    }
    deletingRepeating?.let { r ->
        RecurrenceScopeDialog("Delete Repeating Reminder", RecurrenceScope.Kind.REMINDER, destructive = true, onDismiss = { deletingRepeating = null }) {
            scope.launch { reminders.delete(r, it) }
        }
    }
}

/** A reminder: a checkbox that ticks it off, its title and due time, what it belongs to, and delete. */
@Composable
fun ReminderRow(reminder: Reminder, taskTitle: String? = null, showsLink: Boolean = true, onDelete: (() -> Unit)? = null, onClick: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val overdue = reminder.isOverdue()
    FormRow(onClick = onClick) {
        Icon(
            if (reminder.completed) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            if (reminder.completed) "Mark as not done" else "Mark as done",
            tint = if (reminder.completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp).clickable { scope.launch { graph.reminders.setCompleted(reminder, !reminder.completed) } },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(reminder.title, textDecoration = if (reminder.completed) TextDecoration.LineThrough else null,
                    color = if (reminder.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (reminder.recurrenceRule != null) Icon(Icons.Filled.Repeat, "Repeats", Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!reminder.completed) {
                val time = DateTimeFormatter.ofPattern("MMM d, " + if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm a", Locale.getDefault())
                    .withZone(ZoneId.systemDefault()).format(reminder.due)
                Text(if (overdue) "Overdue · $time" else time, style = MaterialTheme.typography.bodyMedium,
                    color = if (overdue) Color(0xFFFF3B30) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showsLink) {
                val link = when {
                    reminder.linkedEventId != null -> "Event reminder" to Icons.Filled.CalendarMonth
                    reminder.linkedTaskId != null -> (taskTitle ?: "Task") to Icons.Filled.Checklist
                    else -> null
                }
                link?.let { (text, icon) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }
        if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Creates or edits one reminder. A link (event or task) is set once, at creation: the server's update has none. */
@Composable
fun ReminderEditorScreen(mode: ReminderEditorMode, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val reminders = graph.reminders
    val coroutines = rememberCoroutineScope()
    val existing = (mode as? ReminderEditorMode.Edit)?.reminder
    val scope = (mode as? ReminderEditorMode.Edit)?.takeIf { it.reminder.recurrenceRule != null }?.scope
    val create = mode as? ReminderEditorMode.Create
    val linkedTask = create?.taskId?.let { graph.tasks.task(it) }
    val nextHour = remember { Instant.now().atZone(ZoneId.systemDefault()).truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant() }
    var title by remember { mutableStateOf(existing?.title ?: linkedTask?.title ?: "") }
    var due by remember { mutableStateOf(existing?.due ?: create?.due ?: nextHour) }
    var repeat by remember { mutableStateOf(RepeatOption.of(existing?.recurrenceRule)) }
    var task by remember { mutableStateOf<CalendarTask?>(null) }
    var tasks by remember { mutableStateOf<List<CalendarTask>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf<EditConflict?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val titles by reminders.taskTitles.collectAsState()

    LaunchedEffect(Unit) {
        if (create != null && create.eventId == null && create.taskId == null) tasks = runCatching { reminders.tasks() }.getOrDefault(emptyList())
    }

    fun save(overwrite: Boolean = false) {
        saving = true
        error = null
        coroutines.launch {
            try {
                if (existing != null) {
                    reminders.update(existing, scope, title.trim(), due, repeat.rule, overwrite)
                } else {
                    reminders.create(title.trim(), due, repeat.rule, create?.eventId, linkedTask ?: task)
                }
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
        PageBar(if (existing == null) "New Reminder" else "Edit Reminder", onBack) {
            TextButton(onClick = { save() }, enabled = title.isNotBlank() && !saving) { Text(if (existing == null) "Add" else "Save") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FormSection {
                PlainField(title, { title = it }, "Title", capitalize = true)
                DateTimeRow("Due", due, ZoneId.systemDefault(), dateOnly = false) { due = it }
            }
            if (scope == RecurrenceScope.THIS) {
                FormSection(footer = "Saved as a reminder of its own. The repeating one moves on to its next time.") {
                    FormRow(divider = false) { Text(RecurrenceScope.THIS.label(RecurrenceScope.Kind.REMINDER), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            } else {
                val choices = RepeatOption.STANDARD + listOfNotNull(RepeatOption.of(existing?.recurrenceRule).takeIf { it is RepeatOption.Custom })
                FormSection(footer = if (repeat != RepeatOption.Never) "Completing it moves it to the next time it's due." else null) {
                    ChoiceRow("Repeat", repeat, choices, { it.label }, { repeat = it })
                }
            }
            when {
                create?.eventId != null -> Unit
                linkedTask != null -> FormSection { FormRow(divider = false) { Text(linkedTask.title) } }
                create != null && tasks.any { !it.done } -> FormSection(footer = "A reminder can't be moved to a different task later.") {
                    val open = listOf<CalendarTask?>(null) + tasks.filter { !it.done }
                    ChoiceRow("Task", task, open, { it?.title ?: "None" }, { task = it })
                }
                existing?.linkedEventId != null -> FormSection { FormRow(divider = false) { Text("On an event") } }
                existing?.linkedTaskId != null -> FormSection { FormRow(divider = false) { Text(titles[existing.linkedTaskId] ?: "On a task") } }
            }
            ErrorText(error)
            if (existing != null) FormSection {
                ActionRow("Delete Reminder", Icons.Filled.Delete, MaterialTheme.colorScheme.error) {
                    if (existing.recurrenceRule != null) confirmingDelete = true else coroutines.launch { reminders.delete(existing); onBack() }
                }
            }
        }
    }
    if (confirmingDelete && existing != null) {
        RecurrenceScopeDialog("Delete Repeating Reminder", RecurrenceScope.Kind.REMINDER, destructive = true, onDismiss = { confirmingDelete = false }) {
            coroutines.launch { reminders.delete(existing, it); onBack() }
        }
    }
    conflict?.let { c ->
        ConflictDialog(c, onOverwrite = { conflict = null; save(true) }, onDiscard = {
            conflict = null
            coroutines.launch { reminders.reload() }
            onBack()
        })
    }
}
