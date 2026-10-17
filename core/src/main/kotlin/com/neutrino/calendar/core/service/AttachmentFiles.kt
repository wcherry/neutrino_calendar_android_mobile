package com.neutrino.calendar.core.service

import com.neutrino.shared.core.api.ApiException
import com.neutrino.shared.core.api.FileDto
import com.neutrino.shared.core.api.FolderContentsDto
import com.neutrino.shared.core.api.NeutrinoApi
import com.neutrino.shared.core.crypto.FileCrypto
import com.neutrino.shared.core.keys.Keyring
import com.neutrino.shared.core.keys.openDek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

sealed class AttachmentException(message: String) : Exception(message) {
    /** This device holds no encryption key for the account. */
    class NoKey : AttachmentException("This file is encrypted, and this phone doesn't have your encryption key yet. Add it in Settings › Encryption.")
    /** It holds one, but not the version this file's key was sealed to. */
    class MissingKeyVersion(version: Int) : AttachmentException("This file needs encryption key version $version, which this phone doesn't have. Restore your key again in Settings › Encryption.")
    class TooLarge : AttachmentException("This file is too large to open here. Open it in Neutrino Drive.")
    class UploadIncomplete : AttachmentException("The file was uploaded but its key couldn't be saved, so it was removed. Try again.")
}

/**
 * Gets attached Drive files onto the phone to view, and new ones into Drive.
 *
 * Drive files are end-to-end encrypted: the server holds ciphertext and a key sealed to the
 * account's identity key, which only the user's devices have. Opening one means fetching both and
 * decrypting here; adding one means encrypting here first, exactly as the web does. A file stored in
 * the clear, from before encryption, opens as it is. An upload is never sent in the clear.
 */
class AttachmentFiles(
    private val api: NeutrinoApi,
    private val keyring: () -> Keyring?,
    /** Decrypted copies, cleared at sign-out. */
    private val cacheDir: File,
) {
    /** A folder's contents, for the picker. The root's id is the user's id. */
    suspend fun folder(id: String?): FolderContentsDto = api.folder(id, orderByName = true)

    suspend fun metadata(fileId: String): FileDto = api.fileMetadata(fileId)

    /**
     * A plaintext copy of the file on this device, kept under its content version, so opening it again
     * doesn't download it again, and an edited file does. Decryption is in memory, as on the web.
     */
    suspend fun localCopy(file: FileDto): File = withContext(Dispatchers.IO) {
        if ((file.sizeBytes ?: 0) > MAX_OPEN_BYTES) throw AttachmentException.TooLarge()
        val folder = File(cacheDir, "${safe(file.id)}/v${file.contentVersion ?: 0}")
        folder.listFiles()?.firstOrNull()?.let { return@withContext it }

        val stored = api.openBlob(file.id).use { it.body?.bytes() ?: ByteArray(0) }
        var name = file.name
        var plain = stored
        val sealed = api.fileKey(file.id)
        if (sealed != null) {
            val ring = keyring() ?: throw AttachmentException.NoKey()
            val version = sealed.keyVersion ?: 1
            val (dek, _) = ring.openDek(sealed.encryptedFileKey, version) ?: throw AttachmentException.MissingKeyVersion(version)
            try {
                // The metadata names the framing; a file with none is a single push.
                val meta = metadataOf(file, dek)
                plain = FileCrypto.decryptFile(stored, dek, meta?.chunkSize)
                meta?.name?.takeIf { it.isNotEmpty() }?.let { name = it }
            } finally {
                dek.fill(0)
            }
        }
        folder.mkdirs()
        val out = File(folder, safeFileName(name, file.mimeType))
        val tmp = File(folder, out.name + ".part")
        tmp.writeBytes(plain)
        tmp.renameTo(out)
        out
    }

    private fun metadataOf(file: FileDto, dek: ByteArray): FileCrypto.Metadata? {
        val encrypted = file.encryptedMetadata?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { FileCrypto.decryptMetadata(encrypted, dek) }.getOrNull()
    }

    /** Forgets every decrypted copy, for sign-out. */
    fun clearCache() {
        cacheDir.deleteRecursively()
    }

    /** Whether this device can add files: it needs the account's key to encrypt them. */
    val canUpload: Boolean get() = keyring()?.active != null

    /**
     * Encrypts `data` and stores it in Drive's "Attachments" folder, as the web does. Without its key
     * the file could never be opened, so if the key can't be stored after a few tries, the file is
     * taken back out rather than left unreadable.
     */
    suspend fun upload(data: ByteArray, name: String, mimeType: String): FileDto {
        val active = keyring()?.active ?: throw AttachmentException.NoKey()
        val folderId = attachmentsFolderId()
        val fileName = safeFileName(name, mimeType)
        val dek = FileCrypto.generateDek()
        val (content, metadata, key) = withContext(Dispatchers.Default) {
            Triple(
                FileCrypto.encryptFile(data, dek),
                FileCrypto.encryptMetadata(fileName, mimeType, dek),
                FileCrypto.sealDek(dek, active.publicKey),
            )
        }
        dek.fill(0)
        // Every field before the file part: the server stops reading at the file.
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("encrypted_metadata", metadata)
            .apply { folderId?.let { addFormDataPart("folder_id", it) } }
            .addFormDataPart("file", fileName, content.toRequestBody(mimeType.toMediaType()))
            .build()
        val file = api.upload(body)
        for (attempt in 1..3) {
            try {
                api.setFileKey(file.id, key, active.version)
                return file
            } catch (e: Exception) {
                if (attempt < 3) delay(attempt * 500L)
            }
        }
        runCatching { api.trashFile(file.id) }
        throw AttachmentException.UploadIncomplete()
    }

    /** The id of the root's "Attachments" folder, made if it isn't there. */
    private suspend fun attachmentsFolderId(): String? {
        val root = api.auth.userId ?: throw ApiException.NotSignedIn()
        val contents = api.folder(root)
        contents.folders.firstOrNull { it.name.equals(FOLDER_NAME, ignoreCase = true) }?.let { return it.id }
        return api.createFolder(FOLDER_NAME, null).id
    }

    companion object {
        /** The Drive folder new attachments go in, the one the web uses. */
        const val FOLDER_NAME = "Attachments"
        /** Decryption happens in memory, as on the web; past this a phone might not cope. */
        const val MAX_OPEN_BYTES = 250L * 1024 * 1024

        private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }

        /** A name safe as a file name and a multipart `filename`, with an extension that matches its type. */
        fun safeFileName(name: String, mimeType: String?): String {
            var clean = name.split('/', '\\', '"', '\r', '\n', ':').joinToString("-").trim()
            if (clean.isEmpty() || clean.startsWith(".")) clean = "Attachment$clean"
            if (!clean.substringAfterLast('/').contains('.')) extensionFor(mimeType)?.let { clean += ".$it" }
            return clean
        }

        private fun extensionFor(mime: String?): String? = when (mime?.lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/heic" -> "heic"
            "image/webp" -> "webp"
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "application/pdf" -> "pdf"
            "text/plain" -> "txt"
            "text/calendar" -> "ics"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "pptx"
            else -> null
        }
    }
}
