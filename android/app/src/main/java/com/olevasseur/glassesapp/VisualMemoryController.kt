package com.olevasseur.glassesapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.math.abs

/** Pure policy: no frames on disk, no queue, no automatic restart after pause/disconnect. */
class VisualMemoryController(
    private val scope: CoroutineScope,
    private val capture: suspend () -> ByteArray,
    private val hash: suspend (ByteArray) -> Long,
    private val upload: suspend (ByteArray, String?, Boolean) -> JSONObject,
    private val delete: suspend (Int) -> JSONObject,
    private val now: () -> Long,
    private val onActive: (Boolean) -> Unit,
    private val chime: () -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        val TOOL_NAMES = setOf("set_visual_memory", "remember_this", "delete_visual_memory")
        const val INTERVAL_MS = 20_000L
    }
    private var loop: Job? = null
    private var timer: Job? = null
    private val pending = mutableSetOf<Job>()
    private val lock = Mutex()
    private var previousHash: Long? = null
    private var lastSample: Long? = null
    private var lastMoved = Long.MIN_VALUE
    private var lastSaved: Long? = null
    private var generation = 0
    var active = false
        private set

    fun motion(sample: HeadMotionSample) {
        lastSample = now()
        if (maxOf(abs(sample.pitchRate), abs(sample.yawRate), abs(sample.rollRate)) > 0.08) lastMoved = now()
    }

    fun setEnabled(enabled: Boolean, minutes: Int = 30) {
        require(minutes in 1..120) { "Visual memory duration must be 1–120 minutes" }
        if (!enabled) { stop(); return }
        if (active) return
        active = true
        previousHash = null; lastSaved = null
        onActive(true)
        try { chime() } catch (_: Exception) { stop(); onError("Could not play capture chime; visual memory stayed off"); return }
        val current = generation
        timer = scope.launch { delay(minutes * 60_000L); if (current == generation) stop() }
        loop = scope.launch {
            try {
                while (true) {
                    val recentMotion = lastSample?.let { now() - it <= 2_000 } == true
                    val still = recentMotion && lastSaved?.let { lastMoved <= it } == true
                    if (!still) snapshot(null, false)
                    delay(INTERVAL_MS)
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { onError("Visual memory capture/upload failed; mode paused") }
            finally { if (current == generation) stop() }
        }
    }

    private suspend fun snapshot(label: String?, pinned: Boolean): JSONObject = coroutineScope {
        val job = currentCoroutineContext().job
        pending += job
        try {
            lock.withLock {
                withTimeout(45_000) {
                    val capturedAt = now()
                    val bytes = capture()
                    val fingerprint = hash(bytes)
                    if (!pinned && previousHash?.let { java.lang.Long.bitCount(it xor fingerprint) <= 5 } == true) {
                        return@withTimeout JSONObject().put("status", "skipped_duplicate")
                    }
                    val result = upload(bytes, label, pinned)
                    if (result.optString("status") == "ok") {
                        previousHash = fingerprint
                        lastSaved = capturedAt
                    }
                    result
                }
            }
        } finally { pending -= job }
    }

    suspend fun tool(call: FunctionCall): ToolResult {
        return try {
            val result = when (call.name) {
                "set_visual_memory" -> {
                    val on = call.args.opt("on") as? Boolean ?: error("on must be boolean")
                    val minutes = if (on) integer(call.args, "minutes", 30, 120) else 30
                    setEnabled(on, minutes)
                    JSONObject().put("status", if (active == on) "ok" else "error").put("on", active).put("minutes", minutes)
                }
                "remember_this" -> {
                    val label = if (call.args.has("label")) (call.args.opt("label") as? String)
                        ?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 } ?: error("Invalid label") else null
                    // A one-shot is visible and audible even when periodic mode is off.
                    if (!active) { onActive(true); chime() }
                    try { snapshot(label, true) } finally { if (!active) onActive(pending.isNotEmpty()) }
                }
                "delete_visual_memory" -> {
                    val minutes = integer(call.args, "minutes", 60, 10080)
                    stop()
                    delete(minutes)
                }
                else -> toolError("Unsupported visual memory tool")
            }
            ToolResult(result)
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            ToolResult(toolError("Visual memory capture stopped"))
        } catch (_: Exception) {
            if (!active) onActive(pending.isNotEmpty())
            ToolResult(toolError("Visual memory operation failed; check camera, backend and arguments"))
        }
    }

    fun stop() {
        generation++
        loop?.cancel(); loop = null
        timer?.cancel(); timer = null
        pending.toList().forEach { it.cancel() }
        active = false
        previousHash = null; lastSaved = null
        onActive(false)
    }

    private fun integer(args: JSONObject, name: String, default: Int, max: Int): Int {
        if (!args.has(name)) return default
        val value = args.opt(name)
        require(value is Int || value is Long) { "Expected integer" }
        return (value as Number).toLong().also { require(it in 1..max.toLong()) }.toInt()
    }
}

/** 64-bit difference hash of a 9×8 luminance grid. */
fun visualDifferenceHash(luminance: IntArray): Long {
    require(luminance.size == 72)
    var hash = 0L
    for (y in 0 until 8) for (x in 0 until 8) {
        if (luminance[y * 9 + x] > luminance[y * 9 + x + 1]) hash = hash or (1L shl (y * 8 + x))
    }
    return hash
}
