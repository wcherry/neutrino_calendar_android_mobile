package com.neutrino.calendar.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.neutrino.calendar.BuildConfig
import com.neutrino.calendar.Route
import com.neutrino.calendar.core.api.httpStatus
import com.neutrino.calendar.core.logic.HolidayEngine
import com.neutrino.calendar.core.model.CalendarKind
import com.neutrino.calendar.core.model.CalendarSource
import com.neutrino.calendar.core.model.CreateCalendarRequest
import com.neutrino.calendar.core.model.EventSource
import com.neutrino.calendar.core.model.SourceFilter
import com.neutrino.calendar.core.model.UserCalendar
import com.neutrino.calendar.geofence.LocationAccess
import com.neutrino.calendar.graph
import com.neutrino.calendar.ui.ActionRow
import com.neutrino.calendar.ui.ChoiceRow
import com.neutrino.calendar.ui.ColorDot
import com.neutrino.calendar.ui.ColorPalette
import com.neutrino.calendar.ui.ErrorText
import com.neutrino.calendar.ui.FormRow
import com.neutrino.calendar.ui.FormSection
import com.neutrino.calendar.ui.PageBar
import com.neutrino.calendar.ui.SwitchRow
import com.neutrino.calendar.ui.ValueRow
import com.neutrino.calendar.ui.event.PlainField
import com.neutrino.calendar.ui.hexColor
import com.neutrino.shared.android.security.AppLock
import com.neutrino.shared.android.ui.ConfirmDialog
import com.neutrino.shared.android.ui.TextInputDialog
import com.neutrino.shared.android.ui.openUrl
import kotlinx.coroutines.launch
import java.time.DayOfWeek

