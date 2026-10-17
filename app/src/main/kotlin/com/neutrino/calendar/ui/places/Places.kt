package com.neutrino.calendar.ui.places

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.neutrino.calendar.core.logic.NearbyTasks
import com.neutrino.calendar.core.model.GeoPoint
import com.neutrino.calendar.core.model.TaskGeofence
import com.neutrino.calendar.core.model.TaskPlace
import com.neutrino.calendar.geofence.LocationAccess
import com.neutrino.calendar.geofence.PlaceSearch
import com.neutrino.calendar.geofence.PlaceSearchResult
import com.neutrino.calendar.graph
import com.neutrino.calendar.ui.ActionRow
import com.neutrino.calendar.ui.ErrorText
import com.neutrino.calendar.ui.FormRow
import com.neutrino.calendar.ui.FormSection
import com.neutrino.calendar.ui.PageBar
import com.neutrino.calendar.ui.SwitchRow
import com.neutrino.calendar.ui.event.PlainField
import com.neutrino.calendar.ui.tasks.rememberLocationPermission
import com.neutrino.shared.android.ui.ConfirmDialog
import com.neutrino.shared.android.ui.TextInputDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import android.graphics.Color as AndroidColor
import org.osmdroid.util.GeoPoint as OsmPoint

/** A spot picked from the map, being sized and maybe saved. */
private data class Draft(val name: String, val detail: String, val point: GeoPoint)

/**
 * Picks where a task reminds you: one of your saved places, a place found by search, or where you
 * are now, with the radius around it. A new spot can be saved as a place on the way.
 */
