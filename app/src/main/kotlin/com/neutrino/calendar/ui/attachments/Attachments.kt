package com.neutrino.calendar.ui.attachments

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.neutrino.calendar.core.model.Attachment
import com.neutrino.calendar.core.model.CreateAttachmentRequest
import com.neutrino.calendar.core.service.AttachmentOwner
import com.neutrino.calendar.graph
import com.neutrino.calendar.ui.ActionRow
import com.neutrino.calendar.ui.ErrorText
import com.neutrino.calendar.ui.FormRow
import com.neutrino.calendar.ui.FormSection
import com.neutrino.calendar.ui.PageBar
import com.neutrino.shared.android.ui.ConfirmDialog
import com.neutrino.shared.core.api.FileDto
import com.neutrino.shared.core.api.FolderDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * The attachments of an event or a task: notes, and Drive files that open in a viewer (decrypted on
 * the phone) or in the Neutrino app that owns their format. New files come from Drive, Photos or
 * Files; the last two are encrypted and put in Drive first.
 */
@Composable
fun AttachmentsSection(owner: AttachmentOwner) {
    val context = LocalContext.current
    val graph = context.graph
    val files = graph.attachmentFiles
    val scope = rememberCoroutineScope()
    var attachments by remember(owner.id) { mutableStateOf<List<Attachment>?>(null) }
    var loadError by remember(owner.id) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var newNote by remember { mutableStateOf("") }
    var opening by remember { mutableStateOf<String?>(null) }
    var uploading by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pickingFromDrive by remember { mutableStateOf(false) }
    var notInstalled by remember { mutableStateOf<Pair<String, FileDto>?>(null) }

    fun perform(work: suspend () -> Unit) {
        error = null
        scope.launch { runCatching { work() }.onFailure { error = it.message } }
    }

    suspend fun preview(file: FileDto) {
        opening = file.id
        try {
            val copy = files.localCopy(file)
            view(context, copy, file.mimeType)
        } finally {
            opening = null
        }
    }

    fun upload(uri: Uri) = perform {
        val (name, mime) = describe(context, uri)
        uploading = name
        try {
            val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: throw IllegalStateException("Couldn't read that file.")
            val file = files.upload(bytes, name, mime)
            attachments = attachments.orEmpty() + owner.add(CreateAttachmentRequest.file(file.id, file.name))
        } finally {
            uploading = null
        }
    }

    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let(::upload) }
    val document = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(::upload) }

    LaunchedEffect(owner.id) {
        runCatching { owner.load() }.onSuccess { attachments = it }.onFailure { loadError = it.message }
    }

    FormSection("Attachments") {
        val list = attachments
        when {
            loadError != null -> ErrorText(loadError)
            list == null -> FormRow { CircularProgressIndicator(Modifier.size(20.dp)) }
            else -> {
                list.forEach { a ->
                    val fileId = a.fileId
                    if (fileId != null) {
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            FormRow(onClick = { if (opening == null) perform { preview(files.metadata(fileId)) } }) {
                                Icon(iconFor(a.name), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(10.dp))
                                Text(a.name ?: "Drive file", Modifier.weight(1f))
                                if (opening == fileId) CircularProgressIndicator(Modifier.size(18.dp))
                                IconButton(onClick = { menu = true }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "More") }
                            }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem(text = { Text("Preview") }, leadingIcon = { Icon(Icons.Filled.Visibility, null) }, onClick = {
                                    menu = false
                                    perform { preview(files.metadata(fileId)) }
                                })
                                DropdownMenuItem(text = { Text("Open in Neutrino") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }, onClick = {
                                    menu = false
                                    // Only the file id travels; the app fetches the file itself.
                                    perform {
                                        val file = files.metadata(fileId)
                                        val (kind, app) = appFor(file.mimeType)
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.getneutrino.app/open/$kind/${file.id}"))
                                            .addCategory(Intent.CATEGORY_BROWSABLE)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
                                        try {
                                            context.startActivity(intent)
                                        } catch (_: ActivityNotFoundException) {
                                            notInstalled = app to file
                                        }
                                    }
                                })
                                DropdownMenuItem(text = { Text("Remove", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = {
                                    menu = false
                                    perform { owner.delete(a); attachments = attachments.orEmpty().filter { it.id != a.id } }
                                })
                            }
                        }
                    } else {
                        FormRow {
                            Icon(Icons.AutoMirrored.Filled.StickyNote2, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(10.dp))
                            androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1f)) { Text(a.note.orEmpty()) }
                            IconButton(onClick = { perform { owner.delete(a); attachments = attachments.orEmpty().filter { it.id != a.id } } }) {
                                Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                uploading?.let { name ->
                    FormRow {
                        CircularProgressIndicator(Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Adding $name…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ErrorText(error)
                Box {
                    ActionRow("Add File", Icons.Filled.AttachFile, enabled = uploading == null) { adding = true }
                    DropdownMenu(adding, { adding = false }) {
                        DropdownMenuItem(text = { Text("From Drive") }, leadingIcon = { Icon(Icons.Filled.Cloud, null) }, onClick = { adding = false; pickingFromDrive = true })
                        DropdownMenuItem(text = { Text("Photo or Video") }, leadingIcon = { Icon(Icons.Filled.Image, null) }, onClick = {
                            adding = false
                            if (!files.canUpload) error = "Adding a file encrypts it first, which needs your encryption key. Add it in Settings › Encryption."
                            else photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        })
                        DropdownMenuItem(text = { Text("From Files") }, leadingIcon = { Icon(Icons.Filled.Folder, null) }, onClick = {
                            adding = false
                            if (!files.canUpload) error = "Adding a file encrypts it first, which needs your encryption key. Add it in Settings › Encryption."
                            else document.launch(arrayOf("*/*"))
                        })
                    }
                }
                OutlinedTextField(
                    newNote, { newNote = it }, placeholder = { Text("Add a note") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val note = newNote.trim()
                        if (note.isNotEmpty()) perform {
                            attachments = attachments.orEmpty() + owner.add(CreateAttachmentRequest.note(note))
                            newNote = ""
                        }
                    }),
                )
            }
        }
    }
    if (pickingFromDrive) DriveFilePickerDialog({ pickingFromDrive = false }) { file ->
        perform { attachments = attachments.orEmpty() + owner.add(CreateAttachmentRequest.file(file.id, file.name)) }
    }
    notInstalled?.let { (app, file) ->
        ConfirmDialog("$app Isn't Installed", "Preview the file here instead?", "Preview", onDismiss = { notInstalled = null }) {
            perform { preview(file) }
        }
    }
}

/** Hands a decrypted copy to whatever app views its type. */
private fun view(context: Context, file: File, mimeType: String?) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val type = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
        ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(Intent.createChooser(intent, file.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app on this device can open ${file.name}.", Toast.LENGTH_LONG).show()
    }
}

/** The `/open/<kind>` a file's owning app answers, and that app's name. */
private fun appFor(mime: String?): Pair<String, String> = when {
    mime == null -> "file" to "Neutrino Drive"
    mime.contains("wordprocessingml") || mime == "application/msword" || mime.contains("neutrino.doc") -> "doc" to "Neutrino Docs"
    mime.contains("spreadsheetml") || mime == "application/vnd.ms-excel" || mime.contains("neutrino.sheet") -> "sheet" to "Neutrino Sheets"
    mime.contains("presentationml") || mime.contains("neutrino.slide") -> "slide" to "Neutrino Slides"
    mime.contains("neutrino.note") -> "note" to "Neutrino Notes"
    else -> "file" to "Neutrino Drive"
}

private fun describe(context: Context, uri: Uri): Pair<String, String> {
    val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: if (mime.startsWith("image/")) "Photo ${LocalDate.now()}" else "Attachment"
    return name to mime
}

fun iconFor(name: String?): ImageVector {
    val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
    return when (ext) {
        "jpg", "jpeg", "png", "gif", "heic", "webp" -> Icons.Filled.Image
        "mp4", "mov", "m4v", "webm" -> Icons.Filled.Movie
        "pdf" -> Icons.Filled.PictureAsPdf
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
}

/** Browses the user's Drive and picks a file: the web's `DriveFilePicker`. Folders open in place; a field filters by name. */
@Composable
fun DriveFilePickerDialog(onDismiss: () -> Unit, onPick: (FileDto) -> Unit) {
    val graph = LocalContext.current.graph
    val files = graph.attachmentFiles
    var path by remember { mutableStateOf(listOf<FolderDto?>(null)) }
    var contents by remember { mutableStateOf<com.neutrino.shared.core.api.FolderContentsDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    val folder = path.last()

    LaunchedEffect(folder) {
        contents = null
        error = null
        runCatching { files.folder(folder?.id) }.onSuccess { contents = it }.onFailure { error = it.message }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                PageBar(folder?.name ?: "My Drive", { if (path.size > 1) path = path.dropLast(1) else onDismiss() })
                OutlinedTextField(search, { search = it }, placeholder = { Text("Filter by name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                val c = contents
                fun matches(name: String) = search.isBlank() || name.contains(search.trim(), ignoreCase = true)
                when {
                    error != null -> ErrorText(error)
                    c == null -> Box(Modifier.fillMaxWidth().padding(24.dp)) { CircularProgressIndicator() }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        val folders = c.folders.filter { matches(it.name) }.sortedBy { it.name.lowercase() }
                        val shown = c.files.filter { matches(it.name) }.sortedBy { it.name.lowercase() }
                        item {
                            FormSection {
                                folders.forEach { f ->
                                    FormRow(onClick = { path = path + f }) {
                                        Icon(Icons.Filled.Folder, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(10.dp))
                                        Text(f.name)
                                    }
                                }
                                shown.forEach { f ->
                                    FormRow(onClick = { onPick(f); onDismiss() }) {
                                        Icon(iconFor(f.name), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(10.dp))
                                        Text(f.name, Modifier.weight(1f))
                                        Text(android.text.format.Formatter.formatShortFileSize(graph.context, f.sizeBytes ?: 0),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (folders.isEmpty() && shown.isEmpty()) FormRow(divider = false) {
                                    Text(if (search.isBlank()) "This folder is empty." else "No matches.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
