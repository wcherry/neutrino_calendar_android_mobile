package com.neutrino.calendar.core.logic

import com.neutrino.calendar.core.model.CalendarEvent
import com.neutrino.calendar.core.model.CalendarJson
import com.neutrino.calendar.core.model.CalendarKind
import com.neutrino.calendar.core.model.EventSource
import com.neutrino.calendar.core.model.ServerDate
import com.neutrino.calendar.core.model.UserCalendar
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToLong

/**
 * The days of holiday calendars, computed on the device by the web's own rules engine.
 *
 * The web computes holidays with `date-holidays` (`holidaysOf` in `calendar/calendars.ts`). This
 * runs the very same library — its self-contained UMD build, at the version the web locks
 * (`scripts/sync_date_holidays.sh`) — in Rhino, as the iOS app runs it in JavaScriptCore. Porting
 * the rules would mean tracking every country by hand; running the bundle keeps all clients on the
 * same days. Nothing leaves the device: a user's countries are never sent anywhere.
 *
 * The 1.5 MB script is evaluated once, on a thread of its own (Rhino contexts are thread-bound, and
 * interpreting the bundle needs a deep stack), the first time a holiday calendar is shown; each
 * country's year is then cached.
 */
class HolidayEngine(private val script: () -> String? = ::bundledScript) {

    /** One holiday as the library gives it. */
    @Serializable
    data class Record(
        /** Local to the country: `YYYY-MM-DD hh:mm:ss`. */
        val date: String,
        /** Milliseconds since 1970. */
        val start: Double,
        val end: Double,
        val name: String,
        /** `public`, `bank`, `school`, `optional` or `observance`. */
        val type: String,
    )

    @Serializable
    data class Place(val code: String, val name: String)

    class Unavailable(message: String) : Exception(message)

    private val dispatcher: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(null, r, "date-holidays", 16L * 1024 * 1024).apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private var scope: Scriptable? = null
    private var failed = false
    private val cache = mutableMapOf<String, List<Record>>()

    /** The holidays of `country` (and `region`) in `year`, of every type. */
    suspend fun holidays(country: String, region: String?, year: Int, language: String): List<Record> = withContext(dispatcher) {
        val key = "$country|${region.orEmpty()}|$year|$language"
        cache[key] ?: call("__neutrinoHolidays", ListSerializer(Record.serializer()), country, region, year, language)
            .also { cache[key] = it }
    }

    /** Every country the rules know, sorted by name. */
    suspend fun countries(language: String): List<Place> = withContext(dispatcher) {
        call("__neutrinoCountries", ListSerializer(Place.serializer()), language).sortedWith { a, b -> titleOrder(a.name, b.name) }
    }

    /** The regions of `country` with holidays of their own; empty when it has none. */
    suspend fun regions(country: String, language: String): List<Place> = withContext(dispatcher) {
        call("__neutrinoRegions", ListSerializer(Place.serializer()), country, language).sortedWith { a, b -> titleOrder(a.name, b.name) }
    }

    /** Every holiday calendar's days in the range, hidden ones too (the filter hides them). One that fails is skipped. */
    suspend fun occurrences(calendars: List<UserCalendar>, from: Instant, to: Instant, language: String = deviceLanguage()): List<EventOccurrence> =
        calendars.filter { it.kind == CalendarKind.HOLIDAYS }.flatMap { calendar ->
            val country = calendar.country ?: return@flatMap emptyList()
            val events = runCatching {
                Holidays.events(calendar, from, to) { year -> holidays(country, calendar.region, year, language) }
            }.getOrDefault(emptyList())
            events.map { EventOccurrence(it, it.start, it.end) }
        }

    private fun <T> call(function: String, serializer: kotlinx.serialization.KSerializer<T>, vararg args: Any?): T {
        val cx = enter()
        try {
            val scope = loaded(cx)
            val fn = scope.get(function, scope) as? Function ?: throw Unavailable("$function is missing")
            val result = fn.call(cx, scope, scope, args.map { it ?: null }.toTypedArray())
            return CalendarJson.decodeFromString(serializer, Context.toString(result))
        } catch (e: Unavailable) {
            throw e
        } catch (e: Exception) {
            throw Unavailable("$function failed: ${e.message}")
        } finally {
            Context.exit()
        }
    }

    private fun enter(): Context = Context.enter().apply {
        // Android cannot load generated bytecode, so the interpreter runs the bundle everywhere.
        @Suppress("DEPRECATION")
        optimizationLevel = -1
        languageVersion = Context.VERSION_ES6
    }

