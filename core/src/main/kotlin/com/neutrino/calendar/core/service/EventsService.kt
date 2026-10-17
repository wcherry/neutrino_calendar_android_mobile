package com.neutrino.calendar.core.service

import com.neutrino.calendar.core.api.CalendarApi
import com.neutrino.calendar.core.api.isNetwork
import com.neutrino.calendar.core.api.isNotFound
import com.neutrino.calendar.core.logic.CalendarGrid
import com.neutrino.calendar.core.logic.CalendarMode
import com.neutrino.calendar.core.logic.DaySection
import com.neutrino.calendar.core.logic.EventDayRange
import com.neutrino.calendar.core.logic.EventDraft
import com.neutrino.calendar.core.logic.EventOccurrence
import com.neutrino.calendar.core.logic.EventsLayout
import com.neutrino.calendar.core.logic.HolidayEngine
import com.neutrino.calendar.core.logic.RecurrenceExpander
import com.neutrino.calendar.core.logic.SplitEventRequest
import com.neutrino.calendar.core.logic.TaskOccurrences
import com.neutrino.calendar.core.logic.UpNext
import com.neutrino.calendar.core.logic.UpdateEventRequest
import com.neutrino.calendar.core.logic.editedTo
import com.neutrino.calendar.core.model.Attachment
import com.neutrino.calendar.core.model.CalendarContext
import com.neutrino.calendar.core.model.CalendarEvent
import com.neutrino.calendar.core.model.CalendarKind
import com.neutrino.calendar.core.model.CalendarRules
import com.neutrino.calendar.core.model.CalendarTask
import com.neutrino.calendar.core.model.CreateAttachmentRequest
import com.neutrino.calendar.core.model.EditConflict
import com.neutrino.calendar.core.model.EventFilter
import com.neutrino.calendar.core.model.RecurrenceScope
import com.neutrino.calendar.core.model.ServerDate
import com.neutrino.calendar.core.model.SourceFilter
import com.neutrino.calendar.core.model.UserCalendar
import com.neutrino.calendar.core.sync.PendingWrite
import com.neutrino.calendar.core.sync.PendingWrites
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What an event, a reminder or a task can hang attachments off: how to list, add and remove them.
 */
class AttachmentOwner(
    /** Changes when the owner does, to reload. */
    val id: String,
    val load: suspend () -> List<Attachment>,
    val add: suspend (CreateAttachmentRequest) -> Attachment,
    val delete: suspend (Attachment) -> Unit,
)

/**
 * Loads events a month at a time, expands the recurring ones, and answers "what is on this day" for
 * every view. The iOS `EventsService`, rule for rule.
 *
 * The month is the unit because it is the web's (see [EventsLayout.monthRange]). A week that crosses
 * a month end simply loads both months; each day is answered from its own month's load.
 *
 * Confined to one thread — the main thread on Android — like every service here.
 */
