package com.olevasseur.glassesapp

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Called on Main. Private disk outbox survives a lost response or process death. */
class TranscriptMemory(
    private val backend: BackendClient,
    private val directory: File,
    private val onError: (String) -> Unit,
) {
    var onMeetingReady: (JSONObject) -> Unit = {}
    private val sessions = linkedMapOf<String, JSONObject>()
    private val sending = Mutex()
    private var activeId: String? = null

    init {
        directory.mkdirs()
        directory.listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach { file ->
            try {
                val session = JSONObject(file.readText())
                require(session.getString("session_id") == file.nameWithoutExtension)
                // A previous process cannot still be recording this conversation.
                session.put("ending", true)
                sessions[file.nameWithoutExtension] = session
            } catch (_: Exception) {
                onError("A saved memory upload cannot be read; its file was kept.")
            }
        }
    }

    fun begin(meeting: JSONObject? = null): String {
        check(activeId == null)
        val id = UUID.randomUUID().toString()
        val session = JSONObject().put("session_id", id).put("next_seq", 0)
            .put("ending", false).put("turns", JSONArray())
        if (meeting != null) session.put("meeting", meeting)
        sessions[id] = session
        activeId = id
        persist(session)
        return id
    }

    fun append(speaker: String, text: String) {
        val session = sessions[activeId] ?: return
        require(speaker == "user" || speaker == "model")
        text.chunked(12_000).forEach { chunk ->
            val seq = session.getInt("next_seq")
            session.getJSONArray("turns").put(JSONObject().put("seq", seq).put("speaker", speaker)
                .put("text", chunk).put("timestamp", Instant.now().toString()))
            session.put("next_seq", seq + 1)
        }
        persist(session)
    }

    fun stop() {
        val session = sessions[activeId] ?: return
        activeId = null
        session.put("ending", true)
        persist(session)
    }

    /** Only idempotent transcript/end requests are retried; never tool writes. */
    suspend fun flush() = sending.withLock {
        for (session in sessions.values.toList()) {
            try {
                val id = session.getString("session_id")
                if (session.has("meeting") && !session.optBoolean("registered")) {
                    backend.startMeeting(id, session.getJSONObject("meeting"))
                    session.put("registered", true)
                    persist(session)
                }
                var turns = session.getJSONArray("turns")
                while (turns.length() > 0) {
                    val count = minOf(50, turns.length())
                    val batch = JSONArray(List(count) { turns.getJSONObject(it) })
                    backend.transcripts(id, batch)
                    // append() may have run while the HTTP call suspended.
                    turns = session.getJSONArray("turns")
                    val remaining = JSONArray(List(turns.length() - count) { turns.getJSONObject(it + count) })
                    session.put("turns", remaining)
                    persist(session)
                    turns = remaining
                }
                if (session.getBoolean("ending")) {
                    val result = if (session.has("meeting")) backend.endMeeting(id) else backend.endSession(id)
                    if (result.optString("status") == "processing") continue
                    check(File(directory, "$id.json").delete()) { "Cannot remove delivered memory upload" }
                    sessions.remove(id)
                    if (session.has("meeting")) onMeetingReady(result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onError(if (session.has("meeting"))
                    "Meeting saved locally; upload/minutes pending. Check backend, Gemini quota, Google consent and Doc title. Retries while open or next launch."
                    else "Memory upload pending; it will retry while the app is open or next launch.")
            }
        }
    }

    private fun persist(session: JSONObject) {
        val id = session.getString("session_id")
        val temporary = File(directory, "$id.tmp")
        temporary.writeText(session.toString())
        Files.move(temporary.toPath(), File(directory, "$id.json").toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
