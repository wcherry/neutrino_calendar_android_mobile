package com.neutrino.calendar.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.neutrino.calendar.AppGraph
import com.neutrino.calendar.Route
import com.neutrino.calendar.Tab
import com.neutrino.calendar.graph
import com.neutrino.calendar.ui.calendar.CalendarScreen
import com.neutrino.calendar.ui.event.EventDetailScreen
import com.neutrino.calendar.ui.event.EventEditorScreen
import com.neutrino.calendar.ui.places.SavedPlacesScreen
import com.neutrino.calendar.ui.reminders.ReminderEditorScreen
import com.neutrino.calendar.ui.reminders.RemindersScreen
import com.neutrino.calendar.ui.settings.AddCountryScreen
import com.neutrino.calendar.ui.settings.CalendarEditScreen
import com.neutrino.calendar.ui.settings.CalendarFilterScreen
import com.neutrino.calendar.ui.settings.CalendarsScreen
import com.neutrino.calendar.ui.settings.HolidayCalendarScreen
import com.neutrino.calendar.ui.settings.KeyOptionsScreen
import com.neutrino.calendar.ui.settings.SettingsScreen
import com.neutrino.calendar.ui.tasks.TaskDetailScreen
import com.neutrino.calendar.ui.tasks.TasksScreen
import com.neutrino.shared.android.security.AppLock
import com.neutrino.shared.android.ui.ConnectivityBanner
import com.neutrino.shared.android.ui.LockScreen
import com.neutrino.shared.core.sync.SyncActivity
import kotlinx.coroutines.launch
import com.neutrino.shared.android.ui.auth.LoginScreen
import com.neutrino.shared.android.ui.auth.RegisterScreen
import com.neutrino.shared.android.ui.keys.EncryptionSetupScreen
import com.neutrino.shared.android.ui.keys.KeyCodeScreen
import com.neutrino.shared.android.ui.keys.PairingScreen
import com.neutrino.shared.android.ui.keys.RecoveryKitScreen

/** Signed out → sign-in; just registered → encryption setup; otherwise the tabs. The lock sits over all of it. */
@Composable
fun Root() {
    val graph = LocalContext.current.graph
    val signedIn by graph.account.signedIn.collectAsState()
    val locked by AppLock.locked.collectAsState()
    val compact by graph.prefs.compact.collectAsState()
    var justRegistered by rememberSaveable { mutableStateOf(false) }
    var registering by rememberSaveable { mutableStateOf(false) }

    // Signed in, now or at launch: start syncing, and plan the alerts from the first list.
    LaunchedEffect(signedIn) {
        if (signedIn) graph.sync.start() else graph.surfaces.signOut()
    }

    CompactProvider(compact) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when {
                !signedIn -> if (registering) {
                    BackHandler { registering = false }
                    RegisterScreen(onBack = { registering = false }, onRegistered = { justRegistered = true; registering = false })
                } else {
                    LoginScreen(onRegister = { registering = true })
                }
                justRegistered -> Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.padding(top = 32.dp)) { EncryptionSetupScreen(onDone = { justRegistered = false }) }
                }
                // Keyed on the account, so a different sign-in never sees the last one's screens.
                else -> androidx.compose.runtime.key(graph.account.auth.userId) { Home(graph) }
            }
            if (locked && signedIn) LockScreen()
        }
    }
}

@Composable
private fun Home(graph: AppGraph) {
    val router = graph.router
    val wantsPermission by graph.wantsNotificationPermission.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        graph.prefs.askedForNotifications = true
        graph.wantsNotificationPermission.value = false
        graph.alarms.apply(graph.reminders.reminders.value, graph.prefs.alertsEnabled.value)
    }
    // Asked the first time there is something to remind about, which is when the question makes sense.
    LaunchedEffect(wantsPermission) {
        if (wantsPermission && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    BackHandler(enabled = router.current != null) { router.pop() }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = router.tab == tab, onClick = { router.select(tab) }, label = { Text(tab.label) },
                        icon = {
                            Icon(
                                when (tab) {
                                    Tab.CALENDAR -> Icons.Filled.CalendarMonth
                                    Tab.REMINDERS -> Icons.Filled.Notifications
                                    Tab.TASKS -> Icons.Filled.Checklist
                                    Tab.SETTINGS -> Icons.Filled.Settings
                                },
                                null,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize()) {
            Connectivity(graph)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val route = router.current
                if (route == null) TabRoot(router.tab) else RouteContent(route)
            }
            Banners(graph, router.tab)
        }
    }
}

