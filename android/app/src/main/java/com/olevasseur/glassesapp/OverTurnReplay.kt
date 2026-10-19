package com.olevasseur.glassesapp

import java.util.UUID

/** Owned by GeminiSession's send lock; retain only audio accepted by the socket. */
internal class OverTurnReplay(private val cap: Int = 5_760_000) {
    val receiptId = UUID.randomUUID().toString()
    private var pcm = ByteArray(minOf(32_000, cap))
    var size = 0
        private set
    var replayable = true
        private set
    var progress = false
    var retryStartedMs: Long? = null
    var transcript = ""
    /** Gemini's own inputTranscription for the current attempt; better than on-device text. */
    var heard = ""
    private var heardBefore = ""

    /** Best text to save when Gemini never acts: Gemini's transcript, else on-device speech. */
    fun fallbackText(): String = listOf(heard, heardBefore, transcript)
        .map { it.withoutSignOff() }.firstOrNull { it.isNotBlank() }.orEmpty()

    fun append(bytes: ByteArray) {
        if (!replayable) return
        if (bytes.size > cap - size) {
            replayable = false
            pcm = ByteArray(0)
            size = 0
            return
        }
        if (size + bytes.size > pcm.size) pcm = pcm.copyOf(minOf(cap, maxOf(size + bytes.size, pcm.size * 2)))
        bytes.copyInto(pcm, size)
        size += bytes.size
    }

    fun chunk(offset: Int): ByteArray = pcm.copyOfRange(offset, minOf(offset + 6_400, size))

    fun beginRetry(nowMs: Long): Boolean {
        if (progress || !replayable || size == 0 || retryStartedMs != null) return false
        retryStartedMs = nowMs
        if (heard.isNotBlank()) heardBefore = heard
        heard = ""
        return true
    }
}

/** Gemini acted on the turn. A transcript alone is not progress: it can arrive and then nothing. */
internal fun ServerEvent.overProgress(): Boolean = modelContent ||
    outputText != null || audio.isNotEmpty() || calls.isNotEmpty()

private val trailingSignOff = Regex("""(?i)[\s,]*\bover[\s.!]*$""")
internal fun String.withoutSignOff() = replace(trailingSignOff, "").trim()

internal fun stallFallbackAnnouncement(saved: Boolean) = if (saved)
    "Gemini didn't answer. I saved what I heard to your inbox."
else "Gemini didn't answer. Please say that again."
