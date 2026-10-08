package com.olevasseur.glassesapp

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** PCM goes directly to private disk; only one <=62 second chunk is ever loaded. */
class MeetingCapture(
    private val directory: File,
    private val onError: (String) -> Unit,
) {
    private var active: File? = null
    private var raw: RandomAccessFile? = null
    private var rawFile: File? = null
    private var index = 0
    private var startMs = 0L
    private var size = 0
    private var silenceBytes = 0
    private val energy = SpeechEnergyDetector()
    private val sending = Mutex()

    init {
        directory.mkdirs()
        directory.listFiles()?.filter { it.isDirectory }?.forEach { folder ->
            try {
                val meta = metadata(folder)
                folder.listFiles()?.filter { it.extension == "pcm" }?.forEach { pcm ->
                    val idx = pcm.nameWithoutExtension.substringBefore('_').toInt()
                    val wav = File(folder, pcm.nameWithoutExtension + ".wav")
                    // A crash after WAV rename must not enqueue the same audio again.
                    if (!wav.exists() && pcm.length() > (if (idx == 0) 0 else OVERLAP)) encode(pcm, wav)
                    if (wav.exists()) meta.put("count", maxOf(meta.optInt("count"), idx + 1))
                    pcm.delete()
                }
                meta.put("ending", true)
                save(folder, meta)
            } catch (_: Exception) { onError("Saved meeting audio could not be recovered; files retained.") }
        }
    }

    @Synchronized fun begin(title: String, doc: String) {
        check(active == null)
        val folder = File(directory, UUID.randomUUID().toString()).apply { mkdirs() }
        save(folder, JSONObject().put("title", title).apply { if (doc.isNotBlank()) put("doc_name", doc) }
            .put("chunked", true).put("count", 0).put("ending", false))
        active = folder; index = 0; startMs = 0; size = 0; silenceBytes = 0
        openRaw()
    }

    private fun openRaw() {
        rawFile = File(active!!, "${index}_${startMs}.pcm")
        raw = RandomAccessFile(rawFile!!, "rw")
    }

    @Synchronized fun audio(pcm: ByteArray) {
        if (active == null) return
        require(pcm.size % 2 == 0)
        // Split even unusually large callbacks; the normal recorder sends 20 ms frames.
        var offset = 0
        while (offset < pcm.size) {
            val length = minOf(pcm.size - offset, MAX - size)
            raw!!.write(pcm, offset, length)
            size += length; offset += length
            val part = pcm.copyOfRange(offset - length, offset)
            silenceBytes = if (energy.accept(part)) 0 else silenceBytes + length
            if (size >= MAX || (size >= TARGET && silenceBytes >= 9_600)) roll()
        }
    }

    private fun roll() {
        val tail = ByteArray(OVERLAP)
        raw!!.seek((size - OVERLAP).toLong()); raw!!.readFully(tail)
        seal()
        startMs += (size - OVERLAP) / 32
        index++; size = OVERLAP; silenceBytes = 0
        openRaw(); raw!!.write(tail)
    }

    private fun seal() {
        raw?.fd?.sync(); raw?.close(); raw = null
        val folder = active!!
        val meta = metadata(folder)
        // Persist count before exposing the upload; recovery repairs an interrupted seal.
        meta.put("count", index + 1); save(folder, meta)
        encode(rawFile!!, File(folder, "${index}_${startMs}.wav"))
        check(rawFile!!.delete())
    }

    @Synchronized fun stop() {
        val folder = active ?: return
        try {
            if (size > (if (index == 0) 0 else OVERLAP)) seal()
            else { raw?.close(); raw = null; rawFile?.delete() }
            save(folder, metadata(folder).put("ending", true))
        } catch (_: Exception) {
            onError("Could not seal meeting audio; free device storage and reopen to recover retained files.")
        } finally {
            runCatching { raw?.close() }; raw = null
            active = null; rawFile = null
        }
    }

    suspend fun flush(backend: BackendClient, onReady: (JSONObject) -> Unit, onApproval: suspend (JSONObject) -> Unit) =
        flush({ id, meta -> backend.startMeeting(id, meta); Unit }, backend::meetingChunk, backend::processMeeting, backend::endChunkedMeeting, onReady, onApproval)

    /** Upload receipts precede deletion. A lost reply safely retries the identical index/bytes. */
    internal suspend fun flush(
        register: suspend (String, JSONObject) -> Unit,
        upload: suspend (String, Int, Long, File) -> Unit,
        process: suspend (String) -> Unit,
        end: suspend (String, Int) -> JSONObject,
        onReady: (JSONObject) -> Unit = {},
        onApproval: suspend (JSONObject) -> Unit = {},
    ) = sending.withLock {
        // Disk/network work stays off Main; capture and metadata changes share a short lock.
        withContext(Dispatchers.IO) {
            directory.listFiles()?.filter { it.isDirectory }?.forEach { folder ->
                try {
                    val meta = synchronized(this@MeetingCapture) { metadata(folder) }
                    val request = JSONObject().put("title", meta.getString("title")).put("chunked", true)
                    if (meta.has("doc_name")) request.put("doc_name", meta.getString("doc_name"))
                    register(folder.name, request)
                    val files = synchronized(this@MeetingCapture) {
                        folder.listFiles()?.filter { it.extension == "wav" }?.sortedBy { it.name.substringBefore('_').toInt() }.orEmpty()
                    }
                    // Bounded batch lets other pending meetings make progress during catch-up.
                    for (file in files.take(8)) {
                        val parts = file.nameWithoutExtension.split('_')
                        upload(folder.name, parts[0].toInt(), parts[1].toLong(), file)
                        check(file.delete())
                    }
                    val current = synchronized(this@MeetingCapture) { metadata(folder) }
                    if (current.optBoolean("ending") && folder.listFiles()?.none { it.extension == "wav" || it.extension == "pcm" } == true) {
                        val result = end(folder.name, current.getInt("count"))
                        when (result.optString("status")) {
                            "needs_confirmation" -> withContext(Dispatchers.Main) { onApproval(result) }
                            "ok", "rejected" -> {
                                check(folder.deleteRecursively())
                                withContext(Dispatchers.Main) { onReady(result) }
                            }
                        }
                    } else process(folder.name)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { onError("Meeting audio saved on phone; upload/minutes retry pending.") }
            }
        }
    }

    private fun metadata(folder: File) = JSONObject(File(folder, "meeting.json").readText())
    private fun save(folder: File, value: JSONObject) {
        val tmp = File(folder, "meeting.tmp")
        FileOutputStream(tmp).use { it.write(value.toString().toByteArray()); it.fd.sync() }
        Files.move(tmp.toPath(), File(folder, "meeting.json").toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val TARGET = 60 * 32_000
        const val MAX = 62 * 32_000
        const val OVERLAP = 2 * 32_000
        private fun encode(pcm: File, wav: File) {
            val size = pcm.length().toInt()
            require(size in 1..MAX && size % 2 == 0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(size + 36).put("WAVEfmt ".toByteArray()).putInt(16)
                .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
                .put("data".toByteArray()).putInt(size).array()
            val tmp = File(wav.parentFile, wav.name + ".tmp")
            FileOutputStream(tmp).use { output ->
                output.write(header); pcm.inputStream().use { it.copyTo(output) }; output.fd.sync()
            }
            Files.move(tmp.toPath(), wav.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