    private fun loaded(cx: Context): Scriptable {
        scope?.let { return it }
        if (failed) throw Unavailable("date-holidays didn't load")
        val source = script() ?: run {
            failed = true
            throw Unavailable("date-holidays is missing")
        }
        val s = cx.initStandardObjects()
        try {
            // The UMD wrapper reads `self`, as a browser would give it.
            cx.evaluateString(s, "var self = this;", "self", 1, null)
            cx.evaluateString(s, source, "date-holidays.umd.min.js", 1, null)
            cx.evaluateString(s, HELPERS, "helpers", 1, null)
        } catch (e: Exception) {
            failed = true
            throw Unavailable("date-holidays didn't load: ${e.message}")
        }
        scope = s
        return s
    }

    companion object {
        val shared by lazy { HolidayEngine() }

        /** The language holiday names are given in: the device's full locale, since `en-US` says "Labor Day" where `en` says "Labour Day". */
        fun deviceLanguage(): String = Locale.getDefault().toLanguageTag()

        fun bundledScript(): String? =
            HolidayEngine::class.java.getResourceAsStream("/date-holidays/date-holidays.umd.min.js")?.use { it.readBytes().decodeToString() }

        /** The bundle's version, `scripts/sync_date_holidays.sh` keeps it at the web's. */
        fun bundledVersion(): String? =
            HolidayEngine::class.java.getResourceAsStream("/date-holidays/VERSION")?.use { it.readBytes().decodeToString().trim() }

        private const val HELPERS = """
            var __H = self.Holidays.default || self.Holidays;
            function __places(names) {
              return JSON.stringify(Object.keys(names || {}).map(function (code) { return { code: code, name: names[code] }; }));
            }
            function __neutrinoHolidays(country, region, year, lang) {
              var rules = region ? new __H(country, region) : new __H(country);
              return JSON.stringify((rules.getHolidays(year, lang) || []).map(function (h) {
                return { date: h.date, start: h.start.getTime(), end: h.end.getTime(), name: h.name, type: h.type };
              }));
            }
            function __neutrinoCountries(lang) { return __places(new __H().getCountries(lang)); }
            function __neutrinoRegions(country, lang) { return __places(new __H().getStates(country, lang)); }
        """
    }
}

/**
 * A holiday calendar's days as all-day events: the web's `holidaysOf`, line for line, over the
 * records [HolidayEngine] gives. Pure, so it is tested against the web's own expectations.
 */
object Holidays {
    /** Shown always; observances add the rest but school holidays. */
    val PUBLIC_TYPES = setOf("public", "bank")
    val OBSERVANCE_TYPES = setOf("optional", "observance")

    /**
     * The holidays of `calendar` from `from` to `to` (inclusive), each an all-day event dated by its
     * UTC date, `T00:00:00Z` to `T23:59:59Z` of its last day. `records` gives a year's records.
     */
    suspend fun events(calendar: UserCalendar, from: Instant, to: Instant, records: suspend (Int) -> List<HolidayEngine.Record>): List<CalendarEvent> {
        if (calendar.kind != CalendarKind.HOLIDAYS || calendar.country == null) return emptyList()
        val first = ServerDate.utcDay(from)
        val last = ServerDate.utcDay(to)
        val events = mutableListOf<CalendarEvent>()
        val seen = mutableSetOf<String>()
        for (year in first.take(4).toInt()..last.take(4).toInt()) {
            for (record in records(year)) {
                val shown = record.type in PUBLIC_TYPES || (calendar.includeObservances && record.type in OBSERVANCE_TYPES)
                if (!shown) continue
                val day = record.date.take(10)
                // Most holidays are a day; an evening one (Halloween's starts at 18:00) is still its day.
                val days = maxOf(1L, ((record.end - record.start) / 86_400_000.0).roundToLong())
                val lastDay = LocalDate.parse(day).plusDays(days - 1).toString()
                if (lastDay < first || day > last) continue
                val id = "holiday:${calendar.id}:$day:${record.name}"
                if (!seen.add(id)) continue
                events += CalendarEvent(
                    id = id, title = record.name, start = Instant.parse("${day}T00:00:00Z"), end = Instant.parse("${lastDay}T23:59:59Z"),
                    allDay = true, source = EventSource.Holidays, calendarId = calendar.id,
                )
            }
        }
        return events
    }
}
