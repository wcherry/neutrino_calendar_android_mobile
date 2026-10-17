package com.neutrino.calendar.ui.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationAdd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.neutrino.calendar.EventEditorMode
import com.neutrino.calendar.ReminderEditorMode
import com.neutrino.calendar.Route
import com.neutrino.calendar.core.logic.EventDraft
import com.neutrino.calendar.core.logic.EventFormatting
import com.neutrino.calendar.core.logic.EventOccurrence
import com.neutrino.calendar.core.logic.RepeatRule
import com.neutrino.calendar.core.model.CalendarEvent
import com.neutrino.calendar.core.model.EditConflict
import com.neutrino.calendar.core.model.EventSource
import com.neutrino.calendar.core.model.RecurrenceScope
import com.neutrino.calendar.core.model.ReminderPreset
import com.neutrino.calendar.core.model.RepeatOption
import com.neutrino.calendar.graph
import com.neutrino.calendar.ui.ActionRow
import com.neutrino.calendar.ui.Badge
import com.neutrino.calendar.ui.ChoiceRow
import com.neutrino.calendar.ui.ColorDot
import com.neutrino.calendar.ui.DateTimeRow
import com.neutrino.calendar.ui.ErrorText
import com.neutrino.calendar.ui.FormRow
import com.neutrino.calendar.ui.FormSection
import com.neutrino.calendar.ui.PageBar
import com.neutrino.calendar.ui.SwitchRow
import com.neutrino.calendar.ui.ValueRow
import com.neutrino.calendar.ui.attachments.AttachmentsSection
import com.neutrino.calendar.ui.hexColor
import com.neutrino.calendar.ui.reminders.ReminderRow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

// ── Dialogs ─────────────────────────────────────────────────────────────────────────────────────

