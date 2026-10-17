package com.olevasseur.glassesapp

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.util.UUID

data class PendingPhoto(val id: String, val capturedAt: String, val label: String?, val jpeg: ByteArray)
class UnreadablePhotoException : Exception("Unreadable photo moved to failed outbox")

/** Private bounded JPEG delivery; page captures opt out of the photo failed archive. */
class PhotoOutbox(
    private val directory: File,
    private val retainFailures: Boolean = true,
    private val fullMessage: String = "Photo outbox is full; send pending photos to your Mac before taking another",
) {
    companion object {
        const val MAX_PHOTO_BYTES = 12_000_000
        const val MAX_COUNT = 8
        const val MAX_TOTAL_BYTES = 64_000_000L
    }

    private fun files() = directory.listFiles()?.filter { it.name.endsWith(".photo") }?.sortedBy { it.lastModified() }.orEmpty()

    fun checkRoom(bytes: Int = MAX_PHOTO_BYTES) {
        val pending = files()
        check(pending.size < MAX_COUNT && pending.sumOf { it.length() } + bytes + 1024 <= MAX_TOTAL_BYTES) {
            fullMessage
        }
    }

    fun add(jpeg: ByteArray, label: String?): PendingPhoto {
        require(jpeg.isNotEmpty() && jpeg.size <= MAX_PHOTO_BYTES) { "Photo exceeds the 12 MB limit; nothing saved" }
        checkRoom(jpeg.size)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot open private photo outbox" }
        val photo = PendingPhoto(UUID.randomUUID().toString(), OffsetDateTime.now().toString(), label, jpeg)
        val temporary = File(directory, "pending.tmp")
        try {
            FileOutputStream(temporary).use { file ->
                val output = DataOutputStream(file)
                output.writeUTF(photo.id)
                output.writeUTF(photo.capturedAt)
                output.writeUTF(photo.label.orEmpty())
                output.writeInt(jpeg.size)
                output.write(jpeg)
                output.flush()
                file.fd.sync()
            }
            check(temporary.renameTo(File(directory, "${photo.id}.photo"))) { "Cannot persist photo" }
        } finally { temporary.delete() }
        return photo
    }

    fun first(): PendingPhoto? = files().firstOrNull()?.let { file ->
        try {
            DataInputStream(file.inputStream()).use { input ->
                val id = input.readUTF()
                check(UUID.fromString(id).toString() == id && file.name == "$id.photo")
                val capturedAt = input.readUTF()
                OffsetDateTime.parse(capturedAt)
                val label = input.readUTF().ifEmpty { null }
                val size = input.readInt()
                check(size in 1..MAX_PHOTO_BYTES)
                val jpeg = ByteArray(size)
                input.readFully(jpeg)
                PendingPhoto(id, capturedAt, label, jpeg)
            }
        } catch (_: Exception) {
            quarantine(file, "unreadable")
            throw UnreadablePhotoException()
        }
    }

    fun failed(photo: PendingPhoto, code: Int) {
        require(UUID.fromString(photo.id).toString() == photo.id)
        quarantine(File(directory, "${photo.id}.photo"), "http_$code")
    }

    private fun quarantine(file: File, reason: String) {
        if (!retainFailures) {
            check(file.delete()) { "Cannot remove rejected capture" }
            return
        }
        val failed = File(directory, "failed")
        check(failed.isDirectory || failed.mkdirs()) { "Cannot open failed photo outbox" }
        val target = File(failed, file.name)
        check(file.renameTo(target)) { "Cannot quarantine failed photo" }
        target.setLastModified(System.currentTimeMillis())
        DiagnosticLog.add("photo", "quarantined reason=$reason", "WARN")
        val entries = failed.listFiles().orEmpty().sortedBy { it.lastModified() }.toMutableList()
        var bytes = entries.sumOf { it.length() }
        while (entries.size > MAX_COUNT || bytes > MAX_TOTAL_BYTES) {
            val oldest = entries.removeAt(0)
            bytes -= oldest.length()
            check(oldest.deleteRecursively()) { "Cannot prune failed photo" }
        }
    }

    fun delivered(photo: PendingPhoto) {
        require(UUID.fromString(photo.id).toString() == photo.id)
        check(File(directory, "${photo.id}.photo").delete()) { "Could not remove delivered photo from outbox" }
    }
}
