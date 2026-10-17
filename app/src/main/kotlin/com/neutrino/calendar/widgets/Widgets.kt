package com.neutrino.calendar.widgets

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.neutrino.calendar.MainActivity
import com.neutrino.calendar.core.logic.CalendarGrid
import com.neutrino.calendar.core.logic.EventFormatting
import com.neutrino.calendar.core.logic.WidgetEvent
import com.neutrino.calendar.core.logic.WidgetSnapshot
import com.neutrino.calendar.surfaces.SystemSurfaces
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.format.TextStyle as JTextStyle

/**
 * The home-screen widgets: Up Next, Today and Month — the iOS app's widgets, drawn with Glance.
 * No network and no secrets: everything they show comes from the snapshot the app writes
 * ([SystemSurfaces]), which holds titles, times, places and colours and nothing else.
 */
object Widgets {
    suspend fun updateAll(context: Context) {
        runCatching { UpNextWidget().updateAll(context) }
        runCatching { TodayWidget().updateAll(context) }
        runCatching { MonthWidget().updateAll(context) }
    }

    val accent = Color(0xFFAF52DE)
    val text = ColorProvider(Color(0xFF1C1C1E), Color(0xFFF2F2F7))
    val secondary = ColorProvider(Color(0xFF6C6C70), Color(0xFFAEAEB2))
    val surface = ColorProvider(Color(0xFFFFFFFF), Color(0xFF1C1C1E))
    val accentProvider = ColorProvider(accent, Color(0xFFDA8FFF))

    fun open(context: Context, uri: String): Action =
        actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java))

    fun eventUri(e: WidgetEvent) = "neutrinocalendar://event/${Uri.encode(e.id)}"

    fun color(hex: String?): Color = hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)
        ?.let { Color(0xFF000000 or it) } ?: accent

    fun time(e: WidgetEvent, snapshot: WidgetSnapshot): String =
        if (e.allDay) "All day" else EventFormatting.shortTime(e.start, snapshot.context.zone)
}

@Composable
private fun Frame(context: Context, uri: String, content: @Composable () -> Unit) {
    Box(
        GlanceModifier.fillMaxSize().background(Widgets.surface).cornerRadius(16.dp).padding(12.dp).clickable(Widgets.open(context, uri)),
    ) { content() }
}

@Composable
private fun SignedOut() {
    Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Calendar", style = TextStyle(fontWeight = FontWeight.Bold, color = Widgets.accentProvider))
        Text("Sign in to see your events", style = TextStyle(fontSize = 12.sp, color = Widgets.secondary, textAlign = TextAlign.Center))
    }
}

@Composable
private fun EventLine(context: Context, e: WidgetEvent, snapshot: WidgetSnapshot) {
    Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(Widgets.open(context, Widgets.eventUri(e))), verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.width(3.dp).height(28.dp).background(Widgets.color(e.color)).cornerRadius(2.dp)) {}
        Spacer(GlanceModifier.width(6.dp))
        Column {
            Text(e.title, maxLines = 1, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Widgets.text))
            Text(Widgets.time(e, snapshot) + (e.location?.let { " · $it" } ?: ""), maxLines = 1, style = TextStyle(fontSize = 11.sp, color = Widgets.secondary))
        }
    }
}

/** The next timed event, or the one under way; all-day events are the day, not a thing in it. */
class UpNextWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = SystemSurfaces.readSnapshot(context)
        provideContent {
            GlanceTheme {
                val now = Instant.now()
                val next = snapshot?.next(now)
                Frame(context, next?.let(Widgets::eventUri) ?: "neutrinocalendar://day/${LocalDate.now()}") {
                    when {
                        snapshot == null || !snapshot.signedIn -> SignedOut()
                        next == null -> Column {
                            Text("Up next", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Widgets.accentProvider))
                            Spacer(GlanceModifier.height(6.dp))
                            Text("Nothing coming up", style = TextStyle(fontSize = 13.sp, color = Widgets.secondary))
                        }
                        else -> Column {
                            Text(if (next.start <= now) "Now" else "Up next", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Widgets.accentProvider))
                            Spacer(GlanceModifier.height(4.dp))
                            Text(next.title, maxLines = 2, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Widgets.text))
                            val day = snapshot.context.day(next.start)
                            val dayText = if (day == snapshot.context.day(now)) "" else day.dayOfWeek.getDisplayName(JTextStyle.SHORT, Locale.getDefault()) + " "
                            Text(dayText + EventFormatting.shortTime(next.start, snapshot.context.zone) + " – " + EventFormatting.shortTime(next.end, snapshot.context.zone),
                                style = TextStyle(fontSize = 12.sp, color = Widgets.secondary))
                            next.location?.let { Text(it, maxLines = 1, style = TextStyle(fontSize = 12.sp, color = Widgets.secondary)) }
                            snapshot.filterSummary?.let { Text("Showing $it", maxLines = 1, style = TextStyle(fontSize = 10.sp, color = Widgets.secondary)) }
                        }
                    }
                }
            }
        }
    }
}

