package com.olevasseur.glassesapp

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject

data class DiagnosticEntry(val sequence: Long, val id: String, val timestamp: String,
    val feature: String, val level: String, val event: String) {
    fun json() = JSONObject().put("id", id).put("timestamp", timestamp)
        .put("feature", feature).put("level", level).put("event", event)
}

/** Only operational events go here: never pass transcripts, tool arguments or credentials. */
class DiagnosticRing(private val capacity: Int = 500, private val secrets: () -> List<String> = { emptyList() }) {
    private val entries = ArrayDeque<DiagnosticEntry>()
    private var sequence = 0L
    var dropped = 0L; private set
    init { require(capacity > 0) }

    @Synchronized fun add(feature: String, event: String, level: String = "INFO") {
        require(feature.matches(Regex("[a-z][a-z0-9_-]{0,39}")))
        require(level in setOf("DEBUG", "INFO", "WARN", "ERROR"))
        var safe = event
        secrets().filter { it.isNotEmpty() }.forEach { safe = safe.replace(it, "[redacted]") }
        safe = safe.replace(Regex("(?i)(auth_tokens/\\S+|AIza[\\w-]+|(?:bearer|token)\\s+\\S+)"), "[redacted]")
            .map { if (it.isISOControl()) ' ' else it }.joinToString("").take(512)
        if (entries.size == capacity) { entries.removeFirst(); dropped++ }
        entries.addLast(DiagnosticEntry(++sequence, UUID.randomUUID().toString(), Instant.now().toString(), feature, level, safe))
    }
    @Synchronized fun after(cursor: Long, limit: Int = 100): List<DiagnosticEntry> {
        var bytes = 16 // JSON envelope; leave room below the server's 128 KiB limit.
        return entries.filter { it.sequence > cursor }.take(limit).takeWhile {
            bytes += it.json().toString().toByteArray(Charsets.UTF_8).size + 1
            bytes <= 120_000
        }
    }
    @Synchronized fun export() = entries.joinToString("\n") { it.json().toString() }
}

object DiagnosticLog {
    val ring = DiagnosticRing(secrets = { listOf(BuildConfig.APP_SHARED_SECRET) })
    fun add(feature: String, event: String, level: String = "INFO") = ring.add(feature, event, level)
    @Volatile var uploadStatus = "Not checked"
    private var cursor = 0L

    suspend fun uploadLoop(backend: BackendClient) {
        while (currentCoroutineContext().isActive) {
            val batch = ring.after(cursor)
            try {
                backend.diagnosticHealth()
                if (batch.isNotEmpty()) {
                    val result = backend.uploadLogs(JSONArray().apply { batch.forEach { put(it.json()) } })
                    check(result.optInt("accepted", -1) == batch.size) { "Incomplete log acknowledgement" }
                    cursor = batch.last().sequence
                }
                uploadStatus = "Reachable · last checked ${Instant.now()} · uploaded through $cursor"
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { uploadStatus = "Unreachable / unauthorized · retry in 5 s" }
            delay(5_000)
        }
    }
}

/** Local energy estimate, measured to first received Gemini audio, excluding speaker buffering. */
class ResponseLatency(private val clock: () -> Long) {
    private var energy = SpeechEnergyDetector()
    private var lastSpeech: Long? = null
    private var responseStarted = false
    private var activityEnd: Long? = null
    var endWaitMs: Long? = null; private set
    @Synchronized fun activityEnd() { activityEnd = clock() }
    @Synchronized fun input(pcm: ByteArray) { if (!responseStarted && energy.accept(pcm)) lastSpeech = clock() }
    @Synchronized fun firstAudio(): Long? {
        if (responseStarted) return null
        responseStarted = true
        val start = lastSpeech ?: return null
        lastSpeech = null
        endWaitMs = activityEnd?.let { (it - start).coerceAtLeast(0) }
        return (clock() - start).coerceAtLeast(0)
    }
    @Synchronized fun reset() { lastSpeech = null; responseStarted = false; activityEnd = null; endWaitMs = null; energy = SpeechEnergyDetector() }
}

object DiagnosticMetrics {
    val latency = ResponseLatency { System.nanoTime() / 1_000_000 }
    @Volatile var latencyMs: Long? = null
    @Volatile var handleUpdatedMs: Long? = null
    @Volatile var gyro = "No samples"
    @Volatile var gyroUpdatedMs = 0L
    fun response(vadSilenceMs: Int? = null) { latency.firstAudio()?.let {
        latencyMs = it
        val wait = latency.endWaitMs ?: vadSilenceMs?.toLong()
        DiagnosticLog.add("latency", "speech_end_to_first_audio_ms=$it end_wait_ms=$wait end_wait_estimated=${latency.endWaitMs == null} after_end_ms=${wait?.let { w -> it - w }} speech_end_estimated=true")
    } }
}

internal fun diagnosticFeature(tool: String): String = when {
    tool in setOf("remember", "recall", "forget") -> "memory"
    tool == "append_to_doc" -> "notes"
    tool.contains("journal") || tool == "morning_brief" -> "journal"
    tool.contains("translation") -> "translator"
    tool.contains("meeting") -> "meeting"
    tool.contains("workout") -> "workout"
    tool in setOf("read_text", "submit_reading", "reading_control") -> "read-to-me"
    tool in setOf("look", "set_watch_mode") -> "vision"
    tool == "await_gesture_confirmation" -> "gestures"
    tool.contains("visual") || tool in setOf("remember_this", "find_object", "when_did_i_see") -> "visual-memory"
    tool.contains("project") || tool.contains("step") || tool.contains("timer") || tool in setOf("scale_recipe", "check_progress", "set_nod_navigation") -> "coach"
    tool in setOf("codex_task", "deep_research", "job_status", "cancel_job") -> "jobs"
    else -> "tools"
}

internal fun logDiagnosticTool(name: String, finished: Boolean, failed: Boolean = false) {
    val safeName = name.filter { it.isLetterOrDigit() || it == '_' }.take(64)
    DiagnosticLog.add(diagnosticFeature(name), "tool=$safeName ${if (finished) "finished" else "started"} failed=$failed", if (failed) "WARN" else "INFO")
}
