package com.neutrino.calendar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.neutrino.calendar.core.logic.EventLink
import com.neutrino.calendar.core.logic.ICSImport
import com.neutrino.calendar.ui.CalendarBrand
import com.neutrino.calendar.ui.Root
import com.neutrino.shared.android.LocalNeutrinoAccount
import com.neutrino.shared.android.security.AppLock
import com.neutrino.shared.android.ui.NeutrinoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * The one activity. A `FragmentActivity` because `BiometricPrompt` needs one; everything on screen
 * is Compose.
 */
class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = graph.account.settings
        if (savedInstanceState == null) {
            AppLock.lockOnLaunch(settings.appLock.value)
            handle(intent)
        }
        // With the lock on, the recents screen shows a blank card rather than the calendar.
        lifecycleScope.launch {
            settings.appLock.collect { on ->
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        setContent {
            CompositionLocalProvider(LocalNeutrinoAccount provides graph.account) {
                NeutrinoTheme(CalendarBrand) {
                    Root()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        AppLock.onForeground(graph.account.settings.appLock.value)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) AppLock.onBackground()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val data = intent.data
        when {
            intent.action == Intent.ACTION_VIEW && data?.scheme == "neutrinocalendar" -> openLink(data)
            intent.action == Intent.ACTION_VIEW && data != null -> importCalendarFile(data)
            intent.action == Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(::importCalendarFile)
            }
        }
    }

    /** `neutrinocalendar://event/<link>`, `day/<yyyy-MM-dd>`, `reminder/<id>`, `task/<id>`, `new/<kind>`. */
    private fun openLink(uri: Uri) {
        val router = graph.router
        val value = uri.path?.removePrefix("/").orEmpty()
        when (uri.host) {
            "event" -> EventLink.parse(Uri.decode(value))?.let {
                if (it.eventId.startsWith("holiday:")) {
                    router.openDay = graph.events.context.day(it.start)
                    router.select(Tab.CALENDAR)
                } else {
                    router.openEvent = it
                    router.tab = Tab.CALENDAR
                }
            }
            "day" -> runCatching { LocalDate.parse(value) }.getOrNull()?.let {
                router.openDay = it
                router.tab = Tab.CALENDAR
            }
            "reminder" -> {
                router.openReminderId = value
                router.tab = Tab.REMINDERS
            }
            "task" -> {
                router.openTaskId = value
                router.tab = Tab.TASKS
            }
            "new" -> when (value) {
                "event" -> router.push(Route.EditEvent(EventEditorMode.Create(graph.events.focus.value)), Tab.CALENDAR)
                "task" -> {
                    router.select(Tab.TASKS)
                    router.newTask = true
                }
                "reminder" -> router.push(Route.EditReminder(ReminderEditorMode.Create()), Tab.REMINDERS)
            }
        }
    }

    /**
     * Reads a shared `.ics` file into the new-event form. Nothing is saved until the user taps Add. A
     * file opened while signed out is held until the calendar shows.
     */
    private fun importCalendarFile(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.use { ICSImport.decode(it.readBytes()) } }.getOrNull()
            }
            val router = graph.router
            try {
                router.importedEvent = ICSImport.draft(text ?: throw ICSImport.Failure.Unreadable(), graph.events.context)
                router.tab = Tab.CALENDAR
            } catch (e: Exception) {
                router.tab = Tab.CALENDAR
                graph.events.error.value = e.message
            }
        }
    }
}
