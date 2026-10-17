package com.neutrino.calendar.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * One of the user's calendars: `CalendarResponse` in `neutrino/src/calendar/calendars/dto.rs`.
 * Every event belongs to one.
 *
 * Hiding a calendar is applied here, when events are drawn, not by the server, which lists hidden
 * calendars' events like any other. See `neutrino/agent_docs/calendars.md`.
 */
@Serializable
data class UserCalendar(
    val id: String,
    val name: String,
    /** `#rrggbb`. */
    val color: String = PALETTE[0],
    val visible: Boolean = true,
    val readOnly: Boolean = false,
    @Serializable(with = CalendarKindSerializer::class)
    val kind: CalendarKind = CalendarKind.LOCAL,
    val isDefault: Boolean = false,
    /** A connection calendar's provider: `google`, `outlook`, `apple`. */
    val source: String? = null,
    /** A holiday calendar's country, ISO 3166-1 alpha-2. */
    val country: String? = null,
    val region: String? = null,
    val includeObservances: Boolean = false,
) {
    /** Never the default calendar, and a provider's only once its account is disconnected (the server enforces it: 409). */
    val isDeletable: Boolean get() = !isDefault && kind != CalendarKind.CONNECTION

    companion object {
        /** The colours offered for a new calendar: the web's `CALENDAR_COLORS`. */
        val PALETTE = listOf("#3b82f6", "#16a34a", "#f97316", "#8b5cf6", "#e11d48", "#0ea5e9", "#ca8a04", "#64748b")

        val PALETTE_NAMES = mapOf(
            "#3b82f6" to "Blue", "#16a34a" to "Green", "#f97316" to "Orange", "#8b5cf6" to "Purple",
            "#e11d48" to "Red", "#0ea5e9" to "Sky", "#ca8a04" to "Yellow", "#64748b" to "Grey",
        )

        /** The first palette colour no calendar in `calendars` uses, or the first one. */
        fun unusedColor(among: List<UserCalendar>): String {
            val used = among.map { it.color.lowercase() }.toSet()
            return PALETTE.firstOrNull { it !in used } ?: PALETTE[0]
        }
    }
}

enum class CalendarKind(val wire: String) {
    /** Made in Neutrino. */
    LOCAL("local"),
    /** A connected Google, Outlook or iCloud calendar. */
    CONNECTION("connection"),
    /** A country's public holidays, computed on the device. */
    HOLIDAYS("holidays"),
}

/** A kind added later reads as a plain calendar rather than failing the whole list. */
object CalendarKindSerializer : KSerializer<CalendarKind> {
    override val descriptor = PrimitiveSerialDescriptor("CalendarKind", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder) = decoder.decodeString().let { s -> CalendarKind.entries.firstOrNull { it.wire == s } ?: CalendarKind.LOCAL }
    override fun serialize(encoder: Encoder, value: CalendarKind) = encoder.encodeString(value.wire)
}

@Serializable
data class ListCalendarsWire(val calendars: List<UserCalendar> = emptyList())

/** `POST /calendars`. Only the fields set are sent. */
@Serializable
data class CreateCalendarRequest(
    val name: String? = null,
    val color: String? = null,
    val kind: String? = null,
    val country: String? = null,
    val region: String? = null,
    val includeObservances: Boolean? = null,
) {
    companion object {
        fun local(name: String, color: String) = CreateCalendarRequest(name = name, color = color)
        fun holidays(country: String, name: String, color: String) =
            CreateCalendarRequest(name = name, color = color, kind = CalendarKind.HOLIDAYS.wire, country = country)
    }
}

/** `PATCH /calendars/{id}`: settings, so a read-only calendar takes it too. An empty `region` clears it. */
@Serializable
data class UpdateCalendarRequest(
    val name: String? = null,
    val color: String? = null,
    val visible: Boolean? = null,
    val region: String? = null,
    val includeObservances: Boolean? = null,
) {
    val isEmpty: Boolean get() = this == UpdateCalendarRequest()
}

/**
 * What the user's calendars decide about an event: whether it is shown, whether it can be changed,
 * and its colour. The web's `visibleEvents`, `isReadOnlyEvent` and `writableCalendars`
 * (`calendar/calendars.ts`).
 */
