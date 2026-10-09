package com.olevasseur.glassesapp

enum class PauseReason { CALL, FOCUS, BLUETOOTH }

/** Main-thread controls; clock values are elapsed realtime, never wall-clock timestamps. */
data class HandsFreeState(
    val armed: Boolean = false,
    val conversation: Boolean = false,
    val inputsAvailable: Boolean = false,
    val mode: ConversationMode = ConversationMode.AUTO,
    val muted: Boolean = false,
    val pauses: Set<PauseReason> = emptySet(),
    val lastActivityMs: Long = 0,
) {
    val ptt get() = mode == ConversationMode.PTT
    val shouldRun get() = armed && conversation && pauses.isEmpty()
    fun arm(now: Long) = copy(armed = true, lastActivityMs = now)
    fun start(now: Long) = arm(now).copy(conversation = true,
        pauses = pauses - PauseReason.BLUETOOTH)
    fun captureHold(now: Long) = if (conversation) copy(conversation = false, muted = false, lastActivityMs = now) else start(now)
    fun pause(reason: PauseReason, paused: Boolean) = copy(pauses = if (paused) pauses + reason else pauses - reason)
    fun inputsAvailable(available: Boolean) = copy(inputsAvailable = armed && available)
    fun activity(now: Long) = copy(lastActivityMs = now)
    fun setMode(value: ConversationMode, now: Long) = copy(mode = value, muted = false, lastActivityMs = now)
    fun toggleMode(now: Long) = setMode(ConversationMode.entries[(mode.ordinal + 1) % ConversationMode.entries.size], now)
    fun toggleMute(now: Long) = if (shouldRun && mode == ConversationMode.AUTO) copy(muted = !muted, lastActivityMs = now) else this
    fun stop() = copy(armed = false, conversation = false, inputsAvailable = false, muted = false, pauses = pauses.intersect(setOf(PauseReason.CALL)))
    fun expired(now: Long, timeoutMs: Long) = armed && now - lastActivityMs >= timeoutMs

    companion object {
        const val DEFAULT_IDLE_MINUTES = 5
        fun idleMinutes(value: Int) = value.coerceIn(1, 60)
    }
}
