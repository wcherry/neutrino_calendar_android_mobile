package com.neutrino.calendar.core.sync

import com.neutrino.calendar.core.api.CalendarApi
import com.neutrino.calendar.core.api.httpStatus
import com.neutrino.calendar.core.api.isNetwork
import com.neutrino.calendar.core.model.CalendarJson
import com.neutrino.calendar.core.model.JsonBody
import com.neutrino.shared.core.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/**
 * An edit or delete that could not reach the server, kept to be sent again. Only `PUT`, `PATCH` and
 * `DELETE`: they name the thing they change, so sending one twice does no harm. A create is never
 * queued; replaying one could make a duplicate.
 */
@Serializable
data class PendingWrite(
    val method: String,
    /** Server-relative: `/api/v1/calendar/events/<id>`. */
    val path: String,
    val body: String? = null,
    val id: String = UUID.randomUUID().toString(),
    val attempts: Int = 0,
) {
    companion object {
        fun of(method: String, path: String, body: JsonBody? = null) = PendingWrite(method, path, body?.toJson()?.toString())
    }
}

/**
 * Writes made offline, sent in order once the server can be reached again.
 *
 * Saved to a file after every change, so a queued edit survives the app being closed. The replay is
 * last-writer-wins on the fields each write carries; an edit only sends what it changed, so a field
 * changed elsewhere meanwhile is overwritten only when this edit changed the same field.
 */
class PendingWrites(private val file: File?) {

    private val _writes = MutableStateFlow(load())
    val writes: StateFlow<List<PendingWrite>> = _writes

    /**
     * Why queued changes were discarded, until the user dismisses it: a write the server refused for
     * good (403, the calendar is read-only now) would otherwise vanish without a word.
     */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    private var rejected = 0
    private var isReplaying = false

    val isEmpty: Boolean get() = _writes.value.isEmpty()

    fun enqueue(write: PendingWrite) {
        var list = _writes.value
        // A later delete makes every queued edit of the same thing moot.
        if (write.method == "DELETE") list = list.filter { it.path != write.path }
        _writes.value = list + write
        save()
    }

    /** Forgets every queued write, for sign-out: the next account must not send this one's edits. */
    fun clear() {
        _writes.value = emptyList()
        dismissNotice()
        save()
    }

    fun dismissNotice() {
        _notice.value = null
        rejected = 0
    }

    /**
     * Sends the queued writes in order, stopping at the first that still can't get through. Returns
     * how many were done with, sent or dropped, so the caller knows whether to reload.
     */
    suspend fun replay(api: CalendarApi): Int {
        if (isReplaying) return 0
        isReplaying = true
        var finished = 0
        try {
            while (true) {
                val write = _writes.value.firstOrNull() ?: break
                try {
                    api.replay(write.method, write.path, write.body)
                } catch (e: Exception) {
                    if (e.isNetwork || e is ApiException.NotSignedIn || e is ApiException.SessionExpired) break
                    val status = e.httpStatus
                    if (status != null && status >= 500) {
                        if (_writes.value.firstOrNull()?.id != write.id) break
                        val bumped = write.copy(attempts = write.attempts + 1)
                        _writes.value = listOf(bumped) + _writes.value.drop(1)
                        save()
                        // One bad write can't hold up every one queued behind it for ever.
                        if (bumped.attempts < MAX_ATTEMPTS) break
                    }
                    // A 404 means it was deleted elsewhere; any other 4xx is a write the server will
                    // never take. A 403 is an event whose calendar is read-only now: say so.
                    if (status == 403) noteReadOnly()
                }
                // Sign-out may have cleared the queue while this write was in flight.
                if (_writes.value.firstOrNull()?.id != write.id) break
                _writes.value = _writes.value.drop(1)
                save()
                finished++
            }
        } finally {
            isReplaying = false
        }
        return finished
    }

    private fun noteReadOnly() {
        rejected++
        _notice.value = if (rejected == 1) "A change made offline wasn't saved: the event is in a read-only calendar."
        else "$rejected changes made offline weren't saved: their events are in read-only calendars."
    }

    private fun load(): List<PendingWrite> = file?.takeIf { it.exists() }?.let {
        runCatching { CalendarJson.decodeFromString(ListSerializer(PendingWrite.serializer()), it.readText()) }.getOrNull()
    } ?: emptyList()

    private fun save() {
        val file = file ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(CalendarJson.encodeToString(ListSerializer(PendingWrite.serializer()), _writes.value))
            tmp.renameTo(file)
        }
    }

    companion object {
        /** A write the server keeps failing with a 5xx is dropped after this many tries. */
        const val MAX_ATTEMPTS = 5
    }
}