class EventsService(
    private val api: CalendarApi,
    zone: () -> ZoneId = { ZoneId.systemDefault() },
    weekStart: DayOfWeek = DayOfWeek.SUNDAY,
    sourceFilter: SourceFilter = SourceFilter.ALL,
    private val holidays: HolidayEngine = HolidayEngine.shared,
    private val now: () -> Instant = Instant::now,
) {
    private val zone = zone

    /** Where an edit or delete goes when the server can't be reached. Without one, it fails. */
    var pending: PendingWrites? = null

    private val _weekStart = MutableStateFlow(weekStart)
    /** The day weeks start on. Set from Settings; every grid reads it through [context]. */
    val weekStart: StateFlow<DayOfWeek> = _weekStart

    /** The device's zone with the chosen first weekday. */
    val context: CalendarContext get() = CalendarContext(zone(), _weekStart.value)

    private val _focus = MutableStateFlow(context.day(now()))
    /** The day the views are centred on. */
    val focus: StateFlow<LocalDate> = _focus

    private val _byMonth = MutableStateFlow<Map<LocalDate, List<EventOccurrence>>>(emptyMap())
    /** Occurrences by the first of the month they were loaded for. */
    val byMonth: StateFlow<Map<LocalDate, List<EventOccurrence>>> = _byMonth

    private val _loading = MutableStateFlow<Set<LocalDate>>(emptySet())
    val loadingMonths: StateFlow<Set<LocalDate>> = _loading

    val error = MutableStateFlow<String?>(null)

    private val _generation = MutableStateFlow(0)
    /** Bumped whenever the cache is thrown away, so a view that loads on change of it loads again. */
    val generation: StateFlow<Int> = _generation

    private val _sourceFilter = MutableStateFlow(sourceFilter)
    val sourceFilter: StateFlow<SourceFilter> = _sourceFilter

    private val _calendars = MutableStateFlow<List<UserCalendar>>(emptyList())
    /** The user's calendars: which are hidden, their colours, which are read-only, the holiday calendars. */
    val calendars: StateFlow<List<UserCalendar>> = _calendars

    private val _holidaysByMonth = MutableStateFlow<Map<LocalDate, List<EventOccurrence>>>(emptyMap())
    val holidaysByMonth: StateFlow<Map<LocalDate, List<EventOccurrence>>> = _holidaysByMonth

    private val _tasks = MutableStateFlow<List<CalendarTask>>(emptyList())
    /** Every task, for drawing those with a due date. Set from `TasksService`. */
    val tasks: StateFlow<List<CalendarTask>> = _tasks

    /** The events each loaded month was answered with, before expansion: what a change is applied to. */
    private var rawByMonth: Map<LocalDate, List<CalendarEvent>> = emptyMap()
    /** Where the changes feed picks up; taken before the first month loads. */
    var cursor: String? = null
        private set
    private var isPulling = false
    private var pullAgain = false
    /** The calendar each event seen so far is in, for leaving out the alerts of events in a hidden calendar. */
    private val calendarIdsByEvent = mutableMapOf<String, String?>()
    private val tasksByMonth = mutableMapOf<LocalDate, List<EventOccurrence>>()

    // ── Queries ─────────────────────────────────────────────────────────────────────────────────

    fun setWeekStart(day: DayOfWeek) {
        _weekStart.value = day
    }

    fun setSourceFilter(filter: SourceFilter) {
        if (filter != _sourceFilter.value) _sourceFilter.value = filter
    }

    val rules: CalendarRules get() = CalendarRules(_calendars.value)
    val filter: EventFilter get() = EventFilter(rules, _sourceFilter.value)
    val today: LocalDate get() = context.day(now())
    val currentInstant: Instant get() = now()

    /** The first of the focused month. */
    val month: LocalDate get() = _focus.value.withDayOfMonth(1)

    val isLoading: Boolean get() = _loading.value.isNotEmpty()

    fun hasLoaded(month: LocalDate) = _byMonth.value.containsKey(month.withDayOfMonth(1))

    /** A new list of calendars. Hiding and colours apply at once; the loaded months' holidays are worked out again. */
    suspend fun setCalendars(calendars: List<UserCalendar>) {
        if (calendars == _calendars.value) return
        fun key(list: List<UserCalendar>) = list.filter { it.kind == CalendarKind.HOLIDAYS }.map { listOf(it.id, it.country, it.region, it.includeObservances) }
        val holidaysChanged = key(calendars) != key(_calendars.value)
        _calendars.value = calendars
        if (!holidaysChanged) return
        for (month in _holidaysByMonth.value.keys + _byMonth.value.keys) loadHolidays(month)
    }

    fun setTasks(tasks: List<CalendarTask>) {
        if (tasks == _tasks.value) return
        tasksByMonth.clear()
        _tasks.value = tasks
    }

    /** The agenda: the focused month's days that have occurrences. */
    val sections: List<DaySection> get() = EventsLayout.layout(shown(month), month, context)

    /** Everything on `day`, all-day first, then by start, then by title. Empty until its month has loaded. */
    fun occurrencesOn(day: LocalDate): List<EventOccurrence> {
        val context = context
        return shown(day.withDayOfMonth(1)).filter { EventDayRange.of(it, context).contains(day) }.sortedWith(EventsLayout.dayOrder)
    }

    /** The month's events and holidays its calendars and the source filter show, and its tasks (never hidden). */
    private fun shown(month: LocalDate): List<EventOccurrence> {
        val filter = filter
        val events = ((_byMonth.value[month] ?: emptyList()) + (_holidaysByMonth.value[month] ?: emptyList())).filter { filter.shows(it.event) }
        return events + taskOccurrences(month)
    }

    private fun taskOccurrences(month: LocalDate): List<EventOccurrence> = tasksByMonth.getOrPut(month) {
        val (from, to) = EventsLayout.monthRange(month, context)
        TaskOccurrences.occurrences(_tasks.value, from, to)
    }

    /** The days `mode` shows around the focus, which decides what has to be loaded. */
    fun visibleDays(mode: CalendarMode): List<LocalDate> = when (mode) {
        CalendarMode.DAY -> listOf(_focus.value)
        CalendarMode.WEEK -> CalendarGrid.weekDays(_focus.value, context)
        CalendarMode.MONTH -> CalendarGrid.monthDays(_focus.value, context)
        CalendarMode.AGENDA -> listOf(month)
        CalendarMode.YEAR -> emptyList()
    }

    /** The months `mode` needs. The month grid shows its neighbours' days greyed and empty. */
    fun months(mode: CalendarMode): List<LocalDate> =
        if (mode == CalendarMode.MONTH) listOf(month) else CalendarGrid.months(visibleDays(mode))

    // ── Navigation ──────────────────────────────────────────────────────────────────────────────

    /**
     * Steps by the mode's unit. A month or year step lands on the 1st, or on today when it arrives in
     * today's month; a day or week keeps the weekday.
     */
    fun move(mode: CalendarMode, steps: Int) {
        val f = _focus.value
        _focus.value = when (mode) {
            CalendarMode.DAY -> f.plusDays(steps.toLong())
            CalendarMode.WEEK -> f.plusWeeks(steps.toLong())
            CalendarMode.MONTH, CalendarMode.AGENDA, CalendarMode.YEAR -> {
                val moved = if (mode == CalendarMode.YEAR) f.plusYears(steps.toLong()) else f.plusMonths(steps.toLong())
                val first = moved.withDayOfMonth(1)
                if (first == today.withDayOfMonth(1)) today else first
            }
        }
    }

    fun goToToday() {
        _focus.value = today
    }

    fun select(day: LocalDate) {
        _focus.value = day
    }

    /** Whether `mode` already shows today, when the Today button has nothing to do. */
    fun isShowingToday(mode: CalendarMode): Boolean = when (mode) {
        CalendarMode.DAY, CalendarMode.MONTH, CalendarMode.AGENDA -> _focus.value == today
        CalendarMode.WEEK -> visibleDays(CalendarMode.WEEK).contains(today)
        CalendarMode.YEAR -> _focus.value.year == today.year
    }

    // ── Loading ─────────────────────────────────────────────────────────────────────────────────

    /** Loads whichever of `mode`'s months are not loaded yet. */
    suspend fun ensureLoaded(mode: CalendarMode) {
        for (m in months(mode)) if (!_byMonth.value.containsKey(m) && m !in _loading.value) load(m)
    }

    suspend fun reload(mode: CalendarMode) {
        for (m in months(mode)) load(m)
    }

    /** Pull-to-refresh: asks the server to pull from Google, Outlook and Apple first, then reloads. */
    suspend fun refreshFromProviders(mode: CalendarMode) {
        runCatching { api.triggerProviderSync() }
        reload(mode)
    }

    /** Drops every loaded month, for after a change elsewhere: an event can move to any month. */
    fun invalidate() {
        _byMonth.value = emptyMap()
        rawByMonth = emptyMap()
        cursor = null
        _generation.value++
    }

    private suspend fun load(month: LocalDate) {
        val (from, to) = EventsLayout.monthRange(month, context)
        _loading.value = _loading.value + month
        error.value = null
        try {
            // First, and whatever the network does: holidays are computed here, so they show offline.
            loadHolidays(month)
            // Best effort: without a cursor, the next pull reloads the loaded months instead.
            if (cursor == null) cursor = runCatching { api.eventChanges(null).cursor }.getOrNull()
            val events = api.events(from, to)
            rawByMonth = rawByMonth + (month to events)
            remember(events)
            _byMonth.value = _byMonth.value + (month to RecurrenceExpander.expand(events, from, to, context))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = e.message ?: e.javaClass.simpleName
        } finally {
            _loading.value = _loading.value - month
        }
    }

    private suspend fun loadHolidays(month: LocalDate) {
        val holidayCalendars = _calendars.value.filter { it.kind == CalendarKind.HOLIDAYS }
        if (holidayCalendars.isEmpty()) {
            if (_holidaysByMonth.value.containsKey(month)) _holidaysByMonth.value = _holidaysByMonth.value - month
            return
        }
        val (from, to) = EventsLayout.monthRange(month, context)
        val days = holidays.occurrences(holidayCalendars, from, to)
        _holidaysByMonth.value = _holidaysByMonth.value + (month to days)
    }

    private fun remember(events: List<CalendarEvent>) {
        for (e in events) calendarIdsByEvent[e.id] = e.calendarId
    }

    /** Whether the event with `id` is in a hidden calendar, as far as this device knows. */
    fun isInHiddenCalendar(eventId: String): Boolean {
        val calendarId = calendarIdsByEvent[eventId] ?: return false
        return rules.calendar(calendarId)?.visible == false
    }

    /** Forgets everything loaded, for sign-out. */
    fun reset() {
        _byMonth.value = emptyMap()
        rawByMonth = emptyMap()
        _holidaysByMonth.value = emptyMap()
        tasksByMonth.clear()
        _tasks.value = emptyList()
        _calendars.value = emptyList()
        calendarIdsByEvent.clear()
        cursor = null
        _loading.value = emptySet()
        error.value = null
        _focus.value = today
    }

    // ── Changes made elsewhere ──────────────────────────────────────────────────────────────────

    /**
     * Brings the loaded months up to date with the changes feed. A call made while one is running
     * runs again after it, so a signal that lands mid-pull is not lost.
     */
    suspend fun pullChanges() {
        if (_byMonth.value.isEmpty()) return
        if (isPulling) {
            pullAgain = true
            return
        }
        isPulling = true
        try {
            do {
                pullAgain = false
                val since = cursor
                if (since == null) {
                    for (m in _byMonth.value.keys.toList()) load(m)
                    continue
                }
                val changes = try {
                    api.eventChanges(since)
                } catch (e: Exception) {
                    return
                }
                if (changes.fullResyncRequired) {
                    invalidate()
                    return
                }
                apply(changes.events, changes.deletedIds)
                cursor = changes.cursor
            } while (pullAgain)
        } finally {
            isPulling = false
        }
    }

    /** Puts changed events into, and takes deleted ones out of, every loaded month, and expands those again. */
    private fun apply(changed: List<CalendarEvent>, deleted: List<String>) {
        if (changed.isEmpty() && deleted.isEmpty()) return
        remember(changed)
        val context = context
        for ((month, held) in rawByMonth) {
            val (from, to) = EventsLayout.monthRange(month, context)
            val merged = EventsLayout.merge(held, changed, deleted.toSet(), from, to)
            if (merged == held) continue
            rawByMonth = rawByMonth + (month to merged)
            _byMonth.value = _byMonth.value + (month to RecurrenceExpander.expand(merged, from, to, context))
        }
    }

    // ── Changes ─────────────────────────────────────────────────────────────────────────────────

    /** Each change throws the cache away, so every view reloads what it shows. */
    suspend fun create(draft: EventDraft): CalendarEvent {
        val created = api.createEvent(draft.createRequest())
        invalidate()
        return created
    }

    /**
     * Saves what changed between `original` and `draft`; the event unchanged when nothing did. Unless
     * `overwrite`, it first checks the event as the server has it, and throws [EditConflict] if it was
     * deleted or one of the same fields changed elsewhere. Offline, the edit is queued and shown at once.
     */
    suspend fun update(event: CalendarEvent, original: EventDraft, draft: EventDraft, overwrite: Boolean = false): CalendarEvent {
        val request = draft.updateRequest(original)
        if (request.isEmpty) return event
        return try {
            if (!overwrite) checkForConflict(event, original, request)
            api.updateEvent(event.id, request).also { invalidate() }
        } catch (e: Exception) {
            val pending = pending
            if (!e.isNetwork || pending == null) throw e
            pending.enqueue(PendingWrite.of("PUT", "/api/v1/calendar/events/${event.id}", request))
            val local = event.editedTo(draft)
            apply(listOf(local), emptyList())
            local
        }
    }

    private suspend fun checkForConflict(event: CalendarEvent, original: EventDraft, request: UpdateEventRequest) {
        val current = try {
            api.event(event.id)
        } catch (e: Exception) {
            if (e.isNotFound) throw EditConflict.DeletedElsewhere()
            throw e
        }
        // What was changed elsewhere, measured from the same starting point as this edit.
        val theirs = EventDraft.editing(current, context).updateRequest(original)
        val clashes = EditConflict.clashes(request, theirs)
        if (clashes.isNotEmpty()) throw EditConflict.ChangedElsewhere(clashes)
    }

    /**
     * Saves an edit of `occurrence` to the occurrences `scope` names. A one-off event, or ALL, is an
     * update (of the series for ALL). THIS changes the one occurrence, FOLLOWING splits the series
     * there; those two need the server and fail offline rather than being queued.
     */
    suspend fun update(occurrence: EventOccurrence, scope: RecurrenceScope?, original: EventDraft, draft: EventDraft, overwrite: Boolean = false): CalendarEvent {
        val series = occurrence.series
        val effective = EventDraft.effectiveScope(occurrence, scope)
        val at = occurrence.originalStart
        if (series == null || effective == null || at == null) return update(occurrence.event, original, draft, overwrite)
        val request = draft.updateRequest(original)
        return when (effective) {
            RecurrenceScope.ALL -> update(series, original, draft, overwrite)
            RecurrenceScope.THIS -> {
                // One occurrence can't move calendar on its own; the server refuses it (400).
                val r = request.copy(recurrenceRule = null, calendarId = null)
                if (r.isEmpty) return occurrence.event
                api.editOccurrence(series.id, at, r).also { invalidate() }
            }
            RecurrenceScope.FOLLOWING -> {
                // Always the new series' rule: only this side can count how far a COUNT has run.
                val r = request.copy(recurrenceRule = draft.repeatOption.rule ?: "")
                api.splitEvent(series.id, SplitEventRequest(ServerDate.format(at), r)).also { invalidate() }
            }
        }
    }

    /** Deletes the occurrences `scope` names: the one, it and every later one, or the whole series. */
    suspend fun delete(occurrence: EventOccurrence, scope: RecurrenceScope?) {
        val series = occurrence.series
        val at = occurrence.originalStart
        if (series == null || scope == null || at == null) return delete(occurrence.event)
        when (scope) {
            RecurrenceScope.ALL -> return delete(series)
            RecurrenceScope.THIS -> ignoringNotFound { api.cancelOccurrence(series.id, at) }
            RecurrenceScope.FOLLOWING -> ignoringNotFound { api.deleteEvent(series.id, at) }
        }
        invalidate()
    }

    private suspend fun ignoringNotFound(body: suspend () -> Unit) {
        try {
            body()
        } catch (e: Exception) {
            if (!e.isNotFound) throw e
        }
    }

    /** Deleting something already deleted elsewhere is not an error. Offline, the delete is queued. */
    suspend fun delete(event: CalendarEvent) {
        try {
            api.deleteEvent(event.id)
            invalidate()
        } catch (e: Exception) {
            val pending = pending
            when {
                e.isNotFound -> invalidate()
                e.isNetwork && pending != null -> {
                    pending.enqueue(PendingWrite.of("DELETE", "/api/v1/calendar/events/${event.id}"))
                    apply(emptyList(), listOf(event.id))
                }
                else -> throw e
            }
        }
    }

    /**
     * Every occurrence not over yet from now through `days` days' time, soonest first, whatever the
     * source filter: for the next-event notification, the widgets and shortcuts, which ask the server
     * rather than the months loaded.
     */
    suspend fun upcoming(days: Int): List<EventOccurrence> {
        val now = now()
        val context = context
        val from = context.startOf(context.day(now))
        val to = context.startOf(context.day(now).plusDays(days.toLong()))
        return UpNext.upcoming(occurrences(from, to), now, context)
    }

    /** Every occurrence in the range, expanded, with the holidays, straight from the server. The caller applies the filter. */
    suspend fun occurrences(from: Instant, to: Instant): List<EventOccurrence> {
        val events = api.events(from, to)
        remember(events)
        return RecurrenceExpander.expand(events, from, to, context) + holidays.occurrences(_calendars.value, from, to)
    }

    /** The event as the server has it now. */
    suspend fun event(id: String): CalendarEvent = api.event(id)

    fun attachmentOwner(event: CalendarEvent) = AttachmentOwner(
        "event-${event.id}",
        load = { api.eventAttachments(event.id) },
        add = { api.addEventAttachment(event.id, it) },
        delete = { api.deleteEventAttachment(event.id, it.id) },
    )
}