@Composable
fun PlacePickerDialog(onDismiss: () -> Unit, onPick: (name: String, geofence: TaskGeofence) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val places = graph.places
    val saved by places.places.collectAsState()
    val supported by places.isSupported.collectAsState()
    val unreadable by places.unreadable.collectAsState()
    val access by graph.geofences.access.collectAsState()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceSearchResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    val askLocation = rememberLocationPermission()

    LaunchedEffect(Unit) {
        if (access == LocationAccess.NOT_ASKED) askLocation()
        if (!places.hasLoaded.value) places.reload()
    }
    // Searches as you type, once you pause.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(400)
        searching = true
        searchError = null
        results = runCatching { PlaceSearch.search(context, query, graph.geofences.location.value) }
            .onFailure { searchError = "Couldn't search the map. Check your connection." }.getOrDefault(emptyList())
        searching = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val d = draft
            if (d != null) {
                PlaceDraftScreen(d.name, d.detail, d.point, onBack = { draft = null }) { name, geofence ->
                    onPick(name, geofence)
                    onDismiss()
                }
                return@Surface
            }
            Column(Modifier.fillMaxSize().imePadding()) {
                PageBar("Remind Me At", onDismiss)
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search for a place") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (query.isBlank()) {
                        FormSection {
                            ActionRow("Current Location", Icons.Filled.MyLocation, enabled = access != LocationAccess.OFF) {
                                scope.launch {
                                    val here = graph.geofences.currentLocation()
                                    if (here == null) searchError = "Your location isn't available." else draft = Draft("", "Current location", GeoPoint.of(here.latitude, here.longitude))
                                }
                            }
                        }
                        ErrorText(searchError)
                        FormSection("Saved Places", footer = if (unreadable > 0) "$unreadable saved place(s) can't be opened on this phone. Add your encryption key in Settings › Encryption." else null) {
                            saved.forEach { p -> PlaceRow(p) { onPick(p.name, TaskGeofence.Place(p.id)); onDismiss() } }
                            if (saved.isEmpty()) FormRow(divider = false) {
                                Text(if (supported) "No saved places yet. Search for one, then save it." else "This server doesn't support saved places yet.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        FormSection {
                            when {
                                searching && results.isEmpty() -> FormRow(divider = false) { CircularProgressIndicator(Modifier.size(20.dp)) }
                                searchError != null -> ErrorText(searchError)
                                results.isEmpty() -> FormRow(divider = false) { Text("No places found", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            results.forEach { r ->
                                FormRow(onClick = { draft = Draft(r.name, r.detail, r.point) }) {
                                    Column {
                                        Text(r.name)
                                        if (r.detail.isNotEmpty()) Text(r.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        // Saved places that match come first: picking one of those needs no radius.
                        val matching = saved.filter { it.name.contains(query.trim(), ignoreCase = true) }
                        if (matching.isNotEmpty()) FormSection("Saved Places") {
                            matching.forEach { p -> PlaceRow(p) { onPick(p.name, TaskGeofence.Place(p.id)); onDismiss() } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(place: TaskPlace, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    FormRow(onClick = onClick) {
        Icon(Icons.Filled.Place, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(place.name, Modifier.weight(1f))
        if (trailing != null) trailing() else Text(NearbyTasks.format(place.point.radius.toDouble()), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A spot on the map: how far around it counts as arriving, and whether to keep it as a place. */
@Composable
private fun PlaceDraftScreen(initialName: String, detail: String, point: GeoPoint, onBack: () -> Unit, onDone: (String, TaskGeofence) -> Unit) {
    val graph = LocalContext.current.graph
    val places = graph.places
    val supported by places.isSupported.collectAsState()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialName) }
    var radius by remember { mutableFloatStateOf(point.radius.toFloat()) }
    var saveAsPlace by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val sized = GeoPoint.of(point.latitude, point.longitude, radius.toInt())

    Column(Modifier.fillMaxSize().imePadding()) {
        PageBar("Place", onBack) {
            TextButton(enabled = name.isNotBlank() && !saving, onClick = {
                if (!saveAsPlace) {
                    onDone(name.trim(), TaskGeofence.Point(sized))
                    return@TextButton
                }
                saving = true
                scope.launch {
                    runCatching { places.create(name.trim(), sized) }
                        .onSuccess { onDone(it.name, TaskGeofence.Place(it.id)) }
                        .onFailure { error = "Couldn't save the place. ${it.message}" }
                    saving = false
                }
            }) { Text("Done") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FormSection {
                CircleMap(sized, Modifier.fillMaxWidth().height(220.dp))
                PlainField(name, { name = it }, "Name", capitalize = true, divider = detail.isNotEmpty())
                if (detail.isNotEmpty()) FormRow(divider = false) { Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            FormSection("Radius: ${NearbyTasks.format(radius.toDouble())}", footer = "You're reminded when you arrive within this distance.") {
                Slider(radius, { radius = (Math.round(it / 50f) * 50f) }, valueRange = GeoPoint.MINIMUM_RADIUS.toFloat()..GeoPoint.MAXIMUM_RADIUS.toFloat(),
                    modifier = Modifier.padding(horizontal = 16.dp))
            }
            FormSection(footer = when {
                !supported -> "This server doesn't support saved places yet."
                !places.canSave -> "Saved places are end-to-end encrypted. Add your encryption key in Settings › Encryption to save one."
                else -> "Saved places are end-to-end encrypted: only your devices know where they are."
            }) {
                SwitchRow("Save as a place", saveAsPlace, enabled = places.canSave) { saveAsPlace = it }
            }
            ErrorText(error)
        }
    }
}

/** The circle on an OpenStreetMap map. Tiles come from OpenStreetMap's servers; the place stays here. */
@Composable
fun CircleMap(point: GeoPoint, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    AndroidView(
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            }
        },
        update = { map ->
            val center = OsmPoint(point.latitude, point.longitude)
            map.overlays.clear()
            val circle = Polygon(map).apply {
                points = Polygon.pointsAsCircle(center, point.radius.toDouble())
                val argb = android.graphics.Color.argb(255, (accent.red * 255).toInt(), (accent.green * 255).toInt(), (accent.blue * 255).toInt())
                fillPaint.color = AndroidColor.argb(40, AndroidColor.red(argb), AndroidColor.green(argb), AndroidColor.blue(argb))
                outlinePaint.color = argb
                outlinePaint.strokeWidth = 3f
            }
            map.overlays.add(circle)
            map.overlays.add(Marker(map).apply { position = center; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) })
            // About three radii across.
            val zoom = 16.0 - Math.log(point.radius / 150.0) / Math.log(2.0)
            map.controller.setZoom(zoom.coerceIn(10.0, 18.0))
            map.controller.setCenter(center)
            map.invalidate()
        },
        modifier = modifier,
    )
}

/**
 * Settings › Saved Places: rename or delete the places tasks remind you at. Deleting one takes it
 * off every task that used it, on the server and here, and stops watching it.
 */
@Composable
fun SavedPlacesScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val places = graph.places
    val saved by places.places.collectAsState()
    val loaded by places.hasLoaded.collectAsState()
    val supported by places.isSupported.collectAsState()
    val unreadable by places.unreadable.collectAsState()
    val tasks by graph.tasks.tasks.collectAsState()
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<TaskPlace?>(null) }
    var deleting by remember { mutableStateOf<TaskPlace?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { places.reload() }

    Column(Modifier.fillMaxSize()) {
        PageBar("Saved Places", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            FormSection(footer = "Saved places are end-to-end encrypted: the server stores them, but only your devices can read where they are." +
                if (unreadable > 0) "\n\n$unreadable saved place(s) can't be opened on this phone. Add your encryption key in Settings › Encryption." else "") {
                saved.forEach { place ->
                    val count = tasks.count { !it.done && it.geofence == TaskGeofence.Place(place.id) }
                    PlaceRow(place, trailing = {
                        Text(if (count == 0) "" else if (count == 1) "1 task" else "$count tasks", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { deleting = place }) { Icon(Icons.Filled.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }) { renaming = place }
                }
                if (loaded && saved.isEmpty()) FormRow(divider = false) {
                    Text(if (supported) "No saved places. Save one when you pick a task's place." else "This server doesn't support saved places yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ErrorText(error)
        }
    }
    renaming?.let { place ->
        TextInputDialog("Rename Place", place.name, "Name", "Save", onDismiss = { renaming = null }) { name ->
            renaming = null
            if (name.isNotBlank() && name != place.name) scope.launch {
                runCatching { places.update(place.copy(name = name)) }.onSuccess { error = null }.onFailure { error = "Couldn't rename the place. ${it.message}" }
            }
        }
    }
    deleting?.let { place ->
        ConfirmDialog("Delete ${place.name}?", "Tasks at this place keep their location text but stop reminding you there.", "Delete Place", destructive = true,
            onDismiss = { deleting = null }) {
            scope.launch {
                runCatching { places.delete(place) }
                    .onSuccess { graph.tasks.forgetPlace(place.id); error = null }
                    .onFailure { error = "Couldn't delete the place. ${it.message}" }
            }
        }
    }
}
