package com.neutrino.calendar.core.service

import com.neutrino.calendar.core.api.CalendarApi
import com.neutrino.calendar.core.api.isNotFound
import com.neutrino.calendar.core.logic.TaskFilter
import com.neutrino.calendar.core.logic.titleOrder
import com.neutrino.calendar.core.model.GeoPoint
import com.neutrino.calendar.core.model.SaveTaskPlaceRequest
import com.neutrino.calendar.core.model.TaskPlace
import com.neutrino.calendar.core.model.TaskPlaceRecord
import com.neutrino.shared.core.crypto.PlaceEnvelope
import com.neutrino.shared.core.keys.KeyException
import com.neutrino.shared.core.keys.Keyring
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The user's saved places, decrypted on this device.
 *
 * The server holds each place as a [PlaceEnvelope] and can't read its name or where it is, so
 * everything that needs a place — the picker, Smart Add's `@Home`, the geofence planner, Nearby —
 * reads it from here. A place this device can't open (no key yet, or a version it lacks) is counted
 * in [unreadable] rather than shown, and its tasks aren't watched.
 */
class PlacesService(private val api: CalendarApi, private val keyring: () -> Keyring?) {

    private val _places = MutableStateFlow<List<TaskPlace>>(emptyList())
    val places: StateFlow<List<TaskPlace>> = _places
    private val _hasLoaded = MutableStateFlow(false)
    val hasLoaded: StateFlow<Boolean> = _hasLoaded
    /** Saved places this device couldn't decrypt. */
    val unreadable = MutableStateFlow(0)
    /** False once the server has answered 404: it predates saved places. One-off points still work. */
    val isSupported = MutableStateFlow(true)
    val error = MutableStateFlow<String?>(null)

    fun place(id: String) = _places.value.firstOrNull { it.id == id }

    /** Saving a place needs the account key, as reading one does. */
    val canSave: Boolean get() = isSupported.value && keyring()?.active != null

    suspend fun reload() {
        error.value = null
        try {
            val records = api.taskPlaces()
            val opened = records.mapNotNull(::open)
            _places.value = opened.sortedWith { a, b -> titleOrder(a.name, b.name) }
            unreadable.value = records.size - opened.size
            isSupported.value = true
            _hasLoaded.value = true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e.isNotFound) {
                _places.value = emptyList()
                unreadable.value = 0
                isSupported.value = false
                _hasLoaded.value = true
            } else {
                error.value = e.message
            }
        }
    }

    fun reset() {
        _places.value = emptyList()
        _hasLoaded.value = false
        unreadable.value = 0
        isSupported.value = true
        error.value = null
    }

    suspend fun create(name: String, point: GeoPoint): TaskPlace {
        val draft = TaskPlace("", name, point)
        val record = api.createTaskPlace(SaveTaskPlaceRequest(seal(draft.payload)))
        return draft.copy(id = record.id).also(::replace)
    }

    suspend fun update(place: TaskPlace): TaskPlace {
        api.updateTaskPlace(place.id, SaveTaskPlaceRequest(seal(place.payload)))
        return place.also(::replace)
    }

    /** The server clears the place from every task that used it; the caller updates the tasks. */
    suspend fun delete(place: TaskPlace) {
        api.deleteTaskPlace(place.id)
        _places.value = _places.value.filter { it.id != place.id }
    }

    /** The saved place `text` names, for `@Home`: an exact name, else the only one starting with it. Never a guess. */
    fun match(text: String): TaskPlace? = match(text, _places.value)

    private fun seal(payload: PlaceEnvelope.Payload): String {
        val active = keyring()?.active ?: throw KeyException.NoEncryptionKey()
        return PlaceEnvelope.seal(payload, active.publicKey, active.version)
    }

    private fun open(record: TaskPlaceRecord): TaskPlace? = runCatching {
        val ring = keyring() ?: return null
        val version = PlaceEnvelope.keyVersion(record.encryptedPayload)
        // The named version first, then any other, newest first: a key file renumbering can't strand one.
        val entries = ring.entries.filter { it.version == version } + ring.entries.filter { it.version != version }.sortedByDescending { it.version }
        entries.firstNotNullOfOrNull { entry -> runCatching { PlaceEnvelope.open(record.encryptedPayload, entry.secretKey) }.getOrNull() }
            ?.let { TaskPlace.of(record.id, it) }
    }.getOrNull()

    private fun replace(place: TaskPlace) {
        _places.value = (_places.value.filter { it.id != place.id } + place).sortedWith { a, b -> titleOrder(a.name, b.name) }
    }

    companion object {
        fun match(text: String, places: List<TaskPlace>): TaskPlace? {
            fun fold(s: String) = TaskFilter.fold(s).replace(Regex("[^\\p{L}\\p{N}]"), "")
            val query = fold(text)
            if (query.isEmpty()) return null
            places.firstOrNull { fold(it.name) == query }?.let { return it }
            return places.filter { fold(it.name).startsWith(query) }.singleOrNull()
        }
    }
}