@Composable
private fun TabRoot(tab: Tab) = when (tab) {
    Tab.CALENDAR -> CalendarScreen()
    Tab.REMINDERS -> RemindersScreen()
    Tab.TASKS -> TasksScreen()
    Tab.SETTINGS -> SettingsScreen()
}

@Composable
private fun RouteContent(route: Route) {
    val graph = LocalContext.current.graph
    val router = graph.router
    val back: () -> Unit = { router.pop() }
    when (route) {
        is Route.Event -> EventDetailScreen(route.occurrence, back)
        is Route.EditEvent -> EventEditorScreen(route.mode, back)
        is Route.Task -> TaskDetailScreen(route.id, back)
        is Route.EditReminder -> ReminderEditorScreen(route.mode, back)
        Route.Calendars -> CalendarsScreen(back)
        is Route.EditCalendar -> CalendarEditScreen(route.id, back)
        is Route.HolidayCalendar -> HolidayCalendarScreen(route.id, back)
        Route.AddCountry -> AddCountryScreen(back)
        Route.SavedPlaces -> SavedPlacesScreen(back)
        Route.CalendarFilter -> CalendarFilterScreen(back)
        Route.KeyOptions -> KeyOptionsScreen(back)
        Route.Pairing -> KeyFlow(back) { PairingScreen(it) }
        Route.RecoveryKit -> KeyFlow(back) { RecoveryKitScreen(it) }
        Route.KeyCode -> KeyFlow(back) { KeyCodeScreen(it) }
        Route.SetUpEncryption -> KeyFlow(back) { EncryptionSetupScreen(it) }
    }
}

/** A shared key screen inside the app: back closes it, finishing drops every key screen. */
@Composable
private fun KeyFlow(onBack: () -> Unit, content: @Composable (onDone: () -> Unit) -> Unit) {
    val router = LocalContext.current.graph.router
    Column(Modifier.fillMaxSize()) {
        PageBar("", onBack)
        Box(Modifier.weight(1f)) {
            content {
                router.popWhile { it is Route.Pairing || it is Route.RecoveryKit || it is Route.KeyCode || it is Route.SetUpEncryption || it is Route.KeyOptions }
            }
        }
    }
}

/** Offline, the queue of changes made offline, syncing, a failed replay with Retry, and "Back online". */
@Composable
private fun Connectivity(graph: AppGraph) {
    val online by graph.isOnline.collectAsState()
    val writes by graph.pending.writes.collectAsState()
    val replaying by graph.pending.isReplaying.collectAsState()
    val failure by graph.pending.lastFailure.collectAsState()
    val activity = when {
        replaying -> SyncActivity.Syncing(writes.size)
        failure != null && writes.isNotEmpty() -> SyncActivity.Failed(failure.orEmpty())
        else -> SyncActivity.Idle
    }
    ConnectivityBanner(
        isOnline = online,
        pendingChanges = writes.size,
        activity = activity,
        onRetry = { graph.scope.launch { graph.sync.catchUp() } },
    )
}

/** A refused offline change, and the source filter hiding calendars. The queue is [Connectivity]'s. */
@Composable
private fun Banners(graph: AppGraph, tab: Tab) {
    val notice by graph.pending.notice.collectAsState()
    val filter by graph.prefs.sourceFilter.collectAsState()
    val bar = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 16.dp, vertical = 6.dp)
    if (tab == Tab.CALENDAR) filter.summary?.let {
        Text("Showing $it only", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = bar)
    }
    notice?.let {
        androidx.compose.foundation.layout.Row(bar, verticalAlignment = Alignment.CenterVertically) {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            IconButton(onClick = { graph.pending.dismissNotice() }) { Icon(Icons.Filled.Close, "Dismiss") }
        }
    }
}
