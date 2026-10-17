package com.neutrino.calendar.core.model

import com.neutrino.shared.core.crypto.PlaceEnvelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where a task reminds you: on arrival at one of your saved places, or at a one-off point.
 *
 * The server keeps the two apart (`tasks.geo_place_id`, or `geo_lat` / `geo_lng` / `geo_radius_m`).
 * A saved place is end-to-end encrypted, so only a device holding the account key knows where it
 * is; a one-off point is plaintext on purpose (wcherry/neutrino_calendar_ios_mobile#23).
 */
sealed class TaskGeofence {
    data class Place(val id: String) : TaskGeofence()
    data class Point(val point: GeoPoint) : TaskGeofence()

    val placeId: String? get() = (this as? Place)?.id
}

/** A circle on the map: what the device watches and what a one-off geofence stores. */
@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double, val radius: Int = DEFAULT_RADIUS) {
    /** Metres from here to `other` along the Earth's surface (haversine). */
    fun distanceTo(other: GeoPoint): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(latitude)
        val p2 = Math.toRadians(other.latitude)
        val dp = Math.toRadians(other.latitude - latitude)
        val dl = Math.toRadians(other.longitude - longitude)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    companion object {
        /** Arrival is reported unreliably below about 100 m, so nothing smaller is offered. */
        const val MINIMUM_RADIUS = 100
        /** Big enough for a supermarket car park. */
        const val DEFAULT_RADIUS = 150
        /** A region larger than this is a neighbourhood, not a place. */
        const val MAXIMUM_RADIUS = 2_000

        fun clamp(radius: Int): Int = radius.coerceIn(MINIMUM_RADIUS, MAXIMUM_RADIUS)

        /** A point with its radius clamped, as the iOS initialiser does. */
        fun of(latitude: Double, longitude: Double, radius: Int = DEFAULT_RADIUS) = GeoPoint(latitude, longitude, clamp(radius))
    }
}

/** One of the user's saved places ("Home", "Work", "Safeway on 5th"), decrypted. */
data class TaskPlace(val id: String, val name: String, val point: GeoPoint) {
    val payload: PlaceEnvelope.Payload get() = PlaceEnvelope.Payload(point.latitude, point.longitude, name, point.radius)

    companion object {
        fun of(id: String, payload: PlaceEnvelope.Payload) =
            TaskPlace(id, payload.name, GeoPoint.of(payload.lat, payload.lng, payload.radiusM))
    }
}

/** A saved place as the server holds it: an id and ciphertext (`TaskPlaceResponse`). */
@Serializable
data class TaskPlaceRecord(val id: String, val encryptedPayload: String)

@Serializable
data class TaskPlacesWire(val places: List<TaskPlaceRecord> = emptyList())

/** The body of a create or an update: the server never sees anything else. */
@Serializable
data class SaveTaskPlaceRequest(val encryptedPayload: String) : JsonBody {
    override fun toJson(): JsonObject = encodeObject(serializer(), this)
}

/**
 * One attachment on an event or a task: a Drive file, or a short text note, never both. Events and
 * tasks answer the same shape.
 */
@Serializable
data class Attachment(
    val id: String,
    val fileId: String? = null,
    val name: String? = null,
    val note: String? = null,
) {
    val isFile: Boolean get() = fileId != null
}

@Serializable
data class ListAttachmentsWire(val attachments: List<Attachment> = emptyList())

/** A Drive file id and its name, or a note. */
@Serializable
data class CreateAttachmentRequest(val fileId: String? = null, val name: String? = null, val note: String? = null) : JsonBody {
    override fun toJson(): JsonObject = encodeObject(serializer(), this)

    companion object {
        fun file(id: String, name: String) = CreateAttachmentRequest(fileId = id, name = name)
        fun note(text: String) = CreateAttachmentRequest(note = text)
    }
}
