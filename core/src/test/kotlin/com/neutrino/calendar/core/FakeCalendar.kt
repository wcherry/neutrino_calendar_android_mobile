package com.neutrino.calendar.core

import com.neutrino.calendar.core.api.CalendarApi
import com.neutrino.shared.core.FakeServer
import com.neutrino.shared.core.api.NeutrinoApps
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.util.UUID

/**
 * `/api/v1/calendar` in memory, on top of the shared [FakeServer]: rows stored as the JSON the real
 * server answers with, merged with each update as the server's partial updates are. Implements what
 * the Android client calls; recurrence and exceptions are the client's job, so they are stored as sent.
 */
class FakeCalendar(val fake: FakeServer = FakeServer()) {
    val events = linkedMapOf<String, JsonObject>()
    val reminders = linkedMapOf<String, JsonObject>()
    val tasks = linkedMapOf<String, JsonObject>()
    val calendars = linkedMapOf<String, JsonObject>()
    val places = linkedMapOf<String, JsonObject>()
    val attachments = mutableMapOf<String, MutableList<JsonObject>>()
    val deletions = mutableListOf<Pair<Int, String>>()
    var changeCounter = 0
    val changedAt = mutableMapOf<String, Int>()
    /** Every calendar request answers with a dropped connection: the device is offline. */
    var offline = false
    /** Answer every write with this status instead. */
    var failWritesWith: Int? = null
    val log = mutableListOf<String>()
    /** The `X-Neutrino-Client-Id` of the last calendar request. */
    var lastClientId: String? = null

    init {
        calendars["cal-default"] = buildJsonObject {
            put("id", "cal-default"); put("name", "Personal"); put("color", "#3b82f6"); put("visible", true)
            put("readOnly", false); put("kind", "local"); put("isDefault", true)
        }
        fake.extraRoutes = { r, _ -> handle(r) }
    }

    fun api(): CalendarApi = fake.client(config = NeutrinoApps.calendar) { http, auth -> CalendarApi(http, auth) }

    fun signedIn(): CalendarApi = api().also { runBlocking { it.auth.login(fake.email, fake.password) } }

    private val json = Json { ignoreUnknownKeys = true }

    private fun ok(e: JsonElement) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(e.toString())
    private fun status(code: Int) = fake.status(code)

    private fun touch(id: String) {
        changeCounter++
        changedAt[id] = changeCounter
    }

    fun putEvent(id: String, fields: JsonObject.() -> Map<String, JsonElement> = { emptyMap() }) {
        val base = buildJsonObject {
            put("id", id); put("title", id); put("startTime", "2026-10-05T16:00:00Z"); put("endTime", "2026-10-05T17:00:00Z")
            put("allDay", false); put("source", "local"); put("attendees", JsonArray(emptyList())); put("calendarId", "cal-default")
        }
        events[id] = JsonObject(base + base.fields())
        touch(id)
    }