/** Today's events, all-day first. */
class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = SystemSurfaces.readSnapshot(context)
        provideContent {
            GlanceTheme {
                val today = snapshot?.context?.day(Instant.now()) ?: LocalDate.now()
                Frame(context, "neutrinocalendar://day/$today") {
                    if (snapshot == null || !snapshot.signedIn) {
                        SignedOut()
                    } else {
                        val now = Instant.now()
                        val events = snapshot.eventsOn(today).filter { it.allDay || it.end > now }
                        Column {
                            Text(today.dayOfWeek.getDisplayName(JTextStyle.FULL, Locale.getDefault()).uppercase(), style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Widgets.accentProvider))
                            Text(today.dayOfMonth.toString(), style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Widgets.text))
                            if (events.isEmpty()) Text("No more events today", style = TextStyle(fontSize = 12.sp, color = Widgets.secondary))
                            events.take(4).forEach { EventLine(context, it, snapshot) }
                            if (events.size > 4) Text("+${events.size - 4} more", style = TextStyle(fontSize = 11.sp, color = Widgets.secondary))
                        }
                    }
                }
            }
        }
    }
}

/** This month, with a dot under each busy day; a day opens the calendar on it. */
class MonthWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = SystemSurfaces.readSnapshot(context)
        provideContent {
            GlanceTheme {
                val ctx = snapshot?.context
                val today = ctx?.day(Instant.now()) ?: LocalDate.now()
                Frame(context, "neutrinocalendar://day/$today") {
                    if (snapshot == null || ctx == null || !snapshot.signedIn) {
                        SignedOut()
                    } else {
                        Column(GlanceModifier.fillMaxSize()) {
                            Text(DateTimeFormatter.ofPattern("LLLL", Locale.getDefault()).format(today), style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Widgets.accentProvider))
                            Row(GlanceModifier.fillMaxWidth()) {
                                CalendarGrid.weekdaySymbols(ctx).forEach {
                                    Text(it, modifier = GlanceModifier.defaultWeight(), style = TextStyle(fontSize = 9.sp, color = Widgets.secondary, textAlign = TextAlign.Center))
                                }
                            }
                            CalendarGrid.monthDays(today, ctx).chunked(7).forEach { week ->
                                Row(GlanceModifier.fillMaxWidth().defaultWeight()) {
                                    week.forEach { day ->
                                        val inMonth = day.month == today.month
                                        Column(
                                            GlanceModifier.defaultWeight().clickable(Widgets.open(context, "neutrinocalendar://day/$day")),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                        ) {
                                            val isToday = day == today
                                            Box(
                                                (if (isToday) GlanceModifier.background(Widgets.accent).cornerRadius(9.dp) else GlanceModifier).size(18.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text(
                                                    if (inMonth) day.dayOfMonth.toString() else "",
                                                    style = TextStyle(
                                                        fontSize = 10.sp, textAlign = TextAlign.Center,
                                                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (isToday) ColorProvider(Color.White, Color.White) else Widgets.text,
                                                    ),
                                                )
                                            }
                                            if (inMonth && day.toString() in snapshot.busyDays) {
                                                Box(GlanceModifier.size(4.dp).background(Widgets.accent).cornerRadius(2.dp)) {}
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class UpNextWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UpNextWidget()
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}

class MonthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonthWidget()
}
