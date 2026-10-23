package com.neutrino.calendar.core.service

import com.neutrino.calendar.core.api.CalendarApi
import com.neutrino.calendar.core.api.isNotFound
import com.neutrino.calendar.core.model.CalendarJson
import com.neutrino.calendar.core.model.CalendarKind
import com.neutrino.calendar.core.model.CalendarRules
import com.neutrino.calendar.core.model.CreateCalendarRequest
import com.neutrino.calendar.core.model.UpdateCalendarRequest
import com.neutrino.calendar.core.model.UserCalendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * The user's calendars: what decides which events are drawn, in which colour, and which can be
 * changed, holiday calendars included.
 *
 * Calendars aren't in the events changes feed, so the list is fetched again on launch, on coming to
 * the foreground and after every sync. The last list is kept on disk, so colours, hidden calendars
 * and holidays hold with no network.
 */
class CalendarsService(private val api: CalendarApi, private val cacheFile: File?) {

    private val _calendars = MutableStateFlow(loadCache())
    /** Default first, then the server's order. */
    val calendars: StateFlow<List<UserCalendar>> = _calendars
    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded
    val error = MutableStateFlow<String?>(null)

    val rules: CalendarRules get() = CalendarRules(_calendars.value)
    val writable: List<UserCalendar> get() = CalendarRules.writable(_calendars.value)
    val defaultCalendar: UserCalendar? get() = _calendars.value.firstOrNull { it.isDefault } ?: writable.firstOrNull()
    val holidayCalendars: List<UserCalendar> get() = _calendars.value.filter { it.kind == CalendarKind.HOLIDAYS }
    /** The calendars a connected account's events sync into, one per provider. */
    val syncedCalendars: List<UserCalendar> get() = _calendars.value.filter { it.kind == CalendarKind.CONNECTION }
    /** Everything but holidays and synced calendars, which Settings lists in sections of their own. */
    val ownCalendars: List<UserCalendar> get() = _calendars.value.filter { it.kind != CalendarKind.HOLIDAYS && it.kind != CalendarKind.CONNECTION }

    fun calendar(id: String?): UserCalendar? = id?.let { i -> _calendars.value.firstOrNull { it.id == i } }

    suspend fun reload() {
        try {
            set(api.calendars())
            _hasLoaded.value = true
            error.value = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isNotFound) {
                // A server older than calendars: everything is in one calendar it doesn't name.
                set(emptyList())
                _hasLoaded.value = true
            } else {
                error.value = e.message
            }
        }
    }

    /** Sign-out: the next account must not see this one's calendars. */
    fun reset() {
        _calendars.value = emptyList()
        _hasLoaded.value = false
        error.value = null
        cacheFile?.delete()
    }

    suspend fun create(request: CreateCalendarRequest): UserCalendar {
        val created = api.createCalendar(request)
        set(_calendars.value + created)
        return created
    }

    /** Shows or hides a calendar at once, and puts it back if the server refuses. */
    suspend fun setVisible(calendar: UserCalendar, visible: Boolean) {
        if (calendar.visible == visible) return
        replace(calendar.copy(visible = visible))
        try {
            replace(api.updateCalendar(calendar.id, UpdateCalendarRequest(visible = visible)))
        } catch (e: Exception) {
            replace(calendar)
            error.value = "Couldn't ${if (visible) "show" else "hide"} ${calendar.name}. ${e.message}"
        }
    }

    /** Name, colour, and a holiday calendar's region and observances: what changed is sent. */
    suspend fun update(calendar: UserCalendar, edited: UserCalendar): UserCalendar {
        val request = UpdateCalendarRequest(
            name = edited.name.takeIf { it != calendar.name },
            color = edited.color.takeIf { !it.equals(calendar.color, ignoreCase = true) },
            region = (edited.region ?: "").takeIf { edited.region != calendar.region },
            includeObservances = edited.includeObservances.takeIf { it != calendar.includeObservances },
        )
        if (request.isEmpty) return calendar
        return api.updateCalendar(calendar.id, request).also(::replace)
    }

    /** Deletes the calendar and every event in it. The caller reloads the events. */
    suspend fun delete(calendar: UserCalendar) {
        api.deleteCalendar(calendar.id)
        set(_calendars.value.filter { it.id != calendar.id })
    }

    private fun replace(calendar: UserCalendar) = set(_calendars.value.map { if (it.id == calendar.id) calendar else it })

    private fun set(list: List<UserCalendar>) {
        // The default first, as the server sends it; kept so after a local change too.
        _calendars.value = list.filter { it.isDefault } + list.filter { !it.isDefault }
        val file = cacheFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(CalendarJson.encodeToString(ListSerializer(UserCalendar.serializer()), _calendars.value))
        }
    }

    private fun loadCache(): List<UserCalendar> = cacheFile?.takeIf { it.exists() }?.let {
        runCatching { CalendarJson.decodeFromString(ListSerializer(UserCalendar.serializer()), it.readText()) }.getOrNull()
    } ?: emptyList()
}
