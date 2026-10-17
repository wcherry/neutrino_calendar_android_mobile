package com.neutrino.calendar

import android.content.SharedPreferences
import com.neutrino.calendar.core.logic.CalendarMode
import com.neutrino.calendar.core.model.CalendarSource
import com.neutrino.calendar.core.model.SourceFilter
import com.neutrino.calendar.core.model.dayOfWeekFromJs
import com.neutrino.calendar.core.model.jsNumber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.DayOfWeek

/**
 * This device's calendar preferences, in the account's `ncal.settings` file under the iOS app's
 * `ncal.` keys. Per device on purpose, as on iOS and the web: how this screen lays out a week is
 * not something the account should carry to another.
 */
class CalendarPrefs(private val prefs: SharedPreferences) {

    private fun bool(key: String, default: Boolean) = MutableStateFlow(prefs.getBoolean(key, default))

    private val _mode = MutableStateFlow(runCatching { CalendarMode.valueOf(prefs.getString(CalendarMode.STORAGE_KEY, null) ?: "") }.getOrDefault(CalendarMode.MONTH))
    val mode: StateFlow<CalendarMode> = _mode
    fun setMode(mode: CalendarMode) = set(_mode, mode) { putString(CalendarMode.STORAGE_KEY, mode.name) }

    /** The day weeks start on: the web's three choices, stored as its JavaScript day number. */
    private val _weekStart = MutableStateFlow(dayOfWeekFromJs(prefs.getInt(WEEK_START, 0)))
    val weekStart: StateFlow<DayOfWeek> = _weekStart
    fun setWeekStart(day: DayOfWeek) = set(_weekStart, day) { putInt(WEEK_START, day.jsNumber()) }

    /** Less space around rows and sections, so more fits at once. Off by default. */
    private val _compact = bool(COMPACT, false)
    val compact: StateFlow<Boolean> = _compact
    fun setCompact(on: Boolean) = set(_compact, on) { putBoolean(COMPACT, on) }

    /** On by default: a reminder app that doesn't remind is the surprise. */
    private val _alerts = bool(ALERTS, true)
    val alertsEnabled: StateFlow<Boolean> = _alerts
    fun setAlertsEnabled(on: Boolean) = set(_alerts, on) { putBoolean(ALERTS, on) }

    /** The ongoing next-event notification: the iOS Live Activity. On by default. */
    private val _nextEvent = bool(NEXT_EVENT, true)
    val nextEventEnabled: StateFlow<Boolean> = _nextEvent
    fun setNextEventEnabled(on: Boolean) = set(_nextEvent, on) { putBoolean(NEXT_EVENT, on) }

    /** Which sources' events the calendar shows; on iOS a Focus filter sets this. */
    private val _sources = MutableStateFlow(loadSources())
    val sourceFilter: StateFlow<SourceFilter> = _sources
    fun setSourceFilter(filter: SourceFilter) = set(_sources, filter) {
        val shown = filter.shown
        if (shown == null) remove(SOURCES) else putStringSet(SOURCES, shown.map { it.name.lowercase() }.toSet())
    }

    /** Whether the notification permission has been asked for once, so it is asked in context only the first time. */
    var askedForNotifications: Boolean
        get() = prefs.getBoolean(ASKED_NOTIFICATIONS, false)
        set(value) = prefs.edit().putBoolean(ASKED_NOTIFICATIONS, value).apply()

    private fun loadSources(): SourceFilter {
        val raw = prefs.getStringSet(SOURCES, null) ?: return SourceFilter.ALL
        return SourceFilter(raw.mapNotNull { r -> CalendarSource.entries.firstOrNull { it.name.lowercase() == r } }.toSet())
    }

    private fun <T> set(flow: MutableStateFlow<T>, value: T, write: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(write).apply()
        flow.value = value
    }

    companion object {
        const val WEEK_START = "ncal.calendar.weekStart"
        const val COMPACT = "ncal.layout.compact"
        const val ALERTS = "ncal.notifications.enabled"
        const val NEXT_EVENT = "ncal.nextEvent.enabled"
        const val SOURCES = "ncal.focus.sources"
        const val ASKED_NOTIFICATIONS = "ncal.notifications.asked"
    }
}
