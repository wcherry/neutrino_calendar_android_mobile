package com.neutrino.calendar.ui.tasks

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.neutrino.calendar.Route
import com.neutrino.calendar.core.logic.NearbyTasks
import com.neutrino.calendar.core.logic.SmartAdd
import com.neutrino.calendar.core.logic.SmartAddResult
import com.neutrino.calendar.core.logic.TaskFilter
import com.neutrino.calendar.core.logic.TaskSpeech
import com.neutrino.calendar.core.model.CalendarTask
import com.neutrino.calendar.core.model.GeoPoint
import com.neutrino.calendar.core.model.TaskGeofence
import com.neutrino.calendar.core.model.TaskTags
import com.neutrino.calendar.geofence.LocationAccess
import com.neutrino.calendar.geofence.PlaceSearch
import com.neutrino.calendar.geofence.PlaceSearchResult
import com.neutrino.calendar.graph
import com.neutrino.calendar.ui.ErrorText
import com.neutrino.calendar.ui.FormRow
import com.neutrino.calendar.ui.FormSection
import com.neutrino.calendar.ui.PageBar
import kotlinx.coroutines.launch
import java.time.Instant

/** Asks for fine location, in context: when a place is picked or Nearby is turned on. */
@Composable
fun rememberLocationPermission(onResult: () -> Unit = {}): () -> Unit {
    val graph = LocalContext.current.graph
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        graph.geofences.markAsked()
        onResult()
    }
    return { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
}

/**
 * Asks for "Allow all the time" once a task has a place: Android 11 and later send the user to the
 * permission page for it. And notifications, without which an arrival can't be shown.
 */
