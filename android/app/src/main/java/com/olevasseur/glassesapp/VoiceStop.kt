package com.olevasseur.glassesapp

import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Main-thread conversation exit. Generation completion is not speaker completion. */
class VoiceStop(
    private val scope: CoroutineScope,
    private val allowed: () -> Boolean,
    private val playbackIdle: () -> Boolean,
    private val endConversation: () -> Unit,
) {
    private var input = ""
    private var wait: Job? = null
    private var toolId: String? = null
    private var heardGoodbye = false
    private var generationDone = false
    val pending: Boolean get() = wait != null

    fun tool(call: FunctionCall): ToolResult {
        if (!allowed()) return ToolResult(toolError("Voice stop is unavailable; use stop_meeting to end meeting capture"))
        if (call.args.length() != 0) return ToolResult(toolError("end_conversation takes no arguments"))
        request()
        toolId = call.id
        generationDone = false
        return ToolResult(JSONObject().put("status", "ok")
            .put("message", "Say only Bye! now. The phone will close after playback, within four seconds."))
    }

    fun event(event: ServerEvent) {
        if (!allowed()) { reset(); return }
        if (toolId in event.cancelledIds) reset()
        input = (input + event.inputText.orEmpty()).take(12_000)
        if (pending && (event.audio.isNotEmpty() || !event.outputText.isNullOrBlank())) heardGoodbye = true
        if (event.turnComplete && !event.interrupted) {
            if (matches(input)) request()
            // A tool-call generation can end before its spoken continuation begins.
            if (pending && event.calls.isEmpty() && (heardGoodbye || (toolId == null && matches(input)))) generationDone = true
            input = ""
        }
        if (event.interrupted) input = ""
    }

    private fun request() {
        if (pending) return
        heardGoodbye = false
        generationDone = false
        wait = scope.launch {
            withTimeoutOrNull(4_000) {
                // Yield so audio in the triggering event is enqueued before checking the speaker.
                do { delay(20) } while (!generationDone || !playbackIdle())
            }
            val shouldEnd = allowed()
            reset()
            if (shouldEnd) endConversation()
        }
    }

    fun reset() {
        wait?.cancel(); wait = null
        input = ""; toolId = null; heardGoodbye = false; generationDone = false
    }

    companion object {
        const val TOOL = "end_conversation"
        private val phrases = setOf("goodbye", "good bye", "stop listening", "end conversation",
            "end the conversation", "thats all thanks", "that is all thanks")
        fun matches(text: String): Boolean {
            val normalized = text.lowercase(Locale.ROOT).replace(Regex("['’]"), "")
                .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            return normalized in phrases || normalized.removeSuffix(" over") in phrases
        }
    }
}
