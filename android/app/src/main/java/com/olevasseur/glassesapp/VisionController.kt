package com.olevasseur.glassesapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Camera policy independent of Android: one capture at a time, bounded lifetime, no stored frames. */
class VisionController(
    private val scope: CoroutineScope,
    private val capture: suspend () -> ByteArray,
    private val send: (ByteArray) -> Boolean,
    private val stopCamera: () -> Unit,
    private val onWatch: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        const val WATCH_MS = 120_000L
        const val IDLE_MS = 5_000L
        const val FRAME_MS = 1_000L
        val TOOL_NAMES = setOf("look", "set_watch_mode")
    }

    private val captureLock = Mutex()
    private var frame: Job? = null
    private var idle: Job? = null
    private var cooldown: Job? = null
    private var watch: Job? = null
    private var generation = 0

    suspend fun tool(call: FunctionCall): ToolResult = when (call.name) {
        "look" -> {
            if ((call.args.opt("question") as? String).isNullOrBlank()) {
                ToolResult(toolError("look requires a nonblank question"))
            } else try {
                val jpeg = sendFrame()
                ToolResult(JSONObject().put("status", "frame_sent"), jpeg)
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                ToolResult(toolError("Vision stopped before a frame could be sent"))
            }
            catch (e: Exception) { ToolResult(toolError(e.message ?: "Camera unavailable")) }
        }
        "set_watch_mode" -> {
            val enabled = call.args.opt("on") as? Boolean
            if (enabled == null) ToolResult(toolError("set_watch_mode requires a boolean on"))
            else {
                setWatchMode(enabled)
                ToolResult(JSONObject().put("status", "ok").put("on", enabled).put("auto_off_seconds", 120))
            }
        }
        else -> ToolResult(toolError("Client tool is not supported by this app version"))
    }

    private suspend fun sendFrame(): ByteArray {
        val current = generation
        return captureLock.withLock {
            cooldown?.join() // The 1 fps limit also applies across look calls and watch frames.
            check(current == generation) { "Vision stopped" }
            idle?.cancel()
            coroutineScope {
                frame = currentCoroutineContext().job
                try {
                    val jpeg = withTimeoutOrNull(15_000) { capture() } ?: error("Camera capture timed out")
                    check(current == generation && send(jpeg)) { "Frame not sent; Gemini disconnected or busy" }
                    cooldown = scope.launch { delay(FRAME_MS) }
                    jpeg
                } finally {
                    frame = null
                    if (current == generation) idle = scope.launch { delay(IDLE_MS); stopCamera() }
                }
            }
        }
    }

    fun setWatchMode(enabled: Boolean) {
        if (!enabled) { stop(); return }
        if (watch?.isActive == true) return // Repeated requests must not extend the two-minute cap.
        val current = generation
        onWatch(true)
        watch = scope.launch {
            try {
                withTimeoutOrNull(WATCH_MS) {
                    while (true) {
                        sendFrame()
                        delay(FRAME_MS) // At most 1 fps; slow captures never accumulate a backlog.
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onError(e.message ?: "Watch camera unavailable") }
            finally {
                if (current == generation) stop()
            }
        }
    }

    fun stop() {
        generation++
        watch?.cancel(); watch = null
        frame?.cancel(); frame = null
        idle?.cancel(); idle = null
        // Keep the short cooldown so a rapid off/on toggle cannot exceed Gemini's frame limit.
        stopCamera()
        onWatch(false)
    }
}

fun toolError(message: String): JSONObject = JSONObject().put("status", "error").put("message", message)

// The JPEG is also attached to the function response: realtime video alone is not reliably
// included in the model's tool-result turn. Keep it only until that response is queued.
data class ToolResult(val response: JSONObject, val jpeg: ByteArray? = null)

/** Known local tools can never accidentally hit /tools, including with an older backend token. */
suspend fun routeTool(
    call: FunctionCall,
    clientTools: Set<String>,
    local: suspend (FunctionCall) -> ToolResult,
    backend: suspend (FunctionCall) -> JSONObject,
): ToolResult = when {
    call.name == GestureConfirmation.TOOL || call.name in VisionController.TOOL_NAMES ||
        call.name in WorkoutCoach.TOOL_NAMES -> local(call)
    call.name in clientTools -> ToolResult(toolError("Client tool is not supported by this app version"))
    else -> ToolResult(backend(call))
}

fun visionDimensions(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = minOf(1.0, 1024.0 / maxOf(width, height))
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}