class CalendarRules(calendars: List<UserCalendar>) {
    private val byId: Map<String, UserCalendar> = calendars.reversed().associateBy { it.id }

    fun calendarOf(event: CalendarEvent): UserCalendar? = event.calendarId?.let { byId[it] }

    fun calendar(id: String?): UserCalendar? = id?.let { byId[it] }

    /** An event in no calendar this device knows of is shown, so nothing vanishes while the list loads. */
    fun isShown(event: CalendarEvent): Boolean = calendarOf(event)?.visible != false

    /** A holiday, or anything in a read-only calendar. */
    fun isReadOnly(event: CalendarEvent): Boolean = event.source == EventSource.Holidays || calendarOf(event)?.readOnly == true

    /** Whether the event can be edited or deleted here. Synced provider events can't be yet (Epic 17). */
    fun isEditable(event: CalendarEvent): Boolean = event.source == EventSource.Local && !isReadOnly(event)

    /** The calendar's colour, or null for an event in no known calendar. */
    fun colorOf(event: CalendarEvent): String? = calendarOf(event)?.color

    override fun equals(other: Any?) = other is CalendarRules && other.byId == byId
    override fun hashCode() = byId.hashCode()

    companion object {
        val NONE = CalendarRules(emptyList())

        /** The calendars an event can be put in. */
        fun writable(calendars: List<UserCalendar>) = calendars.filter { !it.readOnly }
    }
}

/**
 * The calendars a source filter can choose between: Neutrino's own events and each provider the
 * server syncs from. On iOS a Focus filter sets this; on Android, Settings does.
 */
enum class CalendarSource(val label: String) {
    NEUTRINO("Neutrino"), GOOGLE("Google"), OUTLOOK("Outlook"), ICLOUD("iCloud");

    companion object {
        /** `null` for a source the app doesn't know, which no filter chooses. */
        fun of(source: EventSource): CalendarSource? = when (source) {
            // A holiday and a task are Neutrino's own, whatever they were computed from.
            EventSource.Local, EventSource.Holidays, EventSource.Task -> NEUTRINO
            EventSource.Google -> GOOGLE
            EventSource.Outlook -> OUTLOOK
            EventSource.Apple -> ICLOUD
            is EventSource.Other -> null
        }
    }
}

/** Which sources' events the calendar shows. Everything when none is set. Per device, not per account. */
class SourceFilter(shown: Set<CalendarSource>?) {
    /** `null` shows everything. No sources chosen means no filter rather than an empty calendar. */
    val shown: Set<CalendarSource>? = shown?.takeIf { it.isNotEmpty() }

    val isActive: Boolean get() = shown != null

    /** An event from a source the app doesn't know is hidden by any filter: nothing chose it. */
    fun shows(source: EventSource): Boolean {
        val shown = shown ?: return true
        val known = CalendarSource.of(source) ?: return false
        return known in shown
    }

    /** "Neutrino and Google", in the order the sources are listed; `null` when not filtering. */
    val summary: String?
        get() = shown?.let { set -> listPhrase(CalendarSource.entries.filter { it in set }.map { it.label }) }

    override fun equals(other: Any?) = other is SourceFilter && other.shown == shown
    override fun hashCode() = shown.hashCode()

    companion object {
        val ALL = SourceFilter(null)
    }
}

/** Everything that decides whether an event is drawn: its calendar, and the source filter. Both must allow it. */
data class EventFilter(val rules: CalendarRules, val focus: SourceFilter) {
    fun shows(event: CalendarEvent): Boolean = rules.isShown(event) && focus.shows(event.source)

    /** What a search goes by: asked for, so the source filter doesn't narrow it, but a hidden calendar stays hidden. */
    val ignoringFocus: EventFilter get() = EventFilter(rules, SourceFilter.ALL)

    companion object {
        val ALL = EventFilter(CalendarRules.NONE, SourceFilter.ALL)
    }
}

/** "a", "a and b", "a, b and c" — `ListFormatter` in English. */
fun listPhrase(items: List<String>): String = when (items.size) {
    0 -> ""
    1 -> items[0]
    2 -> "${items[0]} and ${items[1]}"
    else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
}
