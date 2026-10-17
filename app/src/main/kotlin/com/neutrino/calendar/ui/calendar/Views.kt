package com.neutrino.calendar.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neutrino.calendar.core.logic.CalendarGrid
import com.neutrino.calendar.core.logic.CalendarMode
import com.neutrino.calendar.core.logic.EventFormatting
import com.neutrino.calendar.core.logic.EventOccurrence
import com.neutrino.calendar.core.logic.TimeGridLayout
import com.neutrino.calendar.graph
import com.neutrino.calendar.ui.ErrorText
import com.neutrino.calendar.ui.EventStyle
import com.neutrino.calendar.ui.LocalCompact
import com.neutrino.shared.android.ui.EmptyState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

// ── Month ───────────────────────────────────────────────────────────────────────────────────────

/**
 * A month grid with a dot under each day that has events, and the selected day's events below. Tap
 * a day to select it; swipe the grid sideways for the next or previous month.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthView(open: (EventOccurrence) -> Unit, refresh: () -> Unit) {
    WatchEvents()
    val graph = LocalContext.current.graph
    val events = graph.events
    val focus by events.focus.collectAsState()
    val loading by events.loadingMonths.collectAsState()
    val error by events.error.collectAsState()
    val context = events.context
    val today = events.today
    val compact = LocalCompact.current
    val rules = events.rules
    val primary = MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(horizontal = 8.dp).pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (abs(total) > 60) events.move(CalendarMode.MONTH, if (total < 0) 1 else -1) },
                ) { _, dx -> total += dx }
            },
        ) {
            Row(Modifier.fillMaxWidth()) {
                CalendarGrid.weekdaySymbols(context).forEach {
                    Text(it, Modifier.weight(1f).padding(vertical = 4.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            CalendarGrid.monthDays(focus, context).chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth().padding(vertical = if (compact) 1.dp else 3.dp)) {
                    week.forEach { day ->
                        val inMonth = day.month == focus.month && day.year == focus.year
                        // Only this month is loaded, so its neighbours' days get no dots.
                        val dots = if (inMonth) events.occurrencesOn(day).take(3).map { colorOf(it, rules, primary) } else emptyList()
                        DayCell(day, inMonth, day == today, day == focus, dots, compact, Modifier.weight(1f)) { events.select(day) }
                    }
                }
            }
        }
        HorizontalDivider()
        val occurrences = events.occurrencesOn(focus)
        PullToRefreshBox(isRefreshing = loading.isNotEmpty(), onRefresh = refresh, modifier = Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                item { ErrorText(error) }
                item { DayHeader(focus, today) }
                if (occurrences.isEmpty()) item {
                    Text(if (events.hasLoaded(focus)) "No Events" else "Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
                }
                items(occurrences, key = { it.id }) { EventRow(it) { open(it) } }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun DayCell(day: LocalDate, inMonth: Boolean, isToday: Boolean, isSelected: Boolean, dots: List<Color>, compact: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        val size = if (compact) 28.dp else 32.dp
        Box(
            Modifier.size(size).clip(CircleShape).background(if (isSelected) (if (isToday) primary else MaterialTheme.colorScheme.onSurface) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday || isSelected) FontWeight.SemiBold else FontWeight.Normal,
                // Selected wins over today: the number is light on its circle.
                color = when {
                    isSelected -> MaterialTheme.colorScheme.surface
                    isToday -> primary
                    inMonth -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
            )
        }
        Row(Modifier.height(6.dp).padding(top = 1.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            dots.forEach { Box(Modifier.size(5.dp).clip(CircleShape).background(it)) }
        }
    }
}

// ── Day and week ────────────────────────────────────────────────────────────────────────────────

/**
 * Days as columns over a 24-hour grid: seven for the week view, one for the day view. Timed events
 * are blocks placed by [TimeGridLayout]; all-day ones sit in a row above. Hours outside 08:00–20:00
 * are dimmed, as on the web, and the grid opens at 08:00, or an hour before now on today.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeGridView(days: List<LocalDate>, open: (EventOccurrence) -> Unit, onSelectDay: ((LocalDate) -> Unit)?, refresh: () -> Unit) {
    WatchEvents()
    val graph = LocalContext.current.graph
    val events = graph.events
    val context = events.context
    val today = events.today
    val compact = LocalCompact.current
    val hourHeight = if (compact) 44.dp else 56.dp
    val gutter = 44.dp
    val rules = events.rules
    val primary = MaterialTheme.colorScheme.primary
    val loading by events.loadingMonths.collectAsState()
    val error by events.error.collectAsState()
    val density = LocalDensity.current
    val is24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)

    Column(Modifier.fillMaxSize()) {
        ErrorText(error)
        if (days.size > 1) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Spacer(Modifier.width(gutter))
                days.forEach { day ->
                    val isToday = day == today
                    Column(Modifier.weight(1f).clickable { onSelectDay?.invoke(day) }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.size(28.dp).clip(CircleShape).background(if (isToday) primary else Color.Transparent), contentAlignment = Alignment.Center) {
                            Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                color = if (isToday) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
        // All-day row.
        val perDay = days.map { TimeGridLayout.allDay(events.occurrencesOn(it), it, context) }
        if (perDay.any { it.isNotEmpty() }) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("all-day", Modifier.width(gutter).padding(end = 4.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.End)
                perDay.forEach { list ->
                    Column(Modifier.weight(1f).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        list.take(2).forEach { o -> AllDayChip(o, colorOf(o, rules, primary)) { open(o) } }
                        if (list.size > 2) Text("+${list.size - 2}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        HorizontalDivider()
        // A fresh scroll state per day or week, so moving opens it at its own starting hour.
        androidx.compose.runtime.key(days.first()) {
            val scroll = rememberScrollState()
            LaunchedEffect(Unit) {
                var hour = DAY_START_HOUR
                if (days.contains(today)) hour = maxOf(0, LocalTime.now(context.zone).hour - 1)
                scroll.scrollTo(with(density) { (hourHeight * hour).roundToPx() })
            }
            PullToRefreshBox(isRefreshing = loading.isNotEmpty(), onRefresh = refresh, modifier = Modifier.weight(1f)) {
                Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    Column(Modifier.width(gutter)) {
                        (0 until 24).forEach { hour ->
                            Box(Modifier.height(hourHeight).fillMaxWidth().padding(end = 6.dp), contentAlignment = Alignment.TopEnd) {
                                if (hour > 0) Text(hourLabel(hour, is24), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.offset(y = (-7).dp))
                            }
                        }
                    }
                    days.forEach { day ->
                        DayColumn(day, TimeGridLayout.layout(events.occurrencesOn(day), day, context), hourHeight, days.size == 1, Modifier.weight(1f), open)
                    }
                }
            }
        }
    }
}

private const val DAY_START_HOUR = 8
private const val DAY_END_HOUR = 20

private fun hourLabel(hour: Int, is24: Boolean): String =
    DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h a", Locale.getDefault()).format(LocalTime.of(hour, 0))

@Composable
private fun AllDayChip(o: EventOccurrence, color: Color, onClick: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    val task = o.task?.let { graph.tasks.task(it.id) ?: it }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.2f)).clickable(onClick = onClick).padding(horizontal = 3.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (task != null) {
            Icon(if (task.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null, Modifier.size(12.dp).clickable { scope.launch { graph.tasks.setDone(task, !task.done) } },
                tint = EventStyle.taskColor)
            Spacer(Modifier.width(2.dp))
        }
        Text(o.event.title, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textDecoration = if (task?.done == true) TextDecoration.LineThrough else null)
    }
}

@Composable
private fun DayColumn(day: LocalDate, placed: List<TimeGridLayout.Placed>, hourHeight: androidx.compose.ui.unit.Dp, showsTime: Boolean, modifier: Modifier, open: (EventOccurrence) -> Unit) {
    val graph = LocalContext.current.graph
    val rules = graph.events.rules
    val primary = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.06f)
    val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val scope = rememberCoroutineScope()
    BoxWithConstraints(modifier.height(hourHeight * 24)) {
        val width = maxWidth
        Column {
            (0 until 24).forEach { hour ->
                Box(Modifier.height(hourHeight).fillMaxWidth().background(if (hour < DAY_START_HOUR || hour >= DAY_END_HOUR) dim else Color.Transparent)) {
                    HorizontalDivider(color = line)
                }
            }
        }
        Box(Modifier.width(0.5.dp).height(hourHeight * 24).background(line))
        placed.forEach { block ->
            val columnWidth = width / block.columns
            val top = hourHeight * (block.startMinute / 60).toFloat()
            val height = hourHeight * ((block.endMinute - block.startMinute) / 60).toFloat() - 1.dp
            val o = block.occurrence
            val color = colorOf(o, rules, primary)
            val task = o.task?.let { graph.tasks.task(it.id) ?: it }
            Row(
                Modifier.offset(x = columnWidth * block.column + 1.dp, y = top).width(columnWidth - 2.dp).height(height)
                    .clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.18f)).clickable { open(o) },
            ) {
                Box(Modifier.width(3.dp).fillMaxSize().background(color)) {}
                Column(Modifier.padding(horizontal = 3.dp, vertical = 2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (task != null) {
                            Icon(if (task.done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                                Modifier.size(12.dp).clickable { scope.launch { graph.tasks.setDone(task, !task.done) } }, tint = EventStyle.taskColor)
                            Spacer(Modifier.width(2.dp))
                        }
                        Text(o.event.title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = if (height > 40.dp) 2 else 1,
                            overflow = TextOverflow.Ellipsis, textDecoration = if (task?.done == true) TextDecoration.LineThrough else null)
                    }
                    if (showsTime && height > 30.dp) Text(EventFormatting.timeSummary(o), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        NowLine(day, hourHeight)
    }
}

/** A red line at the current time across today's column, moved once a minute. */
@Composable
private fun NowLine(day: LocalDate, hourHeight: androidx.compose.ui.unit.Dp) {
    val graph = LocalContext.current.graph
    val now by produceState(Instant.now()) {
        while (true) {
            delay(60_000)
            value = Instant.now()
        }
    }
    val minute = TimeGridLayout.nowMinute(now, day, graph.events.context) ?: return
    val red = Color(0xFFFF3B30)
    Row(Modifier.offset(y = hourHeight * (minute / 60).toFloat() - 3.5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(red))
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(red))
    }
}

