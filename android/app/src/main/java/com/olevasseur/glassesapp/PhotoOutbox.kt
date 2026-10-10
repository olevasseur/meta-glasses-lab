package com.olevasseur.glassesapp

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.OffsetDateTime
import java.util.UUID

data class PendingPhoto(val id: String, val capturedAt: String, val label: String?, val jpeg: ByteArray)

/** Owned by PhotoToMacController; private no-backup storage, never evict an unsent photo. */
class PhotoOutbox(private val directory: File) {
    companion object {
        const val MAX_PHOTO_BYTES = 12_000_000
        const val MAX_COUNT = 8
        const val MAX_TOTAL_BYTES = 64_000_000L
    }

    private fun files() = directory.listFiles()?.filter { it.name.endsWith(".photo") }?.sortedBy { it.lastModified() }.orEmpty()

    fun checkRoom(bytes: Int = MAX_PHOTO_BYTES) {
        val pending = files()
        check(pending.size < MAX_COUNT && pending.sumOf { it.length() } + bytes + 1024 <= MAX_TOTAL_BYTES) {
            "Photo outbox is full; send pending photos to your Mac before taking another"
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
    }

    fun delivered(photo: PendingPhoto) {
        require(UUID.fromString(photo.id).toString() == photo.id)
        check(File(directory, "${photo.id}.photo").delete()) { "Could not remove delivered photo from outbox" }
    }
}
