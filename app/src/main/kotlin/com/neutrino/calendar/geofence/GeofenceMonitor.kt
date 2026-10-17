package com.neutrino.calendar.geofence

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.neutrino.calendar.R
import com.neutrino.calendar.core.logic.GeofencePlan
import com.neutrino.calendar.core.logic.GeofenceRegion
import com.neutrino.calendar.core.model.CalendarJson
import com.neutrino.calendar.core.model.CalendarTask
import com.neutrino.calendar.core.model.GeoPoint
import com.neutrino.calendar.core.model.TaskPlace
import com.neutrino.calendar.graph
import com.neutrino.calendar.notifications.ReminderAlarms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/** How much of the device's location this app may use. */
enum class LocationAccess(val label: String) {
    NOT_ASKED("Not asked"), WHILE_USING("While using"), ALWAYS("Always"), OFF("Off");
}

/**
 * Arrival alerts for tasks: Play services watches up to [GeofencePlan.REGION_LIMIT] circles and
 * this posts a notification when you enter one. Nothing is sent to the server; it never learns where
 * you are. The iOS `GeofenceMonitor`.
 *
 * - **What is watched** is [GeofencePlan]'s answer for the open tasks and the saved places, re-picked
 *   when either list changes.
 * - **Started by the system.** An arrival can start a process with nothing loaded, so the candidate
 *   regions, with their task titles, are kept in a file, and so is what was registered (Play services
 *   can't be asked). A reboot drops every geofence; [reapply] registers them again.
 * - **Permission** is asked in context: fine location the first time a place is picked or Nearby is
 *   turned on, then background ("Allow all the time") once a task has a place.
 */
class GeofenceMonitor(private val context: Context) {

    private val client = LocationServices.getGeofencingClient(context)
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val storeFile = File(context.noBackupFilesDir, "geofences.json")
    private val registeredFile = File(context.noBackupFilesDir, "geofences-registered.json")
    private var candidates: List<GeofenceRegion> = loadCandidates()

    private val _access = MutableStateFlow(currentAccess())
    val access: StateFlow<LocationAccess> = _access

    /** The device's last known position, for Nearby and for picking the nearest regions. */
    val location = MutableStateFlow<GeoPoint?>(null)

    fun refreshAccess() {
        _access.value = currentAccess()
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun currentAccess(): LocationAccess {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine) {
            val asked = context.getSharedPreferences("ncal.location", Context.MODE_PRIVATE).getBoolean("asked", false)
            return if (asked) LocationAccess.OFF else LocationAccess.NOT_ASKED
        }
        val background = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        return if (background) LocationAccess.ALWAYS else LocationAccess.WHILE_USING
    }

    fun markAsked() {
        context.getSharedPreferences("ncal.location", Context.MODE_PRIVATE).edit().putBoolean("asked", true).apply()
        refreshAccess()
    }

    /** Arrivals are reported in the background only with "Allow all the time". */
    val canAlert: Boolean get() = access.value == LocationAccess.ALWAYS && granted(Manifest.permission.ACCESS_FINE_LOCATION)

    /** Why arrival alerts aren't working, in words for the task screen; null when they are. */
    val inactiveReason: String?
        get() = when (access.value) {
            LocationAccess.ALWAYS -> null
            LocationAccess.WHILE_USING -> "Arrival alerts need location access set to “Allow all the time”. The task is saved either way."
            LocationAccess.OFF -> "Location access is off for Calendar, so this place won't alert you. The task is saved either way."
            LocationAccess.NOT_ASKED -> "Calendar will ask for your location so it can alert you on arrival."
        }

