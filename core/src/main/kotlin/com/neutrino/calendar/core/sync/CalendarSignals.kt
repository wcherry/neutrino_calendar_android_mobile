package com.neutrino.calendar.core.sync

import com.neutrino.calendar.core.model.CalendarJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * The server's "your calendar changed" signal, on the per-user notification socket
 * (`src/shared/drive_events/` in `neutrino`): `{"type":"calendar.changed","originClientId":…}`.
 *
 * A doorbell, not a delivery: it says only that something changed, and the app pulls the changes
 * itself. The same socket carries `drive.changed` signals and inbox records; both are ignored here.
 */
object CalendarSignal {
    const val TYPE = "calendar.changed"

    /** Whether `text` is a calendar signal for a change some *other* client made. Unattributed counts as another's. */
    fun isRemoteChange(text: String, ownClientId: String): Boolean {
        val json = runCatching { CalendarJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return false
        if (runCatching { json["type"]?.jsonPrimitive?.content }.getOrNull() != TYPE) return false
        return runCatching { json["originClientId"]?.jsonPrimitive?.content }.getOrNull() != ownClientId
    }

    /** `https://host` → `wss://host/api/v1/drive/notifications/ws?token=…`, and `http` → `ws`. */
    fun socketUrl(baseUrl: String, token: String): String? {
        val url = baseUrl.toHttpUrlOrNull() ?: return null
        val scheme = when (url.scheme) {
            "https" -> "wss"
            "http" -> "ws"
            else -> return null
        }
        val port = if ((url.scheme == "https" && url.port == 443) || (url.scheme == "http" && url.port == 80)) "" else ":${url.port}"
        // Not percent-encoded: the server slices the token out of the raw query. A JWT is base64url.
        return "$scheme://${url.host}$port/api/v1/drive/notifications/ws?token=$token"
    }
}

/**
 * Holds the notification socket open while the app is in the foreground, and calls
 * [onRemoteChange] for each calendar signal from another client. Reconnects with backoff, and calls
 * [onReconnect] once a dropped socket is open again, since signals sent while it was down are lost.
 */
class CalendarSignalsClient(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val baseUrl: () -> String,
    private val token: suspend () -> String?,
    private val ownClientId: String,
) {
    var onRemoteChange: (() -> Unit)? = null
    var onReconnect: (() -> Unit)? = null

    private val socketHttp by lazy { http.newBuilder().pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build() }
    private var socket: WebSocket? = null
    private var reconnect: Job? = null
    private var attempt = 0
    private var running = false
    private var connectedBefore = false

    fun start() {
        if (running) return
        running = true
        attempt = 0
        open()
    }

    /** Closes the socket: for leaving the foreground, and for sign-out. */
    fun stop() {
        running = false
        connectedBefore = false
        reconnect?.cancel()
        reconnect = null
        socket?.close(1001, null)
        socket = null
    }

    private fun open() {
        scope.launch {
            if (!running || socket != null) return@launch
            val url = token()?.let { CalendarSignal.socketUrl(baseUrl(), it) } ?: return@launch
            val request = runCatching { Request.Builder().url(url).build() }.getOrNull() ?: return@launch
            socket = socketHttp.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    scope.launch {
                        if (socket !== webSocket) return@launch
                        attempt = 0
                        if (connectedBefore) onReconnect?.invoke()
                        connectedBefore = true
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (CalendarSignal.isRemoteChange(text, ownClientId)) scope.launch { if (socket === webSocket) onRemoteChange?.invoke() }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)
            })
        }
    }

    private fun dropped(webSocket: WebSocket) {
        scope.launch {
            if (socket !== webSocket) return@launch
            socket = null
            if (!running || reconnect != null) return@launch
            val delayMillis = minOf(2_000L shl minOf(attempt, 5), 60_000L)
            attempt++
            reconnect = scope.launch {
                delay(delayMillis)
                reconnect = null
                if (running) open()
            }
        }
    }
}
