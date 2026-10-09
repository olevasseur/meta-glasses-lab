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
        // Tools can follow app-generated text or arrive without an activityEnd.
        if (!waiting && event.calls.isNotEmpty()) begin(nowMs)
        if (!waiting) return null
        val newCalls = event.calls.map { it.id }.filter { it !in calls }
        if (newCalls.isNotEmpty()) {
            if (pendingCalls.isEmpty()) deadlineMs = nowMs + 45_000
            calls.addAll(newCalls)
            pendingCalls.addAll(newCalls)
            replyContent = false // Wait for the continuation after the tool result.
        }
        event.cancelledIds.forEach { toolResult(it, nowMs) }
        val content = event.modelContent || event.audio.any { it.isNotEmpty() } || !event.outputText.isNullOrBlank()
        if (pendingCalls.isEmpty()) {
            if (event.audio.any { it.isNotEmpty() } || !event.outputText.isNullOrBlank()) replyContent = true
            if (content) deadlineMs = nowMs + 45_000 // Healthy long replies can keep streaming.
            else if (!event.inputText.isNullOrBlank()) deadlineMs = startedMs + 45_000
        }
        if (event.interrupted) { reset(); return null }
        if (event.turnComplete && pendingCalls.isEmpty()) {
            // A tool-call generation can complete before its result's spoken continuation.
            if (calls.isNotEmpty() && !replyContent) return null
            waiting = false
            return if (replyContent) null else "empty_turn_complete"
        }
        return null
    }

    fun toolResult(id: String, nowMs: Long) {
        if (pendingCalls.remove(id) && pendingCalls.isEmpty()) {
            // Tool execution has its own cap; the no-reply clock starts at successful send.
            startedMs = nowMs
            deadlineMs = nowMs + 8_000
            replyContent = false
        }
    }

    fun expired(nowMs: Long): String? = if (waiting && nowMs >= deadlineMs) {
        if (calls.isEmpty()) "no_reply_watchdog" else "tool_reply_watchdog"
    } else null

    fun reset() {
        waiting = false
        calls.clear(); pendingCalls.clear()
        replyContent = false
    }
}