/** Re-reads permission state whenever the screen comes back, as after a trip to system settings. */
@Composable
private fun OnResume(action: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) action() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val graph = context.graph
    val prefs = graph.prefs
    val router = graph.router
    val scope = rememberCoroutineScope()
    val alerts by prefs.alertsEnabled.collectAsState()
    val nextEvent by prefs.nextEventEnabled.collectAsState()
    val compact by prefs.compact.collectAsState()
    val weekStart by prefs.weekStart.collectAsState()
    val sources by prefs.sourceFilter.collectAsState()
    val calendars by graph.calendars.calendars.collectAsState()
    val access by graph.geofences.access.collectAsState()
    val appLock by graph.account.settings.appLock.collectAsState()
    val keyring by graph.account.keyringVersion.collectAsState()
    var notificationsAllowed by remember { mutableStateOf(graph.alarms.hasPermission) }
    var hasKey by remember { mutableStateOf(graph.account.keys.hasKey) }
    var canSetUp by remember { mutableStateOf(false) }
    var editingDeviceName by remember { mutableStateOf(false) }

    OnResume {
        notificationsAllowed = graph.alarms.hasPermission
        graph.geofences.refreshAccess()
    }
    LaunchedEffect(keyring) {
        hasKey = graph.account.keys.hasKey
        // First-time setup is only for an account with no key anywhere; any other needs its key brought here.
        canSetUp = !hasKey && graph.account.keys.canProvision()
    }
    fun appSettings() = context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    Column(Modifier.fillMaxSize()) {
        PageBar("Settings", null)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FormSection("Account") {
                ValueRow("Signed in as", graph.account.auth.email ?: "—")
                ValueRow("Server", graph.account.settings.serverUrl.removePrefix("https://"))
                ValueRow("Device name", graph.account.settings.deviceName, onClick = { editingDeviceName = true })
            }

            FormSection("Notifications", footer = when {
                !alerts -> "No reminder will alert on this device."
                !notificationsAllowed -> "Notifications are off for Calendar, so reminders can't alert. Turn them on in Settings."
                else -> "Reminders alert on this device at their due time, with Mark as Done and Snooze. They're scheduled from what was last synced, so a reminder added elsewhere alerts once this phone has caught up."
            }) {
                SwitchRow("Reminder alerts", alerts) {
                    prefs.setAlertsEnabled(it)
                    if (it && !notificationsAllowed && Build.VERSION.SDK_INT >= 33) graph.wantsNotificationPermission.value = true
                }
                if (alerts && !notificationsAllowed) ActionRow("Allow Notifications in Settings") { appSettings() }
                SwitchRow("Next event notification", nextEvent) { prefs.setNextEventEnabled(it) }
            }

            FormSection("Places", footer = "A task with a place reminds you when you arrive there, which needs location access set to “Allow all the time”. This phone watches for arrivals itself: your location is never sent to the server.") {
                ValueRow("Saved Places", "", onClick = { router.push(Route.SavedPlaces) })
                ValueRow("Location access", access.label)
                if (access != LocationAccess.ALWAYS && access != LocationAccess.NOT_ASKED) ActionRow("Change in Settings") { appSettings() }
            }

            FormSection("Calendar", footer = "Show or hide calendars, change their colours, and add public holidays. The week start is used by the month, week and year views, as on the web.") {
                ValueRow("Calendars", calendars.size.toString(), onClick = { router.push(Route.Calendars) })
                ChoiceRow("Week starts on", weekStart, listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.SATURDAY),
                    { it.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) }, { prefs.setWeekStart(it) })
                ValueRow("Show events from", sources.summary ?: "All sources", onClick = { router.push(Route.CalendarFilter) })
            }

            FormSection("Display", footer = "Less space around rows, sections and the edges of the screen, so more fits at once.") {
                SwitchRow("Compact layout", compact) { prefs.setCompact(it) }
            }

            FormSection("Encryption", footer = if (hasKey)
                "Attached Drive files are decrypted on this phone, and new ones and saved places are encrypted before they're uploaded. The key never leaves the device."
            else "Attached Drive files and saved places are end-to-end encrypted. To open or add them here, bring your key from a device that has it.") {
                if (hasKey) {
                    FormRow(divider = false) {
                        Icon(Icons.Filled.VerifiedUser, null, Modifier.size(20.dp), tint = com.neutrino.shared.android.ui.NeutrinoBrand.success)
                        Spacer(Modifier.width(12.dp))
                        Text("This phone has your encryption key")
                    }
                } else {
                    FormRow {
                        Icon(Icons.Filled.Warning, null, Modifier.size(20.dp), tint = com.neutrino.calendar.ui.EventStyle.warning)
                        Spacer(Modifier.width(12.dp))
                        Text("No encryption key on this phone", color = com.neutrino.calendar.ui.EventStyle.warning)
                    }
                    if (canSetUp) ActionRow("Set Up Encryption", Icons.Filled.Key) { router.push(Route.SetUpEncryption) }
                    ActionRow("Pair With Another Device", Icons.Filled.QrCodeScanner) { router.push(Route.Pairing) }
                    ActionRow("Restore From Recovery Kit", Icons.AutoMirrored.Filled.MenuBook) { router.push(Route.RecoveryKit) }
                    ActionRow("Scan Key Code", Icons.Filled.Password) { router.push(Route.KeyCode) }
                }
            }

            FormSection("Security", footer = "Locks Calendar with your fingerprint, face or device PIN when you return to it.") {
                SwitchRow("App lock", appLock, enabled = AppLock.isAvailable(context)) { on ->
                    scope.launch {
                        if (AppLock.authenticate(context as FragmentActivity, if (on) "Turn on app lock" else "Turn off app lock")) graph.account.settings.setAppLock(on)
                    }
                }
            }

            FormSection("Connected Calendars", footer = "Synced events are drawn in their calendar's colour. Tap one to change it.".takeIf { calendars.any { it.kind == CalendarKind.CONNECTION } }) {
                calendars.filter { it.kind == CalendarKind.CONNECTION }.forEach { c ->
                    FormRow(onClick = { router.push(Route.EditCalendar(c.id)) }) {
                        ColorDot(hexColor(c.color) ?: MaterialTheme.colorScheme.primary, 12)
                        Spacer(Modifier.width(10.dp))
                        Text(c.name, Modifier.weight(1f))
                        Text("Colour", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FormRow(divider = false) {
                    Text("Connect Google, Outlook or iCloud from the web app for now.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            FormSection("About") {
                ValueRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                ActionRow("Report a Bug", Icons.Filled.BugReport) { openUrl(context, BugReport.url(graph.account.settings.serverUrl)) }
            }

            FormSection {
                ActionRow("Sign Out", Icons.AutoMirrored.Filled.Logout, MaterialTheme.colorScheme.error) { graph.account.signOut() }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
    if (editingDeviceName) TextInputDialog("Device name", graph.account.settings.deviceName, "Shown in your account's device list", "Save",
        onDismiss = { editingDeviceName = false }) {
        graph.account.settings.customDeviceName = it
        editingDeviceName = false
    }
}

/**
 * The GitHub issue form behind "Report a Bug", prefilled with what this build knows about itself.
 * Prefilled rather than gathered silently: every value lands in a field the user reads and can edit
 * before submitting, since an issue is public. The server's *kind*, never its address.
 */
object BugReport {
    const val REPOSITORY = "wcherry/neutrino_calendar_android_mobile"

    fun url(server: String): String {
        val host = server.lowercase()
        val kind = when {
            host.contains("getneutrino.app") -> "getneutrino.app (hosted)"
            host.contains("localhost") || host.contains("127.0.0.1") || host.contains("10.0.2.2") -> "localhost / development"
            else -> "Self-hosted"
        }
        return Uri.parse("https://github.com/$REPOSITORY/issues/new").buildUpon()
            .appendQueryParameter("template", "1-bug-report.yml")
            .appendQueryParameter("version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            .appendQueryParameter("android", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .appendQueryParameter("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .appendQueryParameter("install", if (BuildConfig.DEBUG) "Built from source" else "Release build")
            .appendQueryParameter("server", kind)
            .build().toString()
    }
}

/** The key screens behind "Encryption", for a deep link into them. */
@Composable
fun KeyOptionsScreen(onBack: () -> Unit) {
    val router = LocalContext.current.graph.router
    Column(Modifier.fillMaxSize()) {
        PageBar("Encryption", onBack)
        FormSection {
            ActionRow("Pair With Another Device", Icons.Filled.QrCodeScanner) { router.push(Route.Pairing) }
            ActionRow("Restore From Recovery Kit", Icons.AutoMirrored.Filled.MenuBook) { router.push(Route.RecoveryKit) }
            ActionRow("Scan Key Code", Icons.Filled.Password) { router.push(Route.KeyCode) }
        }
    }
}

/** Which sources' events the calendar shows: the iOS Focus filter, chosen here. */
@Composable
fun CalendarFilterScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val filter by graph.prefs.sourceFilter.collectAsState()
    Column(Modifier.fillMaxSize()) {
        PageBar("Show Events From", onBack)
        FormSection(footer = "The calendar, the widgets and the next-event notification show only the sources ticked. With none ticked, everything shows.") {
            CalendarSource.entries.forEach { source ->
                val shown = filter.shown
                val checked = shown?.contains(source) ?: false
                FormRow(onClick = {
                    val now = (shown ?: emptySet()).let { if (checked) it - source else it + source }
                    graph.prefs.setSourceFilter(SourceFilter(now))
                }) {
                    Text(source.label, Modifier.weight(1f))
                    Checkbox(checked, null)
                }
            }
        }
    }
}

// ── Calendars ───────────────────────────────────────────────────────────────────────────────────

/**
 * Settings › Calendars: every calendar, shown or hidden at a tap, the calendars connected accounts
 * sync into, and holiday calendars by country.
 */
@Composable
fun CalendarsScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val calendars = graph.calendars
    val router = graph.router
    val list by calendars.calendars.collectAsState()
    val error by calendars.error.collectAsState()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { calendars.reload() }

    Column(Modifier.fillMaxSize()) {
        PageBar("Calendars", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ErrorText(error)
            FormSection("My Calendars", footer = "A hidden calendar's events don't show anywhere on this phone: the calendar, widgets and alerts. It's hidden on the web too.") {
                list.filter { it.kind != CalendarKind.HOLIDAYS && it.kind != CalendarKind.CONNECTION }.forEach { c ->
                    CalendarRow(c, { scope.launch { calendars.setVisible(c, it) } }) { router.push(Route.EditCalendar(c.id)) }
                }
                ActionRow("Add Calendar", Icons.Filled.Add) { adding = true }
            }
            val synced = list.filter { it.kind == CalendarKind.CONNECTION }
            if (synced.isNotEmpty()) FormSection("Synced Calendars", footer = "Events from your Google, Outlook and iCloud accounts, drawn in their calendar's colour. Tap one to change it.") {
                synced.forEach { c ->
                    CalendarRow(c, { scope.launch { calendars.setVisible(c, it) } }) { router.push(Route.EditCalendar(c.id)) }
                }
            }
            FormSection("Holidays", footer = "Public holidays are worked out on this phone, so the countries you pick never leave Neutrino. Holiday rules from date-holidays (CC BY-SA 3.0).") {
                list.filter { it.kind == CalendarKind.HOLIDAYS }.forEach { c ->
                    CalendarRow(c, { scope.launch { calendars.setVisible(c, it) } }) { router.push(Route.HolidayCalendar(c.id)) }
                }
                ActionRow("Add Country", Icons.Filled.Add) { router.push(Route.AddCountry) }
            }
        }
    }
    if (adding) NewCalendarDialog { adding = false }
}

@Composable
private fun CalendarRow(calendar: UserCalendar, onVisible: (Boolean) -> Unit, onClick: () -> Unit) {
    FormRow(onClick = onClick) {
        ColorDot(hexColor(calendar.color) ?: MaterialTheme.colorScheme.primary, 12)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(calendar.name)
            val detail = listOfNotNull(
                "Default".takeIf { calendar.isDefault },
                calendar.source?.takeIf { calendar.kind == CalendarKind.CONNECTION }?.let { EventSource.of(it).badge ?: it },
                "Read-only".takeIf { calendar.readOnly },
            ).joinToString(" · ")
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(calendar.visible, onVisible)
    }
}

@Composable
private fun NewCalendarDialog(onDismiss: () -> Unit) {
    val graph = LocalContext.current.graph
    val calendars = graph.calendars
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(UserCalendar.unusedColor(calendars.calendars.value)) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Calendar") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ColorPalette(color) { color = it }
                ErrorText(error)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = name.isNotBlank() && !saving, onClick = {
                saving = true
                scope.launch {
                    runCatching { calendars.create(CreateCalendarRequest.local(name.trim(), color)) }
                        .onSuccess { onDismiss() }.onFailure { error = "Couldn't add the calendar. ${it.message}" }
                    saving = false
                }
            }) { Text("Add") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Rename, recolour or delete a calendar, saved on the way out as system settings are. The default
 * calendar can't be deleted, nor a provider's while its account is connected.
 */
@Composable
fun CalendarEditScreen(id: String, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val calendars = graph.calendars
    val calendar = calendars.calendar(id) ?: return onBack()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(calendar.name) }
    var color by remember { mutableStateOf(calendar.color) }
    var confirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun saveAndLeave() {
        val current = calendars.calendar(id)
        onBack()
        if (current != null) graph.scope.launch {
            runCatching { calendars.update(current, current.copy(name = name.trim().ifEmpty { current.name }, color = color)) }
                .onFailure { calendars.error.value = "Couldn't save ${current.name}. ${it.message}" }
        }
    }
    androidx.activity.compose.BackHandler { saveAndLeave() }

    Column(Modifier.fillMaxSize()) {
        PageBar(calendar.name, ::saveAndLeave)
        FormSection { PlainField(name, { name = it }, "Name", divider = false) }
        FormSection("Colour") { ColorPalette(color) { color = it } }
        ErrorText(error)
        if (calendar.isDeletable) {
            FormSection(footer = "Deletes the calendar and every event in it, on every device.") {
                ActionRow("Delete Calendar", color = MaterialTheme.colorScheme.error) { confirming = true }
            }
        } else if (calendar.isDefault) {
            Text("This is your default calendar: new events go here, and it can't be deleted.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 28.dp))
        }
    }
    if (confirming) ConfirmDialog("Delete ${calendar.name}?", "Every event in it is deleted too. This can't be undone.", "Delete Calendar and Its Events",
        destructive = true, onDismiss = { confirming = false }) {
        scope.launch {
            try {
                calendars.delete(calendar)
                // Its events went with it.
                graph.events.invalidate()
                onBack()
            } catch (e: Exception) {
                error = if (e.httpStatus == 409) "Disconnect this account on the web first, then delete its calendar." else "Couldn't delete the calendar. ${e.message}"
            }
        }
    }
}

/** One country's holidays: its region, observances, colour, and removing it. */
@Composable
fun HolidayCalendarScreen(id: String, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val calendars = graph.calendars
    val calendar = calendars.calendar(id) ?: return onBack()
    val scope = rememberCoroutineScope()
    var regions by remember { mutableStateOf<List<HolidayEngine.Place>>(emptyList()) }
    var region by remember { mutableStateOf(calendar.region) }
    var observances by remember { mutableStateOf(calendar.includeObservances) }
    var color by remember { mutableStateOf(calendar.color) }
    var confirming by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(calendar.country) {
        calendar.country?.let { regions = runCatching { graph.holidays.regions(it, HolidayEngine.deviceLanguage()) }.getOrDefault(emptyList()) }
    }
    fun saveAndLeave() {
        val current = calendars.calendar(id)
        onBack()
        if (current != null) graph.scope.launch {
            runCatching { calendars.update(current, current.copy(region = region, includeObservances = observances, color = color)) }
                .onFailure { calendars.error.value = "Couldn't save ${current.name}. ${it.message}" }
        }
    }
    androidx.activity.compose.BackHandler { saveAndLeave() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageBar(calendar.name, ::saveAndLeave)
        if (regions.isNotEmpty()) FormSection(footer = "Adds the region's own holidays to the nationwide ones.") {
            ChoiceRow("Region", region, listOf<String?>(null) + regions.map { it.code }, { code -> code?.let { c -> regions.firstOrNull { it.code == c }?.name ?: c } ?: "Nationwide only" },
                { region = it })
        }
        FormSection(footer = "Days that are marked but aren't days off, such as Mother's Day or Halloween.") {
            SwitchRow("Include observances", observances) { observances = it }
        }
        FormSection("Colour") { ColorPalette(color) { color = it } }
        ErrorText(error)
        FormSection { ActionRow("Remove ${calendar.name}", color = MaterialTheme.colorScheme.error) { confirming = true } }
    }
    if (confirming) ConfirmDialog("Remove ${calendar.name}?", "Its holidays leave the calendar on every device.", "Remove Holidays", destructive = true,
        onDismiss = { confirming = false }) {
        scope.launch {
            runCatching { calendars.delete(calendar) }.onSuccess { onBack() }.onFailure { error = "Couldn't remove it. ${it.message}" }
        }
    }
}

/** Every country the holiday rules know, searchable, those already added left out. */
@Composable
fun AddCountryScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val calendars = graph.calendars
    val scope = rememberCoroutineScope()
    var countries by remember { mutableStateOf<List<HolidayEngine.Place>?>(null) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { graph.holidays.countries(HolidayEngine.deviceLanguage()) }
            .onSuccess { countries = it }.onFailure { error = "The holiday rules couldn't be loaded."; countries = emptyList() }
    }
    val added = calendars.holidayCalendars.mapNotNull { it.country }.toSet()
    val shown = countries.orEmpty().filter { it.code !in added }
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.code.equals(query.trim(), ignoreCase = true) }

    Column(Modifier.fillMaxSize()) {
        PageBar("Add Holidays", onBack)
        OutlinedTextField(query, { query = it }, placeholder = { Text("Search countries") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        ErrorText(error)
        if (countries == null) CircularProgressIndicator(Modifier.padding(24.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.code }) { country ->
                FormRow(onClick = {
                    scope.launch {
                        try {
                            calendars.create(CreateCalendarRequest.holidays(country.code, country.name, UserCalendar.unusedColor(calendars.calendars.value)))
                            onBack()
                        } catch (e: Exception) {
                            // Added on another device meanwhile.
                            if (e.httpStatus == 409) {
                                calendars.reload()
                                onBack()
                            } else error = "Couldn't add ${country.name}. ${e.message}"
                        }
                    }
                }) { Text(country.name) }
            }
        }
    }
}