    private fun handle(r: RecordedRequest): MockResponse? {
        val url = r.requestUrl!!
        val path = url.encodedPath
        if (!path.startsWith("/api/v1/calendar")) return null
        lastClientId = r.getHeader(CalendarApi.CLIENT_ID_HEADER)
        log += "${r.method} ${url.encodedPath}${url.encodedQuery?.let { "?$it" } ?: ""}"
        if (offline) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        if (r.method != "GET") failWritesWith?.let { return status(it) }
        val body: JsonObject? = r.body.readUtf8().takeIf { it.isNotEmpty() }?.let { json.parseToJsonElement(it) as? JsonObject }
        val p = path.removePrefix("/api/v1/calendar")

        fun merge(row: JsonObject, changes: JsonObject) = JsonObject(row + changes.filterValues { it !is JsonNull } + changes.filterValues { it is JsonNull })

        when {
            p == "/events" && r.method == "GET" -> return ok(buildJsonObject {
                put("events", JsonArray(events.values.filter { it["cancelled"]?.jsonPrimitive?.booleanOrNull != true || it["recurringEventId"] != null }))
            })
            p == "/events" && r.method == "POST" -> {
                val id = UUID.randomUUID().toString()
                events[id] = JsonObject(body!! + mapOf("id" to JsonPrimitive(id), "source" to JsonPrimitive("local")) +
                    (if (body["calendarId"] == null) mapOf("calendarId" to JsonPrimitive("cal-default")) else emptyMap()))
                touch(id)
                return ok(events[id]!!)
            }
            p == "/events/changes" -> {
                val since = url.queryParameter("since")?.toInt()
                return ok(buildJsonObject {
                    put("events", JsonArray(if (since == null) emptyList() else events.values.filter { (changedAt[it.str("id")] ?: 0) > since }))
                    put("deletedIds", buildJsonArray { if (since != null) deletions.filter { it.first > since }.forEach { add(JsonPrimitive(it.second)) } })
                    put("cursor", changeCounter.toString())
                    put("fullResyncRequired", false)
                })
            }
            p == "/sync/trigger" -> return ok(JsonObject(emptyMap()))
        }
        Regex("/events/([^/]+)/occurrences/(.+)").matchEntire(p)?.let { m ->
            val series = m.groupValues[1]
            val original = m.groupValues[2]
            val existing = events.values.firstOrNull { it.str("recurringEventId") == series && it.str("originalStartTime") == original }
            val id = existing?.str("id") ?: UUID.randomUUID().toString()
            val s = events[series] ?: return status(404)
            val duration = java.time.Duration.between(java.time.Instant.parse(s.str("startTime")), java.time.Instant.parse(s.str("endTime")))
            val base = existing ?: JsonObject(s + mapOf(
                "id" to JsonPrimitive(id), "recurrenceRule" to JsonNull, "recurringEventId" to JsonPrimitive(series),
                "originalStartTime" to JsonPrimitive(original), "startTime" to JsonPrimitive(original),
                "endTime" to JsonPrimitive(java.time.Instant.parse(original).plus(duration).toString()),
            ))
            events[id] = if (r.method == "DELETE") JsonObject(base + ("cancelled" to JsonPrimitive(true))) else merge(base, body!!)
            touch(id)
            return if (r.method == "DELETE") ok(JsonObject(emptyMap())) else ok(events[id]!!)
        }
        Regex("/events/([^/]+)/split").matchEntire(p)?.let { m ->
            val s = events[m.groupValues[1]] ?: return status(404)
            val at = body!!.str("originalStartTime")!!
            events[s.str("id")!!] = JsonObject(s + ("recurrenceRule" to JsonPrimitive(s.str("recurrenceRule") + ";UNTIL=" + at.replace("-", "").replace(":", ""))))
            touch(s.str("id")!!)
            val id = UUID.randomUUID().toString()
            events[id] = merge(JsonObject(s + mapOf("id" to JsonPrimitive(id), "startTime" to JsonPrimitive(at))), JsonObject(body - "originalStartTime"))
            touch(id)
            return ok(events[id]!!)
        }
        Regex("/events/([^/]+)/attachments(?:/([^/]+))?").matchEntire(p)?.let { m -> return attachmentsRoute(r, "event-" + m.groupValues[1], m.groupValues[2], body) }
        Regex("/events/([^/]+)").matchEntire(p)?.let { m ->
            val id = m.groupValues[1]
            val row = events[id] ?: return status(404)
            return when (r.method) {
                "GET" -> ok(row)
                "PUT" -> {
                    events[id] = merge(row, body!!)
                    touch(id)
                    ok(events[id]!!)
                }
                "DELETE" -> {
                    events.remove(id)
                    changeCounter++
                    deletions += changeCounter to id
                    ok(JsonObject(emptyMap()))
                }
                else -> status(405)
            }
        }

        // Reminders
        if (p == "/reminders" && r.method == "GET") return ok(buildJsonObject { put("reminders", JsonArray(reminders.values.toList())) })
        if (p == "/reminders" && r.method == "POST") {
            val id = UUID.randomUUID().toString()
            reminders[id] = JsonObject(body!! + mapOf("id" to JsonPrimitive(id), "completed" to JsonPrimitive(false)))
            return ok(reminders[id]!!)
        }
        Regex("/reminders/([^/]+)/skip").matchEntire(p)?.let { m ->
            val row = reminders[m.groupValues[1]] ?: return status(404)
            val next = java.time.Instant.parse(row.str("dueTime")).plus(java.time.Duration.ofDays(1)).toString()
            reminders[m.groupValues[1]] = JsonObject(row + ("dueTime" to JsonPrimitive(next)))
            return ok(buildJsonObject { put("series", reminders[m.groupValues[1]]!!) })
        }
        Regex("/reminders/([^/]+)/occurrence").matchEntire(p)?.let { m ->
            val row = reminders[m.groupValues[1]] ?: return status(404)
            val id = UUID.randomUUID().toString()
            reminders[id] = merge(JsonObject(row + mapOf("id" to JsonPrimitive(id), "recurrenceRule" to JsonNull)), JsonObject(body!! - "timezone"))
            val next = java.time.Instant.parse(row.str("dueTime")).plus(java.time.Duration.ofDays(1)).toString()
            reminders[m.groupValues[1]] = JsonObject(row + ("dueTime" to JsonPrimitive(next)))
            return ok(buildJsonObject { put("reminder", reminders[id]!!); put("series", reminders[m.groupValues[1]]!!) })
        }
        Regex("/reminders/([^/]+)").matchEntire(p)?.let { m ->
            val id = m.groupValues[1]
            val row = reminders[id] ?: return status(404)
            return when (r.method) {
                "GET" -> ok(row)
                "PATCH" -> {
                    var updated = merge(row, JsonObject(body!! - "timezone"))
                    // Completing a repeating reminder moves it to its next occurrence instead.
                    if (body["completed"]?.jsonPrimitive?.booleanOrNull == true && !row.str("recurrenceRule").isNullOrEmpty()) {
                        val next = java.time.Instant.parse(row.str("dueTime")).plus(java.time.Duration.ofDays(1)).toString()
                        updated = JsonObject(updated + mapOf("completed" to JsonPrimitive(false), "dueTime" to JsonPrimitive(next)))
                    }
                    reminders[id] = updated
                    ok(updated)
                }
                "DELETE" -> {
                    reminders.remove(id)
                    ok(JsonObject(emptyMap()))
                }
                else -> status(405)
            }
        }

        // Calendars
        if (p == "/calendars" && r.method == "GET") return ok(buildJsonObject { put("calendars", JsonArray(calendars.values.toList())) })
        if (p == "/calendars" && r.method == "POST") {
            if (body!!.str("country") != null && calendars.values.any { it.str("country") == body.str("country") }) return status(409)
            val id = UUID.randomUUID().toString()
            val kind = body.str("kind") ?: "local"
            calendars[id] = JsonObject(body + mapOf(
                "id" to JsonPrimitive(id), "visible" to JsonPrimitive(true), "readOnly" to JsonPrimitive(kind == "holidays"),
                "kind" to JsonPrimitive(kind), "isDefault" to JsonPrimitive(false),
            ))
            return ok(calendars[id]!!)
        }
        Regex("/calendars/([^/]+)").matchEntire(p)?.let { m ->
            val id = m.groupValues[1]
            val row = calendars[id] ?: return status(404)
            return when (r.method) {
                "PATCH" -> {
                    calendars[id] = merge(row, body!!)
                    ok(calendars[id]!!)
                }
                "DELETE" -> {
                    if (row["isDefault"]?.jsonPrimitive?.booleanOrNull == true) return status(400)
                    calendars.remove(id)
                    ok(JsonObject(emptyMap()))
                }
                else -> status(405)
            }
        }

        // Tasks
        if (p == "/tasks" && r.method == "GET") return ok(JsonArray(tasks.values.toList()))
        if (p == "/tasks" && r.method == "POST") {
            val id = UUID.randomUUID().toString()
            tasks[id] = JsonObject(body!! + mapOf("id" to JsonPrimitive(id), "done" to JsonPrimitive(false), "position" to JsonPrimitive(tasks.size)))
            return ok(tasks[id]!!)
        }
        if (p == "/tasks/reorder") {
            val ids = body!!["taskIds"]!! as JsonArray
            ids.forEachIndexed { i, e -> tasks[e.jsonPrimitive.content]?.let { tasks[e.jsonPrimitive.content] = JsonObject(it + ("position" to JsonPrimitive(i))) } }
            return ok(JsonObject(emptyMap()))
        }
        Regex("/tasks/([^/]+)/event").matchEntire(p)?.let { m ->
            val id = m.groupValues[1]
            val task = tasks[id] ?: return status(404)
            if (r.method == "DELETE") {
                task.str("eventId")?.let { events.remove(it) }
                tasks[id] = JsonObject(task + ("eventId" to JsonNull))
                return ok(tasks[id]!!)
            }
            val eventId = task.str("eventId") ?: UUID.randomUUID().toString()
            events[eventId] = JsonObject(body!! + mapOf("id" to JsonPrimitive(eventId), "title" to task["title"]!!, "source" to JsonPrimitive("local"), "calendarId" to JsonPrimitive("cal-default")))
            touch(eventId)
            tasks[id] = JsonObject(task + ("eventId" to JsonPrimitive(eventId)))
            return ok(events[eventId]!!)
        }
        Regex("/tasks/([^/]+)/attachments(?:/([^/]+))?").matchEntire(p)?.let { m -> return attachmentsRoute(r, "task-" + m.groupValues[1], m.groupValues[2], body) }
        Regex("/tasks/([^/]+)").matchEntire(p)?.let { m ->
            val id = m.groupValues[1]
            val row = tasks[id] ?: return status(404)
            var updated = merge(row, JsonObject(body!! - "timezone"))
            var next: JsonObject? = null
            if (body["done"]?.jsonPrimitive?.booleanOrNull == true && !row.str("recurrenceRule").isNullOrEmpty()) {
                val nextId = UUID.randomUUID().toString()
                next = JsonObject(row + mapOf("id" to JsonPrimitive(nextId), "done" to JsonPrimitive(false)))
                tasks[nextId] = next
            }
            tasks[id] = updated
            if (next != null) updated = JsonObject(updated + ("nextTask" to next))
            return ok(updated)
        }

        // Saved places
        if (p == "/task-places" && r.method == "GET") return ok(buildJsonObject { put("places", JsonArray(places.values.toList())) })
        if (p == "/task-places" && r.method == "POST") {
            val id = UUID.randomUUID().toString()
            places[id] = buildJsonObject { put("id", id); put("encryptedPayload", body!!.str("encryptedPayload")) }
            return ok(places[id]!!)
        }
        Regex("/task-places/([^/]+)").matchEntire(p)?.let { m ->
            val id = m.groupValues[1]
            places[id] ?: return status(404)
            return if (r.method == "DELETE") {
                places.remove(id)
                tasks.replaceAll { _, t -> if (t.str("geoPlaceId") == id) JsonObject(t + ("geoPlaceId" to JsonNull)) else t }
                ok(JsonObject(emptyMap()))
            } else {
                places[id] = buildJsonObject { put("id", id); put("encryptedPayload", body!!.str("encryptedPayload")) }
                ok(places[id]!!)
            }
        }
        return status(404)
    }

    private fun attachmentsRoute(r: RecordedRequest, owner: String, attachmentId: String, body: JsonObject?): MockResponse {
        val list = attachments.getOrPut(owner) { mutableListOf() }
        return when (r.method) {
            "GET" -> ok(buildJsonObject { put("attachments", JsonArray(list.toList())) })
            "POST" -> {
                val row = JsonObject(body!! + ("id" to JsonPrimitive(UUID.randomUUID().toString())))
                list += row
                ok(row)
            }
            "DELETE" -> {
                list.removeAll { it.str("id") == attachmentId }
                ok(JsonObject(emptyMap()))
            }
            else -> status(405)
        }
    }

    fun shutdown() = fake.shutdown()
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
