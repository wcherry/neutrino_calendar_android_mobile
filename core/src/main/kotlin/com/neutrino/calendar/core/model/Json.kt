package com.neutrino.calendar.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * How Calendar reads and writes JSON. Reading is tolerant — unknown fields ignored, a `null` where a
 * default exists taken as the default — so a server that adds or drops a field can't empty the
 * calendar. Writing leaves out every `null`, so a request carries only what it sets: the server
 * reads an absent field as "leave it alone".
 */
@OptIn(ExperimentalSerializationApi::class)
val CalendarJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = false
}

/** A request body. [toJson] is what is sent, and what an edit conflict is measured on. */
interface JsonBody {
    fun toJson(): JsonObject
}

fun <T> encodeObject(serializer: KSerializer<T>, value: T): JsonObject =
    CalendarJson.encodeToJsonElement(serializer, value).jsonObject

/**
 * A field that can be left alone, set, or cleared.
 *
 * The server's update reads an *absent* field as "leave it alone" and a JSON `null` as "clear it",
 * so emptying the notes has to send `null` and an untouched field has to send nothing. A nullable
 * can only say one of those.
 */
sealed class Patch<out T> {
    object Keep : Patch<Nothing>() {
        override fun toString() = "Keep"
    }
    data class Set<T>(val value: T) : Patch<T>()
    object Clear : Patch<Nothing>() {
        override fun toString() = "Clear"
    }

    val isKeep: Boolean get() = this === Keep
}

/** Nothing for [Patch.Keep], the value for [Patch.Set], and a JSON `null` for [Patch.Clear]. */
fun JsonObjectBuilder.patch(key: String, patch: Patch<Any?>) {
    when (patch) {
        Patch.Keep -> Unit
        Patch.Clear -> put(key, JsonNull)
        is Patch.Set -> put(key, jsonValue(patch.value))
    }
}

fun jsonValue(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is JsonElement -> value
    is List<*> -> kotlinx.serialization.json.JsonArray(value.map(::jsonValue))
    else -> error("unsupported JSON value ${value::class}")
}
