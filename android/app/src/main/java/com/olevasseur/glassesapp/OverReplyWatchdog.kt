package com.olevasseur.glassesapp

/** Main-thread reply state. User speech has no deadline; only a closed activity does. */
internal class OverReplyWatchdog {
    var waiting = false
        private set
    val calls = mutableSetOf<String>()
    private val pendingCalls = mutableSetOf<String>()
    private var startedMs = 0L
    private var deadlineMs = 0L
    private var replyContent = false

    fun begin(nowMs: Long) {
        reset()
        waiting = true
        startedMs = nowMs
        deadlineMs = nowMs + 8_000
    }

    fun event(event: ServerEvent, nowMs: Long): String? {
        if (!waiting) return null
        if (event.calls.isNotEmpty()) {
            calls.addAll(event.calls.map { it.id })
            pendingCalls.addAll(event.calls.map { it.id })
            replyContent = false // Wait for the continuation after the tool result.
        }
        pendingCalls.removeAll(event.cancelledIds.toSet())
        val content = event.modelContent || event.audio.any { it.isNotEmpty() } || !event.outputText.isNullOrBlank()
        if (content && pendingCalls.isEmpty()) replyContent = true
        if (calls.isNotEmpty()) deadlineMs = startedMs + 45_000
        else if (content) deadlineMs = nowMs + 45_000 // Healthy long replies can keep streaming.
        else if (!event.inputText.isNullOrBlank() || event.modelContent) deadlineMs = startedMs + 45_000
        if (event.interrupted) { reset(); return null }
        if (event.turnComplete && pendingCalls.isEmpty()) {
            // A tool-call generation can complete before its result's spoken continuation.
            if (calls.isNotEmpty() && !replyContent) return null
            waiting = false
            return if (replyContent) null else "empty_turn_complete"
        }
        return null
    }

    fun toolResult(id: String) { pendingCalls.remove(id) }

    fun expired(nowMs: Long): String? = if (waiting && nowMs >= deadlineMs) {
        if (calls.isEmpty()) "no_reply_watchdog" else "tool_reply_watchdog"
    } else null

    fun reset() {
        waiting = false
        calls.clear(); pendingCalls.clear()
        replyContent = false
    }
}