@Composable
fun rememberArrivalPermissions(): () -> Unit {
    val graph = LocalContext.current.graph
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        graph.geofences.refreshAccess()
        graph.geofences.apply()
    }
    val fine = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        graph.geofences.markAsked()
        if (graph.geofences.access.value == LocationAccess.WHILE_USING && android.os.Build.VERSION.SDK_INT >= 29) {
            background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { graph.prefs.askedForNotifications = true }
    return {
        if (!graph.alarms.hasPermission && android.os.Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        when (graph.geofences.access.value) {
            LocationAccess.NOT_ASKED, LocationAccess.OFF -> fine.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            LocationAccess.WHILE_USING -> if (android.os.Build.VERSION.SDK_INT >= 29) background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            LocationAccess.ALWAYS -> graph.geofences.apply()
        }
    }
}

/**
 * The Tasks tab: a box to type a task into (Smart Add), the open tasks in the order they were
 * arranged, and the done ones after. One flat list, as on the web. Search and a filter menu narrow
 * it; Nearby lists open tasks with a place near you.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen() {
    val context = LocalContext.current
    val graph = context.graph
    val tasks = graph.tasks
    val places = graph.places
    val router = graph.router
    val scope = rememberCoroutineScope()
    val all by tasks.tasks.collectAsState()
    val loading by tasks.isLoading.collectAsState()
    val loaded by tasks.hasLoaded.collectAsState()
    val error by tasks.error.collectAsState()
    val savedPlaces by places.places.collectAsState()
    val unreadable by places.unreadable.collectAsState()
    val access by graph.geofences.access.collectAsState()
    var newTitle by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(TaskFilter()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var reordering by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    var here by remember { mutableStateOf<GeoPoint?>(null) }
    var locating by remember { mutableStateOf(false) }
    /** A map result for an `@place` that named no saved place, waiting for a yes. */
    var offer by remember { mutableStateOf<Pair<CalendarTask, PlaceSearchResult>?>(null) }
    val composer = remember { FocusRequester() }
    val askArrivals = rememberArrivalPermissions()
    val askLocation = rememberLocationPermission { scope.launch { here = graph.geofences.currentLocation() } }
    val ctx = graph.events.context

    LaunchedEffect(Unit) {
        tasks.reload()
        if (!places.hasLoaded.value) places.reload()
    }
    LaunchedEffect(router.openTaskId, all) {
        val id = router.openTaskId ?: return@LaunchedEffect
        if (tasks.task(id) == null) return@LaunchedEffect
        router.openTaskId = null
        router.popWhile { true }
        router.push(Route.Task(id))
    }
    LaunchedEffect(router.newTask) {
        if (router.newTask) {
            router.newTask = false
            composer.requestFocus()
        }
    }
    LaunchedEffect(filter.nearby) {
        if (filter.nearby == null) return@LaunchedEffect
        if (graph.geofences.access.value == LocationAccess.NOT_ASKED) askLocation()
        locating = true
        here = graph.geofences.currentLocation()
        locating = false
    }

    val parsed: SmartAddResult? = newTitle.trim().takeIf { it.isNotEmpty() }?.let { SmartAdd.parse(it, SmartAdd.Context.current(Instant.now(), ctx)) }
    val shown = filter.apply(all, Instant.now(), ctx)
    val open = shown.filter { !it.done }
    val done = shown.filter { it.done }

    /** Adds the task and keeps the box focused; what was typed stays if the add fails. */
    fun add() {
        val p = parsed ?: return
        if (adding) return
        if (p.title.isEmpty()) {
            addError = "Add a title as well as the details."
            return
        }
        adding = true
        addError = null
        offer = null
        scope.launch {
            try {
                var request = SmartAdd.request(p, ctx)
                val place = p.location?.let(places::match)
                if (place != null) request = request.copy(geoPlaceId = place.id)
                val created = tasks.create(request)
                if (place != null) {
                    askArrivals()
                } else if (p.location != null) {
                    // In the background, so a slow search never holds up typing the next task.
                    launch {
                        runCatching { PlaceSearch.search(context, p.location!!, graph.geofences.location.value).firstOrNull() }.getOrNull()
                            ?.let { offer = created to it }
                    }
                }
                newTitle = ""
                composer.requestFocus()
            } catch (e: Exception) {
                addError = "Couldn't add that task. Please try again."
            } finally {
                adding = false
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageBar("Tasks", null) {
            IconButton(onClick = { searching = !searching; if (!searching) filter = filter.copy(text = "") }) { Icon(Icons.Filled.Search, "Search tasks") }
            if (tasks.open.size > 1 && !filter.isActive) {
                IconButton(onClick = { reordering = !reordering }) { Icon(Icons.Filled.SwapVert, if (reordering) "Done reordering" else "Reorder") }
            }
            if (all.isNotEmpty()) FilterMenu(filter, tasks.allTags) { filter = it }
        }
        if (searching) OutlinedTextField(filter.text, { filter = filter.copy(text = it) }, placeholder = { Text("Search tasks") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        PullToRefreshBox(isRefreshing = loading, onRefresh = { scope.launch { tasks.reload() } }, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    FormSection {
                        OutlinedTextField(
                            newTitle, { newTitle = it }, placeholder = { Text("Add a task…") }, singleLine = true, enabled = !adding,
                            modifier = Modifier.fillMaxWidth().padding(8.dp).focusRequester(composer).onFocusChanged { focused = it.isFocused },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onDone = { add() }),
                        )
                        if (parsed != null && parsed.hasDetails) {
                            SmartAddPreview(parsed, parsed.location?.let(places::match)?.name)
                        } else if (focused && newTitle.isEmpty()) {
                            Text("Try ^fri 3pm #tag !1 *weekly", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
                        }
                        ErrorText(addError)
                        offer?.let { (task, result) ->
                            Column(Modifier.padding(16.dp)) {
                                Text("Remind you when you arrive at ${result.name}?", style = MaterialTheme.typography.bodyMedium)
                                if (result.detail.isNotEmpty()) Text(result.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        offer = null
                                        scope.launch {
                                            runCatching { tasks.setGeofence(tasks.task(task.id) ?: task, TaskGeofence.Point(result.point)) }
                                                .onSuccess { askArrivals() }.onFailure { addError = "Couldn't add the place to that task." }
                                        }
                                    }) { Text("Remind Me") }
                                    OutlinedButton(onClick = { offer = null }) { Text("Not Now") }
                                }
                            }
                        }
                    }
                }
                item { ErrorText(error) }
                if (filter.isActive && all.isNotEmpty()) item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (shown.isEmpty()) "No tasks match" else "Showing ${shown.size} of ${all.size}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = { filter = TaskFilter() }) { Text("Clear") }
                    }
                }
                if (all.isEmpty()) item {
                    if (loading && !loaded) Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else Text("No tasks — type one above", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(24.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                val range = filter.nearby
                if (range != null) item {
                    FormSection("Nearby", footer = if (unreadable > 0) "Tasks at $unreadable saved place(s) this phone can't decrypt aren't shown." else null) {
                        val point = here
                        when {
                            point != null -> {
                                val entries = NearbyTasks.list(shown, savedPlaces, point, range.meters)
                                entries.forEach { e -> TaskRow(e.task, e.distance) { router.push(Route.Task(e.task.id)) } }
                                if (entries.isEmpty()) FormRow(divider = false) { Text("No open tasks with a place ${range.title.lowercase()}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            locating -> FormRow(divider = false) { CircularProgressIndicator(Modifier.size(20.dp)) }
                            else -> FormRow(divider = false) {
                                Text(if (access == LocationAccess.OFF) "Turn on location access for Calendar to see tasks near you." else "Your location isn't available right now.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                } else if (open.isNotEmpty()) item {
                    FormSection {
                        open.forEachIndexed { i, task ->
                            if (reordering && !filter.isActive) {
                                FormRow {
                                    Text(task.title, Modifier.weight(1f))
                                    IconButton(onClick = { move(graph, open, i, -1) }, enabled = i > 0) { Icon(Icons.Filled.ArrowUpward, "Move up") }
                                    IconButton(onClick = { move(graph, open, i, 1) }, enabled = i < open.lastIndex) { Icon(Icons.Filled.ArrowDownward, "Move down") }
                                }
                            } else {
                                TaskRow(task) { router.push(Route.Task(task.id)) }
                            }
                        }
                    }
                }
                if (done.isNotEmpty() && range == null) item {
                    FormSection("Done") { done.forEach { task -> TaskRow(task) { router.push(Route.Task(task.id)) } } }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** Reordering sends the order of every open task, so it is offered only when the whole list shows. */
private fun move(graph: com.neutrino.calendar.AppGraph, open: List<CalendarTask>, index: Int, by: Int) {
    val ids = open.map { it.id }.toMutableList()
    val target = index + by
    if (target !in ids.indices) return
    ids.add(target, ids.removeAt(index))
    graph.scope.launch { graph.tasks.reorderOpen(ids) }
}

@Composable
private fun FilterMenu(filter: TaskFilter, allTags: List<String>, onChange: (TaskFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.FilterList, "Filter", tint = if (filter.hasMenuFilters) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(open, { open = false }) {
            Text("Due", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            TaskFilter.Due.entries.forEach { d ->
                DropdownMenuItem(text = { Text(d.title, fontWeight = if (filter.due == d) FontWeight.Bold else FontWeight.Normal) }, onClick = { onChange(filter.copy(due = d)) })
            }
            HorizontalDivider()
            listOf(null to "Any priority", 1 to "High", 2 to "Medium", 3 to "Low").forEach { (p, label) ->
                DropdownMenuItem(text = { Text(label, fontWeight = if (filter.priority == p) FontWeight.Bold else FontWeight.Normal) }, onClick = { onChange(filter.copy(priority = p)) })
            }
            // A tag picked and then removed from every task stays listed, so it can be unpicked.
            val tags = allTags + (filter.tags - allTags.toSet()).sorted()
            if (tags.isNotEmpty()) {
                HorizontalDivider()
                tags.forEach { tag ->
                    DropdownMenuItem(
                        text = { Text("#$tag") },
                        leadingIcon = { Checkbox(tag in filter.tags, null) },
                        onClick = { onChange(filter.copy(tags = if (tag in filter.tags) filter.tags - tag else filter.tags + tag)) },
                    )
                }
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Anywhere", fontWeight = if (filter.nearby == null) FontWeight.Bold else FontWeight.Normal) }, onClick = { onChange(filter.copy(nearby = null)) })
            NearbyTasks.Range.entries.forEach { r ->
                DropdownMenuItem(text = { Text(r.title, fontWeight = if (filter.nearby == r) FontWeight.Bold else FontWeight.Normal) }, onClick = { onChange(filter.copy(nearby = r)) })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Show done") }, leadingIcon = { Checkbox(filter.showDone, null) }, onClick = { onChange(filter.copy(showDone = !filter.showDone)) })
            if (filter.hasMenuFilters) DropdownMenuItem(text = { Text("Clear filters", color = MaterialTheme.colorScheme.error) }, onClick = {
                onChange(TaskFilter(text = filter.text))
                open = false
            })
        }
    }
}

/** A task: its checkbox, title, and what it carries that the title can't say. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskRow(task: CalendarTask, distance: Double? = null, onClick: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val ctx = graph.events.context
    FormRow(onClick = onClick) {
        Icon(
            if (task.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            if (task.done) "Mark as not done" else "Mark as done",
            tint = if (task.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp).clickable { scope.launch { graph.tasks.setDone(task, !task.done) } },
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(task.title, textDecoration = if (task.done) TextDecoration.LineThrough else null,
                color = if (task.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            val parts = listOfNotNull(
                task.priority?.let { "!$it" to Icons.Filled.Flag },
                TaskSpeech.dueText(task, Instant.now(), ctx)?.let { it to Icons.Filled.Schedule },
                if (task.recurrenceRule != null) "Repeats" to Icons.Filled.Repeat else null,
                distance?.let { NearbyTasks.format(it) to Icons.Filled.MyLocation },
                task.location?.let { it to (if (task.geofence == null) Icons.Filled.LocationOn else Icons.Filled.MyLocation) }
                    ?: if (task.geofence != null) "Place" to Icons.Filled.MyLocation else null,
                if (task.eventId != null) "On calendar" to Icons.Filled.CalendarMonth else null,
                if (task.notes != null) "Notes" to Icons.AutoMirrored.Filled.Notes else null,
            )
            if (parts.isNotEmpty() || task.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    parts.forEach { (text, icon) -> Meta(text, icon) }
                    if (task.tags.isNotEmpty()) Text(task.tags.joinToString(" ") { "#$it" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Meta(text: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(2.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** One chip per field Smart Add found, so a date picked up from the title is visible before Return commits it. */
@Composable
fun SmartAddPreview(parsed: SmartAddResult, place: String?) {
    val chips = buildList {
        parsed.due?.let { add(TaskSpeech.formatSmartDate(it) to Icons.Filled.Event) }
        parsed.start?.let { add("starts ${TaskSpeech.formatSmartDate(it)}" to Icons.Filled.PlayCircle) }
        parsed.priority?.let { add("Priority $it" to Icons.Filled.Flag) }
        parsed.tags.forEach { add("#$it" to Icons.Filled.Tag) }
        parsed.recurrenceRule?.let { add(SmartAdd.describeRepeat(it, parsed.repeatAfterCompletion) to Icons.Filled.Repeat) }
        parsed.estimateMinutes?.let { add(SmartAdd.formatEstimate(it) to Icons.Filled.HourglassBottom) }
        if (place != null) add(place to Icons.Filled.MyLocation) else parsed.location?.let { add(it to Icons.Filled.LocationOn) }
        parsed.note?.let { add(it to Icons.AutoMirrored.Filled.Notes) }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        chips.forEach { (text, icon) ->
            Row(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(text, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * A task's tags: the chosen ones as removable chips, a field to type one, and while it has focus the
 * tags already in use that match. Return, a space or a comma adds whatever was typed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagField(tags: List<String>, onTags: (List<String>) -> Unit, draft: String, onDraft: (String) -> Unit, known: List<String>) {
    var focused by remember { mutableStateOf(false) }
    fun commit(text: String) {
        onTags(tags + TaskTags.split(text).filter { it !in tags })
        onDraft("")
    }
    Column {
        if (tags.isNotEmpty()) FlowRow(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tags.forEach { tag ->
                Row(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)).padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("#$tag", style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { onTags(tags - tag) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Close, "Remove $tag", Modifier.size(14.dp)) }
                }
            }
        }
        OutlinedTextField(
            draft,
            { text ->
                // A separator finishes the tag before it, as the web's tag field splits on spaces and commas.
                val last = text.indexOfLast { it.isWhitespace() || it == ',' }
                if (last >= 0) {
                    onTags(tags + TaskTags.split(text.substring(0, last)).filter { it !in tags })
                    onDraft(text.substring(last + 1))
                } else onDraft(text)
            },
            placeholder = { Text("Add a tag") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(8.dp).onFocusChanged { focused = it.isFocused },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { commit(draft) }),
        )
        if (focused) {
            val suggestions = TaskTags.suggestions(draft, known, tags).take(8)
            suggestions.forEach { tag ->
                FormRow(onClick = { onTags(tags + tag); onDraft("") }) { Icon(Icons.Filled.Tag, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)); Text("#$tag") }
            }
            val typed = TaskTags.split(draft).lastOrNull()
            if (typed != null && typed !in tags && typed !in suggestions) {
                FormRow(onClick = { commit(draft) }) { Text("Add “#$typed”", color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}