// ── Year ────────────────────────────────────────────────────────────────────────────────────────

/** The focused year as twelve small months. Tap a month to open it; today is circled. No events load here. */
@Composable
fun YearView(onSelectMonth: (LocalDate) -> Unit) {
    WatchEvents()
    val graph = LocalContext.current.graph
    val events = graph.events
    val focus by events.focus.collectAsState()
    val context = events.context
    val today = events.today
    val primary = MaterialTheme.colorScheme.primary
    LazyVerticalGrid(
        GridCells.Fixed(3), Modifier.fillMaxSize().padding(12.dp).pointerInput(Unit) {
            var total = 0f
            detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (abs(total) > 60) events.move(CalendarMode.YEAR, if (total < 0) 1 else -1) }) { _, dx -> total += dx }
        },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items((1..12).map { LocalDate.of(focus.year, it, 1) }) { month ->
            val isThisMonth = month.year == today.year && month.month == today.month
            Column(Modifier.clickable { onSelectMonth(month) }) {
                Text(month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()), style = MaterialTheme.typography.titleSmall,
                    color = if (isThisMonth) primary else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                CalendarGrid.monthDays(month, context).chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            val inMonth = day.month == month.month
                            val isToday = inMonth && day == today
                            Box(Modifier.weight(1f).height(13.dp).clip(CircleShape).background(if (isToday) primary else Color.Transparent), contentAlignment = Alignment.Center) {
                                Text(if (inMonth) day.dayOfMonth.toString() else "", fontSize = 8.sp,
                                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isToday) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Agenda ──────────────────────────────────────────────────────────────────────────────────────

/** One month as a list: the days with events, each event on every day it covers. The web's Agenda. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgendaView(open: (EventOccurrence) -> Unit, refresh: () -> Unit) {
    WatchEvents()
    val graph = LocalContext.current.graph
    val events = graph.events
    val focus by events.focus.collectAsState()
    val loading by events.loadingMonths.collectAsState()
    val error by events.error.collectAsState()
    val sections = events.sections
    val today = events.today
    val list = rememberLazyListState()
    var scrolledIn by remember { mutableStateOf<LocalDate?>(null) }

    // Opens the month at the focused day, or the next day with events, rather than at the 1st; once
    // per month, so a refresh doesn't move the list.
    LaunchedEffect(sections, focus) {
        if (scrolledIn == focus.withDayOfMonth(1)) return@LaunchedEffect
        var index = 1
        for (s in sections) {
            if (s.day >= focus) {
                scrolledIn = focus.withDayOfMonth(1)
                list.scrollToItem(index)
                break
            }
            index += 1 + s.occurrences.size
        }
    }

    PullToRefreshBox(isRefreshing = loading.isNotEmpty(), onRefresh = refresh, modifier = Modifier.fillMaxSize()) {
        if (sections.isEmpty()) {
            when {
                loading.isNotEmpty() || (!events.hasLoaded(focus) && error == null) -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    EmptyState(Icons.Filled.CalendarMonth, "Couldn't Load Events", error!!, Modifier.height(320.dp))
                    TextButton(onClick = refresh) { Text("Try Again") }
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    EmptyState(Icons.Filled.CalendarMonth, "No Events", "Nothing is scheduled this month.", Modifier.height(360.dp))
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = list) {
                // Keep what is on screen and say the refresh failed, rather than replace a usable list.
                item { ErrorText(error) }
                sections.forEach { section ->
                    item(key = "h-${section.day}") { DayHeader(section.day, today) }
                    items(section.occurrences, key = { "${section.day}-${it.id}" }) { EventRow(it) { open(it) } }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
