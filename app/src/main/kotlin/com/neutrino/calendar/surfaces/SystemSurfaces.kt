package com.neutrino.calendar.surfaces

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.neutrino.calendar.CalendarPrefs
import com.neutrino.calendar.R
import com.neutrino.calendar.core.logic.EventLink
import com.neutrino.calendar.core.logic.EventOccurrence
import com.neutrino.calendar.core.logic.EventsLayout
import com.neutrino.calendar.core.logic.UpNext
import com.neutrino.calendar.core.logic.WidgetSnapshot
import com.neutrino.calendar.core.model.CalendarJson
import com.neutrino.calendar.core.model.EventFilter
import com.neutrino.calendar.core.service.EventsService
import com.neutrino.calendar.notifications.ReminderAlarmReceiver
import com.neutrino.calendar.notifications.ReminderAlarms
import com.neutrino.calendar.widgets.Widgets
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Everything Android shows of the calendar outside the app: the home-screen widgets and the
 * next-event notification (the iOS Live Activity). One fetch feeds both, whenever the calendar
 * changes here or elsewhere, and from background refresh. The iOS `SystemSurfaces`, less Spotlight,
 * which has no Android counterpart a third-party app can fill.
 *
 * The fetch runs from the first of this month (the month widget needs it) to the end of next month.
 * A change that needs no network (the source filter, the week start, the switch) redraws from the
 * last fetch.
 */
class SystemSurfaces(
    private val context: Context,
    private val events: EventsService,
    private val prefs: CalendarPrefs,
    private val isSignedIn: () -> Boolean,
) {
    private val snapshotFile = File(context.filesDir, WidgetSnapshot.FILE_NAME)
    private var fetched: List<EventOccurrence>? = null
    private var running = false
    private var runAgain = false
    private var lastWritten: WidgetSnapshot? = null

    /** Fetches and updates everything. A failed fetch leaves things as they were: a stale widget beats an empty one. */
    suspend fun refresh() {
        if (running) {
            runAgain = true
            return
        }
        running = true
        try {
            do {
                runAgain = false
                if (!isSignedIn()) return
                val context = events.context
                val today = context.day(events.currentInstant)
                val first = today.withDayOfMonth(1)
                val endOfNextMonth = EventsLayout.monthRange(first.plusMonths(1), context).second
                val horizon = context.startOf(today.plusDays(30))
                val occurrences = runCatching { events.occurrences(context.startOf(first), maxOf(endOfNextMonth, horizon)) }.getOrNull() ?: return
                if (!isSignedIn()) return
                fetched = occurrences
                publish(occurrences)
            } while (runAgain)
        } finally {
            running = false
        }
    }

    /** Redraws from the last fetch. */
    suspend fun redraw() {
        val f = fetched ?: return refresh()
        if (isSignedIn()) publish(f)
    }

    /** Signed out, or never signed in: nothing of an account may be left on show. */
    suspend fun signOut() {
        fetched = null
        write(WidgetSnapshot.signedOut())
        NextEvent.cancel(context)
    }

    private suspend fun publish(occurrences: List<EventOccurrence>) {
        val now = events.currentInstant
        val context = events.context
        val filter = events.filter
        write(WidgetSnapshot.build(occurrences, now, context, filter))
        val upcoming = UpNext.upcoming(occurrences, now, context)
        val candidate = NextEvent.candidate(upcoming, now, filter)
        if (candidate != null && prefs.nextEventEnabled.value) NextEvent.show(this.context, candidate, now) else NextEvent.cancel(this.context)
    }

    /** Only when what the widgets would show changed: a sync that changed nothing shouldn't redraw them. */
    private suspend fun write(snapshot: WidgetSnapshot) {
        val comparable = snapshot.copy(generatedAtEpochSeconds = 0)
        if (comparable == lastWritten && snapshotFile.exists()) return
        runCatching {
            val tmp = File(snapshotFile.parentFile, snapshotFile.name + ".tmp")
            tmp.writeText(CalendarJson.encodeToString(WidgetSnapshot.serializer(), snapshot))
            tmp.renameTo(snapshotFile)
        }
        lastWritten = comparable
        Widgets.updateAll(context)
    }

    companion object {
        fun readSnapshot(context: Context): WidgetSnapshot? = File(context.filesDir, WidgetSnapshot.FILE_NAME).takeIf { it.exists() }?.let {
            runCatching { CalendarJson.decodeFromString(WidgetSnapshot.serializer(), it.readText()) }.getOrNull()
        }
    }
}

/**
 * The next event as an ongoing notification, from an hour before it starts until it ends: the
 * iOS Live Activity. A countdown to the start, then the time left; an alarm at the start and at the
 * end moves it on, since nothing pushes.
 */
object NextEvent {
    /** How long before an event its notification appears. */
    val LEAD: Duration = Duration.ofHours(1)
    private const val ID = 0x4E455854 // "NEXT"

    /** The next timed event the filter shows, if it is under way or starts within [LEAD]. */
    fun candidate(upcoming: List<EventOccurrence>, now: Instant, filter: EventFilter): EventOccurrence? {
        val next = UpNext.next(upcoming.filter { it.end > now }, filter) ?: return null
        return if (next.start <= now.plus(LEAD)) next else null
    }

    fun show(context: Context, occurrence: EventOccurrence, now: Instant) {
        val started = occurrence.start <= now
        val zone = java.time.ZoneId.systemDefault()
        val time = com.neutrino.calendar.core.logic.EventFormatting.timeSummary(occurrence, zone)
        val location = occurrence.event.location?.trim()?.takeIf { it.isNotEmpty() }
        val notification = NotificationCompat.Builder(context, ReminderAlarms.CHANNEL_NEXT_EVENT)
            .setSmallIcon(R.drawable.ic_shortcut_event)
            .setContentTitle(occurrence.event.title)
            .setContentText(listOfNotNull(if (started) "Now · $time" else time, location).joinToString(" · "))
            .setSubText(if (started) "Ends" else "Starts")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen((if (started) occurrence.end else occurrence.start).toEpochMilli())
            .setTimeoutAfter(Duration.between(now, occurrence.end).toMillis().coerceAtLeast(1))
            .setContentIntent(ReminderAlarms.openIntent(context, "neutrinocalendar://event/${android.net.Uri.encode(EventLink(occurrence).string)}"))
            .build()
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < 33
        ) {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(ID, notification)
        }
        // Wake at the next moment the picture changes: the start, then the end.
        val at = if (started) occurrence.end else occurrence.start
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.set(AlarmManager.RTC, at.toEpochMilli() + 1000, redrawIntent(context))
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID)
        context.getSystemService(AlarmManager::class.java).cancel(redrawIntent(context))
    }

    private fun redrawIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, ID, Intent(context, ReminderAlarmReceiver::class.java).setAction(ReminderAlarmReceiver.ACTION_SURFACES),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