/** "This Event / This and Following Events / All Events": asked before an edit or delete of a repeating one. */
@Composable
fun RecurrenceScopeDialog(title: String, kind: RecurrenceScope.Kind, destructive: Boolean = false, onDismiss: () -> Unit, onChoose: (RecurrenceScope) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("This is a repeating ${kind.noun.lowercase()}.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                RecurrenceScope.entries.forEach { scope ->
                    TextButton(onClick = { onDismiss(); onChoose(scope) }, modifier = Modifier.fillMaxWidth()) {
                        Text((if (destructive) "Delete " else "") + scope.label(kind),
                            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A save found the thing changed or deleted elsewhere: save over the other change, or drop this one. */
@Composable
fun ConflictDialog(conflict: EditConflict, onOverwrite: () -> Unit, onDiscard: () -> Unit) {
    val deleted = conflict is EditConflict.DeletedElsewhere
    AlertDialog(
        onDismissRequest = onDiscard,
        title = { Text(if (deleted) "Deleted Elsewhere" else "Changed Elsewhere") },
        text = { Text(if (deleted) "${conflict.message} Your changes can't be saved." else "${conflict.message} Save yours over theirs, or keep theirs?") },
        confirmButton = {
            if (deleted) TextButton(onClick = onDiscard) { Text("OK") }
            else TextButton(onClick = onOverwrite) { Text("Save Mine Anyway", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { if (!deleted) TextButton(onClick = onDiscard) { Text("Discard My Changes") } },
    )
}

// ── Detail ──────────────────────────────────────────────────────────────────────────────────────

/**
 * One occurrence of an event. Neutrino's own events can be edited and deleted here unless their
 * calendar is read-only; synced events can't be yet (the server can't write back), and a holiday,
 * computed here, has no reminders or attachments either.
 */
@Composable
fun EventDetailScreen(occurrence: EventOccurrence, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val events = graph.events
    val reminders = graph.reminders
    val router = graph.router
    val scope = rememberCoroutineScope()
    val event = occurrence.event
    events.calendars.collectAsState().value
    val all by reminders.reminders.collectAsState()
    val rules = events.rules
    val calendar = rules.calendarOf(event)
    val editable = rules.isEditable(event)
    var choosingEditScope by remember { mutableStateOf(false) }
    var deletingReminder by remember { mutableStateOf<com.neutrino.calendar.core.model.Reminder?>(null) }
    var addingReminder by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (!reminders.hasLoaded.value && !event.source.isComputed) reminders.reload() }

    Column(Modifier.fillMaxSize()) {
        PageBar("Event", onBack) {
            if (editable) TextButton(onClick = {
                if (occurrence.isRepeating) choosingEditScope = true else router.push(Route.EditEvent(EventEditorMode.Edit(occurrence, null)))
            }) { Text("Edit") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                FormSection(footer = when {
                    event.source.badge != null -> "Synced from ${event.source.badge}. Edit it there: changes can't be sent back to ${event.source.badge} yet."
                    event.source == EventSource.Holidays -> "A public holiday, worked out on this phone from the holiday calendar you added."
                    rules.isReadOnly(event) -> "This calendar is read-only, so its events can't be changed."
                    else -> null
                }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(event.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            event.source.badge?.let { Badge(it) }
                        }
                        if (calendar != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ColorDot(hexColor(calendar.color) ?: MaterialTheme.colorScheme.primary)
                            Text(calendar.name, style = MaterialTheme.typography.bodyMedium)
                            if (rules.isReadOnly(event)) {
                                Icon(Icons.Filled.Lock, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Read-only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(EventFormatting.dateSummary(occurrence))
                        Text(EventFormatting.timeSummary(occurrence), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        EventFormatting.originalTimeZoneSummary(occurrence)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        (occurrence.series ?: event).recurrenceRule?.takeIf { it.isNotEmpty() }?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Repeat, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                                Text(EventFormatting.recurrenceSummary(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            event.location?.takeIf { it.isNotEmpty() }?.let { location ->
                item { FormSection("Location") { FormRow(divider = false) { SelectionContainer { Text(location) } } } }
            }
            event.description?.takeIf { it.isNotEmpty() }?.let { notes ->
                item { FormSection("Notes") { FormRow(divider = false) { SelectionContainer { Text(notes) } } } }
            }
            if (event.attendees.isNotEmpty()) item {
                FormSection("Guests (${event.attendees.size})") {
                    event.attendees.forEach { email ->
                        FormRow {
                            Icon(Icons.Filled.Person, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            SelectionContainer { Text(email) }
                        }
                    }
                }
            }
            // Nothing on the server to remind about or attach to.
            if (!event.source.isComputed) {
                item {
                    val eventReminders = all.filter { it.linkedEventId == event.id }.sortedBy { it.due }
                    FormSection("Reminders") {
                        eventReminders.forEach { r ->
                            ReminderRow(r, showsLink = false, onDelete = {
                                if (r.recurrenceRule != null) deletingReminder = r else scope.launch { reminders.delete(r) }
                            }) { router.push(Route.EditReminder(ReminderEditorMode.Edit(r))) }
                        }
                        // The web's presets, timed from this occurrence's start; those used or past are left out.
                        val taken = eventReminders.map { it.due }.toSet()
                        val now = Instant.now()
                        val presets = ReminderPreset.ALL.filter { p -> occurrence.start.minusSeconds(p.minutes * 60L).let { it > now && it !in taken } }
                        Box {
                            ActionRow("Add Reminder", Icons.Filled.NotificationAdd) { addingReminder = true }
                            DropdownMenu(addingReminder, { addingReminder = false }) {
                                presets.forEach { p ->
                                    DropdownMenuItem(text = { Text(p.label) }, onClick = {
                                        addingReminder = false
                                        // Titled after the event, as the web titles the reminders it makes for one.
                                        scope.launch {
                                            runCatching { reminders.create(event.title, occurrence.start.minusSeconds(p.minutes * 60L), null, eventId = event.id) }
                                                .onFailure { reminders.error.value = it.message }
                                        }
                                    })
                                }
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Custom…") }, onClick = {
                                    addingReminder = false
                                    router.push(Route.EditReminder(ReminderEditorMode.Create(eventId = event.id, due = occurrence.start)))
                                })
                            }
                        }
                    }
                }
                item { AttachmentsSection(events.attachmentOwner(event)) }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (choosingEditScope) RecurrenceScopeDialog("Edit Repeating Event", RecurrenceScope.Kind.EVENT, onDismiss = { choosingEditScope = false }) {
        router.push(Route.EditEvent(EventEditorMode.Edit(occurrence, it)))
    }
    deletingReminder?.let { r ->
        RecurrenceScopeDialog("Delete Repeating Reminder", RecurrenceScope.Kind.REMINDER, destructive = true, onDismiss = { deletingReminder = null }) {
            scope.launch { reminders.delete(r, it) }
        }
    }
}

// ── Editor ──────────────────────────────────────────────────────────────────────────────────────

private enum class EndKind(val label: String) { NEVER("Never"), ON("On Date"), AFTER("After") }

/** Creates or edits an event: the web's `NewEventModal`; reminders and attachments are added from its screen once it exists. */
@Composable
fun EventEditorScreen(mode: EventEditorMode, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val events = graph.events
    val calendars = graph.calendars
    val router = graph.router
    val coroutines = rememberCoroutineScope()
    val context = events.context
    val initial = remember(mode) {
        when (mode) {
            is EventEditorMode.Create -> EventDraft.newOn(mode.day, Instant.now(), context)
            is EventEditorMode.Imported -> mode.draft
            is EventEditorMode.Edit -> EventDraft.editing(mode.occurrence, mode.scope, context)
        }
    }
    val occurrence = (mode as? EventEditorMode.Edit)?.occurrence
    val scope = (mode as? EventEditorMode.Edit)?.let { EventDraft.effectiveScope(it.occurrence, it.scope) }
    val existing: CalendarEvent? = occurrence?.let { if (scope == RecurrenceScope.ALL) it.series else it.event }
    var draft by remember(mode) { mutableStateOf(initial) }
    var newGuest by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf<EditConflict?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var pickingZone by remember { mutableStateOf(false) }
    val writable = calendars.writable

    /** A one-off is followed to its new time; a repeating one or a deleted one closes the event screen too. */
    fun finish(saved: CalendarEvent?) {
        router.pop()
        val top = router.current
        if (top is Route.Event) {
            router.pop()
            if (saved != null && !saved.isRecurring && saved.recurringEventId == null && occurrence?.isRepeating != true) {
                router.push(Route.Event(EventOccurrence(saved, saved.start, saved.end)))
            }
        }
    }

    fun save(overwrite: Boolean = false) {
        draft.problem?.let {
            error = it
            return
        }
        saving = true
        error = null
        coroutines.launch {
            try {
                val saved = if (occurrence != null) events.update(occurrence, scope, initial, draft, overwrite) else events.create(draft)
                if (occurrence != null) finish(saved) else router.pop()
            } catch (c: EditConflict) {
                conflict = c
            } catch (e: Exception) {
                error = e.message
            } finally {
                saving = false
            }
        }
    }

    fun delete(deleteScope: RecurrenceScope?) {
        val o = occurrence ?: return
        saving = true
        coroutines.launch {
            try {
                events.delete(o, deleteScope)
                // A task scheduled as this event is no longer on the calendar.
                val id = o.series?.id ?: o.event.id
                if (graph.tasks.tasks.value.any { it.eventId == id }) graph.tasks.reload()
                finish(null)
            } catch (e: Exception) {
                error = e.message
            } finally {
                saving = false
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        PageBar(if (existing == null) "New Event" else "Edit Event", onBack) {
            TextButton(onClick = { save() }, enabled = draft.trimmedTitle.isNotEmpty() && !saving) { Text(if (existing == null) "Add" else "Save") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FormSection {
                PlainField(draft.title, { draft = draft.copy(title = it) }, "Title", capitalize = true)
                PlainField(draft.location, { draft = draft.copy(location = it) }, "Location", divider = false)
            }

            // One occurrence can't move calendar on its own, so a "This event" edit has none.
            if (scope != RecurrenceScope.THIS && writable.size > 1) {
                val current = draft.calendarId ?: calendars.defaultCalendar?.id
                FormSection(footer = when (scope) {
                    RecurrenceScope.ALL -> "Moving it moves every event in the series."
                    RecurrenceScope.FOLLOWING -> "Moving it moves this event and every one after it."
                    else -> null
                }) {
                    ChoiceRow("Calendar", writable.firstOrNull { it.id == current } ?: writable.first(), writable, { it.name },
                        { draft = draft.copy(calendarId = it.id) }) { c -> ColorDot(hexColor(c.color) ?: Color.Gray) }
                }
            }

            FormSection {
                SwitchRow("All day", draft.allDay) { draft = draft.withAllDay(it) }
                // Times are entered in the event's zone, so 09:00 in New York is 09:00 there.
                DateTimeRow("Starts", draft.start, draft.timeZone, draft.allDay) { draft = draft.withStart(it) }
                DateTimeRow("Ends", draft.end, draft.timeZone, draft.allDay, minimum = draft.day(draft.start)) { draft = draft.copy(end = it) }
                if (!draft.allDay) ValueRow("Time Zone", zoneName(draft.timeZone), onClick = { pickingZone = true })
            }

            if (scope == RecurrenceScope.THIS) {
                FormSection(footer = "Changes apply to this event only. The rest of the series stays as it is.") {
                    FormRow(divider = false) { Text(RecurrenceScope.THIS.label(RecurrenceScope.Kind.EVENT), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            } else {
                RepeatSection(draft, initial, scope) { draft = it }
            }

            FormSection("Guests", footer = "Guests are saved with the event, but aren't invited or notified yet.") {
                draft.attendees.forEach { email ->
                    FormRow {
                        Icon(Icons.Filled.Person, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(email, Modifier.weight(1f))
                        IconButton(onClick = { draft = draft.copy(attendees = draft.attendees - email) }) { Icon(Icons.Filled.Close, "Remove $email") }
                    }
                }
                val addGuest = {
                    val added = draft.addingAttendee(newGuest)
                    if (added != null) {
                        draft = added
                        newGuest = ""
                    } else if (newGuest.isNotBlank()) {
                        error = "“$newGuest” isn't an email address, or is already a guest."
                    }
                }
                PlainField(newGuest, { newGuest = it }, "Add guest email", divider = false, keyboard = KeyboardType.Email, onDone = addGuest)
            }

            FormSection("Notes") {
                PlainField(draft.notes, { draft = draft.copy(notes = it) }, "Notes", singleLine = false, divider = false)
            }
            ErrorText(error)
            if (existing != null) FormSection {
                ActionRow("Delete Event", Icons.Filled.Delete, MaterialTheme.colorScheme.error) { confirmingDelete = true }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (pickingZone) TimeZoneDialog(draft.timeZone, { pickingZone = false }) { draft = draft.withTimeZone(it) }
    if (confirmingDelete) {
        if (occurrence?.isRepeating == true) {
            RecurrenceScopeDialog("Delete Event", RecurrenceScope.Kind.EVENT, destructive = true, onDismiss = { confirmingDelete = false }) { delete(it) }
        } else {
            com.neutrino.shared.android.ui.ConfirmDialog("Delete Event", "This event will be deleted on every device.", "Delete", destructive = true,
                onDismiss = { confirmingDelete = false }) { delete(null) }
        }
    }
    conflict?.let { c ->
        ConflictDialog(c, onOverwrite = { conflict = null; save(overwrite = true) }, onDiscard = {
            conflict = null
            // Drops this edit for the change made elsewhere, and shows that.
            coroutines.launch {
                val current = existing?.let { e -> runCatching { events.event(e.id) }.getOrNull() }
                events.pullChanges()
                finish(current)
            }
        })
    }
}

/** How often, and when it stops. A rule the form can't show is kept as it was. */
@Composable
private fun RepeatSection(draft: EventDraft, original: EventDraft, scope: RecurrenceScope?, onChange: (EventDraft) -> Unit) {
    val rule = draft.repeatRule
    val choices = RepeatOption.STANDARD + listOfNotNull(original.repeatOption.takeIf { it is RepeatOption.Custom && original.repeatRule == null })
    FormSection(footer = when (scope) {
        RecurrenceScope.FOLLOWING -> "Changes apply to this event and every one after it."
        RecurrenceScope.ALL -> "Changes apply to every event in the series."
        else -> null
    }) {
        ChoiceRow("Repeat", rule?.preset ?: draft.repeatOption, choices, { it.label }, { choice ->
            onChange(if (choice is RepeatOption.Custom) draft.copy(repeatOption = choice) else draft.withRepeatRule(RepeatRule.with(choice, rule)))
        })
        if (rule != null) {
            Stepper("Every ${rule.interval} ${rule.frequency.unit(rule.interval)}", rule.interval) {
                onChange(draft.withRepeatRule(rule.copy(interval = it)))
            }
            val kind = when (rule.end) {
                is RepeatRule.End.On -> EndKind.ON
                is RepeatRule.End.After -> EndKind.AFTER
                RepeatRule.End.Never -> EndKind.NEVER
            }
            ChoiceRow("End Repeat", kind, EndKind.entries, { it.label }, { k ->
                val end = when (k) {
                    EndKind.NEVER -> RepeatRule.End.Never
                    EndKind.AFTER -> RepeatRule.End.After(10)
                    EndKind.ON -> RepeatRule.End.On(RepeatRule.defaultEndDay(draft.day(draft.start), rule.frequency))
                }
                onChange(draft.withRepeatRule(rule.copy(end = end)))
            })
            when (val end = rule.end) {
                is RepeatRule.End.On -> {
                    var picking by remember { mutableStateOf(false) }
                    ValueRow("End Date", com.neutrino.calendar.ui.mediumDate.format(end.day), onClick = { picking = true })
                    if (picking) com.neutrino.calendar.ui.DayPickerDialog(end.day, { picking = false }, minimum = draft.day(draft.start)) {
                        onChange(draft.withRepeatRule(rule.copy(end = RepeatRule.End.On(it))))
                    }
                }
                is RepeatRule.End.After -> Stepper("After ${end.count} ${if (end.count == 1) "time" else "times"}", end.count) {
                    onChange(draft.withRepeatRule(rule.copy(end = RepeatRule.End.After(it))))
                }
                RepeatRule.End.Never -> Unit
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onChange: (Int) -> Unit) {
    FormRow {
        Text(label, Modifier.weight(1f))
        IconButton(onClick = { onChange(value - 1) }, enabled = value > 1) { Icon(Icons.Filled.Remove, "Fewer") }
        IconButton(onClick = { onChange(value + 1) }, enabled = value < RepeatRule.MAX_NUMBER) { Icon(Icons.Filled.Add, "More") }
    }
}

/** A borderless field inside a form section. */
@Composable
fun PlainField(
    value: String, onChange: (String) -> Unit, placeholder: String, divider: Boolean = true, singleLine: Boolean = true,
    capitalize: Boolean = false, keyboard: KeyboardType = KeyboardType.Text, onDone: (() -> Unit)? = null,
) {
    Column {
        TextField(
            value, onChange, placeholder = { Text(placeholder) }, singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(
                capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
                keyboardType = keyboard, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Default,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        )
        if (divider) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/** "New York (GMT-4)", from the zone's id and its offset now. */
fun zoneName(zone: ZoneId): String {
    val city = zone.id.substringAfterLast('/').replace('_', ' ')
    val seconds = zone.rules.getOffset(Instant.now()).totalSeconds
    if (seconds == 0) return "$city (GMT)"
    val hours = seconds / 3600
    val minutes = Math.abs(seconds / 60 % 60)
    val offset = if (minutes == 0) "%+d".format(hours) else "%+d:%02d".format(hours, minutes)
    return "$city (GMT$offset)"
}

/** Every zone the device knows, searchable by city or region. */
@Composable
fun TimeZoneDialog(selected: ZoneId, onDismiss: () -> Unit, onPick: (ZoneId) -> Unit) {
    var query by remember { mutableStateOf("") }
    val all = remember { ZoneId.getAvailableZoneIds().filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted() }
    val shown = all.filter { query.isBlank() || it.replace('_', ' ').contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Time Zone") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, placeholder = { Text("City or region") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(shown) { id ->
                        val zone = ZoneId.of(id)
                        Column(Modifier.fillMaxWidth().clickable { onPick(zone); onDismiss() }.padding(vertical = 8.dp)) {
                            Text(zoneName(zone), fontWeight = if (id == selected.id) FontWeight.Bold else FontWeight.Normal)
                            Text(id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