    /** The device's position now, or the last known one; null if neither can be had. One fix, not a stream. */
    @SuppressLint("MissingPermission")
    suspend fun currentLocation(): GeoPoint? {
        refreshAccess()
        if (access.value == LocationAccess.NOT_ASKED || access.value == LocationAccess.OFF) return location.value
        val token = CancellationTokenSource()
        val fix = runCatching { fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, token.token).await() }.getOrNull()
            ?: runCatching { fused.lastLocation.await() }.getOrNull()
        val point = fix?.let { GeoPoint.of(it.latitude, it.longitude, GeoPoint.MINIMUM_RADIUS) }
        if (point != null) location.value = point
        return point ?: location.value
    }

    // ── Planning ────────────────────────────────────────────────────────────────────────────────

    fun replan(tasks: List<CalendarTask>, places: List<TaskPlace>) {
        candidates = GeofencePlan.candidates(tasks, places)
        storeFile.writeText(CalendarJson.encodeToString(ListSerializer(GeofenceRegion.serializer()), candidates))
        apply()
    }

    /** After a reboot: Play services forgot everything, so everything planned is registered again. */
    fun reapply() {
        saveRegistered(emptyMap())
        apply()
    }

    /** Hands Play services the nearest regions, changing only what changed. */
    @SuppressLint("MissingPermission")
    fun apply() {
        refreshAccess()
        if (!canAlert) {
            stopAll()
            return
        }
        val planned = GeofencePlan.nearest(candidates, location.value)
        val registered = loadRegistered()
        val (remove, add) = GeofencePlan.changes(registered, planned)
        if (remove.isNotEmpty()) client.removeGeofences(remove)
        if (add.isNotEmpty()) {
            val request = GeofencingRequest.Builder()
                // Only an arrival: already being there when the plan is made is not one.
                .setInitialTrigger(0)
                .addGeofences(add.map { region ->
                    Geofence.Builder()
                        .setRequestId(region.identifier)
                        .setCircularRegion(region.center.latitude, region.center.longitude, region.center.radius.toFloat())
                        .setExpirationDuration(Geofence.NEVER_EXPIRE)
                        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                        .build()
                })
                .build()
            client.addGeofences(request, pendingIntent())
        }
        saveRegistered(planned.associate { it.identifier to it.center })
    }

    /** Sign-out: the next account must not be alerted at this one's places. */
    fun stop() {
        candidates = emptyList()
        storeFile.delete()
        stopAll()
    }

    private fun stopAll() {
        val registered = loadRegistered().keys.filter { it.startsWith(GeofencePlan.REGION_PREFIX) }
        if (registered.isNotEmpty()) runCatching { client.removeGeofences(registered) }
        saveRegistered(emptyMap())
    }

    fun region(identifier: String): GeofenceRegion? = candidates.firstOrNull { it.identifier == identifier }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, GeofenceReceiver::class.java),
        // Play services fills the intent in, so it must be mutable.
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
    )

    private fun loadCandidates(): List<GeofenceRegion> = storeFile.takeIf { it.exists() }?.let {
        runCatching { CalendarJson.decodeFromString(ListSerializer(GeofenceRegion.serializer()), it.readText()) }.getOrNull()
    } ?: emptyList()

    private val registeredSerializer = MapSerializer(String.serializer(), GeoPoint.serializer())

    private fun loadRegistered(): Map<String, GeoPoint> = registeredFile.takeIf { it.exists() }?.let {
        runCatching { CalendarJson.decodeFromString(registeredSerializer, it.readText()) }.getOrNull()
    } ?: emptyMap()

    private fun saveRegistered(map: Map<String, GeoPoint>) {
        registeredFile.writeText(CalendarJson.encodeToString(registeredSerializer, map))
    }
}

suspend fun <T> Task<T>.await(): T? = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task -> cont.resume(if (task.isSuccessful) task.result else null) }
}

/**
 * One notification per open task at the place. An entry is reported once per arrival, so it fires
 * once until you leave and come back; completing the task takes it out of the plan.
 */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError() || event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        val graph = context.graph
        if (!graph.prefs.alertsEnabled.value || !graph.alarms.hasPermission) return
        for (fence in event.triggeringGeofences.orEmpty()) {
            val region = graph.geofences.region(fence.requestId) ?: continue
            for (alert in region.alerts) {
                // A task completed here since the plan was saved is skipped, even before a re-plan.
                if (graph.tasks.task(alert.taskId)?.done == true) continue
                val notification = NotificationCompat.Builder(context, ReminderAlarms.CHANNEL_ARRIVALS)
                    .setSmallIcon(R.drawable.ic_shortcut_task)
                    .setContentTitle(alert.title)
                    .setContentText("You're at ${region.name}")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setGroup("arrivals")
                    .setContentIntent(ReminderAlarms.openIntent(context, "neutrinocalendar://task/${alert.taskId}"))
                    .addAction(0, "Mark as Done", ReminderAlarms.actionIntent(context, ReminderAlarms.ACTION_DONE, { putExtra(ReminderAlarms.EXTRA_TASK_ID, alert.taskId) }, alert.taskId))
                    .build()
                @Suppress("MissingPermission")
                NotificationManagerCompat.from(context).notify(ReminderAlarms.notificationId(alert.taskId), notification)
            }
        }
    }
}

/** A place found by searching the map: what the picker lists and what Smart Add offers for an `@Safeway`. */
data class PlaceSearchResult(val name: String, val detail: String, val point: GeoPoint)

/**
 * The platform geocoder's search. The query goes to the device's geocoding provider (Google's, on
 * most phones), as a search in Maps would; nothing about it reaches the Neutrino server.
 */
object PlaceSearch {
    suspend fun search(context: Context, query: String, near: GeoPoint?): List<PlaceSearchResult> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isEmpty() || !Geocoder.isPresent()) return@withContext emptyList()
        val geocoder = Geocoder(context, Locale.getDefault())
        @Suppress("DEPRECATION")
        val found = if (near != null) {
            // A box of about 20 km around you first, as the iOS search biases to the region.
            val d = 0.1
            geocoder.getFromLocationName(text, 10, near.latitude - d, near.longitude - d, near.latitude + d, near.longitude + d)
                ?.takeIf { it.isNotEmpty() } ?: geocoder.getFromLocationName(text, 10)
        } else {
            geocoder.getFromLocationName(text, 10)
        }
        found.orEmpty().map { a ->
            val street = listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ")
            val detail = listOf(street, a.locality.orEmpty()).filter { it.isNotEmpty() }.joinToString(", ")
            PlaceSearchResult(a.featureName?.takeIf { it != a.subThoroughfare } ?: text, detail, GeoPoint.of(a.latitude, a.longitude))
        }
    }
}
