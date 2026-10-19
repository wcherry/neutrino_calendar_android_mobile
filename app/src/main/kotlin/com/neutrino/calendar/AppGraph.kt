package com.neutrino.calendar

import android.content.Context
import com.neutrino.calendar.core.api.CalendarApi
import com.neutrino.calendar.core.logic.HolidayEngine
import com.neutrino.calendar.core.service.AttachmentFiles
import com.neutrino.calendar.core.service.CalendarSync
import com.neutrino.calendar.core.service.CalendarsService
import com.neutrino.calendar.core.service.EventsService
import com.neutrino.calendar.core.service.PlacesService
import com.neutrino.calendar.core.service.RemindersService
import com.neutrino.calendar.core.service.TasksService
import com.neutrino.calendar.core.sync.CalendarSignalsClient
import com.neutrino.calendar.core.sync.PendingWrites
import com.neutrino.calendar.geofence.GeofenceMonitor
import com.neutrino.calendar.notifications.ReminderAlarms
import com.neutrino.calendar.surfaces.SystemSurfaces
import com.neutrino.shared.android.NeutrinoAccount
import com.neutrino.shared.android.net.NetworkMonitor
import com.neutrino.shared.core.api.NeutrinoApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZoneId

/**
 * Every long-lived object, built once per process in `Application.onCreate`: an alarm, an arrival or
 * a background refresh can start the process with no screen, and all of them need the services.
 * The iOS app's `AppServices`. Everything here is confined to the main thread.
 */
@OptIn(FlowPreview::class)
class AppGraph(context: Context) {
    val context: Context = context.applicationContext

    /** Sign-in, the session, the keyring and the HTTP client, from neutrino_shared_android. */
    val account = NeutrinoAccount(this.context, NeutrinoApps.calendar, "NeutrinoCalendar-Android/${BuildConfig.VERSION_NAME}") { http, auth ->
        CalendarApi(http, auth)
    }
    val api: CalendarApi get() = account.api
    val prefs = CalendarPrefs(account.settings.prefs)

    /** Main-thread work that outlives any screen. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val zone: () -> ZoneId = { ZoneId.systemDefault() }
    val holidays = HolidayEngine.shared
    val events = EventsService(account.api, zone, prefs.weekStart.value, prefs.sourceFilter.value, holidays)
    val reminders = RemindersService(account.api, zone)
    val tasks = TasksService(account.api, zone)
    val calendars = CalendarsService(account.api, File(this.context.noBackupFilesDir, "calendars.json"))
    val places = PlacesService(account.api) { account.keys.keyring() }
    val attachmentFiles = AttachmentFiles(account.api, { account.keys.keyring() }, File(this.context.cacheDir, "attachments"))
    val pending = PendingWrites(File(this.context.noBackupFilesDir, "pending-writes.json"))
    private val signals = CalendarSignalsClient(
        account.http, scope, { account.auth.serverUrl },
        { runCatching { account.auth.accessToken() }.getOrNull() }, account.api.clientId,
    )
    val sync = CalendarSync(account.api, signals, pending, events, reminders, tasks, calendars, places, scope)
    val router = AppRouter()
    val alarms = ReminderAlarms(this.context)
    val geofences = GeofenceMonitor(this.context)
    val surfaces = SystemSurfaces(this.context, events, prefs) { account.auth.isSignedIn }

    /** Set when there is something to remind about and the notification permission was never asked: the screen asks. */
    val wantsNotificationPermission = MutableStateFlow(false)
    val network = NetworkMonitor(this.context)
    val isOnline: StateFlow<Boolean> = network.isOnline

    init {
        alarms.isHidden = { r -> r.linkedEventId?.let(events::isInHiddenCalendar) ?: false }
        sync.onRefreshed = { surfaces.refresh() }

        // Hidden calendars, colours and holidays, from the list kept on disk until the server's
        // arrives; tasks with a due date drawn on the calendar.
        calendars.calendars.onEach { events.setCalendars(it) }.launchIn(scope)
        tasks.tasks.onEach { events.setTasks(it) }.launchIn(scope)

        // Every change to the list re-plans the alarms — only once it has loaded: an empty list
        // before the first load would unschedule every alert. A calendar hidden or shown hides or
        // brings back its events' alerts.
        combine(reminders.reminders, reminders.hasLoaded, prefs.alertsEnabled, events.calendars) { list, loaded, enabled, _ ->
            Triple(list, loaded, enabled)
        }.onEach { (list, loaded, enabled) ->
            if (!account.auth.isSignedIn || !loaded) return@onEach
            alarms.apply(list, enabled)
            if (enabled && list.any { !it.completed } && !alarms.hasPermission && !prefs.askedForNotifications) wantsNotificationPermission.value = true
        }.launchIn(scope)

        // Arrival alerts, once both lists have loaded: before that an empty list means "not loaded".
        combine(tasks.tasks, places.places, tasks.hasLoaded, places.hasLoaded) { t, p, tl, pl -> if (tl && pl) t to p else null }
            .debounce(300)
            .onEach { it?.let { (t, p) -> geofences.replan(t, p) } }
            .launchIn(scope)

        // An edit made here: the server doesn't signal a client its own writes.
        events.generation.drop(1).onEach { surfaces.refresh() }.launchIn(scope)
        combine(prefs.sourceFilter, prefs.weekStart, prefs.nextEventEnabled) { f, w, _ -> f to w }.drop(1).onEach { (f, w) ->
            events.setSourceFilter(f)
            events.setWeekStart(w)
            surfaces.redraw()
        }.launchIn(scope)

        account.onSignOut {
            sync.stop()
            // Decrypted attachments must not outlive the session that opened them.
            attachmentFiles.clearCache()
            events.reset()
            reminders.reset()
            tasks.reset()
            calendars.reset()
            // The next account must not be alerted at this one's places, or reminded of its reminders.
            places.reset()
            geofences.stop()
            alarms.removeAll()
            router.reset()
            scope.launch { surfaces.signOut() }
        }
        // Replays queued writes whenever the network comes back. StateFlow already drops repeats.
        network.start()
        isOnline.drop(1).onEach { online ->
            if (account.auth.isSignedIn) sync.networkChanged(online)
        }.launchIn(scope)
    }
}
