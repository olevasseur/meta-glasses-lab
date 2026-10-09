package com.olevasseur.glassesapp

import java.util.Locale

/** One user turn. Call on Main; partial transcripts replace hypotheses, never append them. */
class OverTurnDetector(private val keyword: String, silenceSeconds: Int = 10) {
    enum class End { KEYWORD, SIGN_OFF, SILENCE_TIMEOUT }
    private var ended = false
    private var pending: End? = null
    private var pendingSince = 0L
    private var hypothesis = ""
    private var finalTail = ""
    // HFP fixture attenuation loses quiet speech at the shared detector's 700 RMS gate.
    private val speech = SpeechEnergyDetector(startRms = 350.0, holdRms = 225.0)
    private val silenceLimitSamples = silenceSeconds.coerceIn(5, 30) * 16_000L
    private var silentSamples = 0L
    var firstSpeechMs: Long? = null
        private set
    var lastSpeechMs: Long? = null
        private set

    fun speechDetected(nowMs: Long) {
        if (ended) return
        if (firstSpeechMs == null) firstSpeechMs = nowMs
        lastSpeechMs = nowMs
        silentSamples = 0
    }

    fun transcript(text: String, final: Boolean, nowMs: Long): End? {
        if (ended) return null
        val words = normalize(text)
        if (words.isBlank()) return null
        // Changed partials are speech evidence even when HFP energy is below the gate.
        if (words != hypothesis) speechDetected(nowMs)
        val match = when {
            endsWithWords("$finalTail $words".trim(), "over and out") -> End.SIGN_OFF
            endsWithWords(words, keyword) -> End.KEYWORD
            else -> null
        }
        // Leave time for "over" to become "over and out", including across final segments.
        if (final) finalTail = "$finalTail $words".trim().split(" ").takeLast(3).joinToString(" ")
        if (words != hypothesis) pendingSince = nowMs
        hypothesis = words
        pending = match
        if (final && match != null && (match == End.SIGN_OFF || !endsWithWords(words, "over"))) {
            return finish(match)
        }
        return null
    }

    fun tick(nowMs: Long): End? = if (!ended && pending != null && nowMs - pendingSince >= 1200) {
        finish(pending!!)
    } else null

    /** Count captured silence, not session age or missing microphone callbacks. */
    fun audio(pcm: ByteArray, nowMs: Long = System.nanoTime() / 1_000_000): End? {
        if (ended || pcm.size < 2) return null
        silentSamples += pcm.size / 2
        if (speech.accept(pcm)) speechDetected(nowMs)
        // Let a new onset finish its debounce even at the silence boundary.
        return if (firstSpeechMs != null && !speech.pendingSpeech && silentSamples >= silenceLimitSamples) finish(End.SILENCE_TIMEOUT) else null
    }

    private fun finish(reason: End): End { ended = true; pending = null; return reason }

    companion object {
        private val word = Regex("[\\p{L}\\p{N}]+")
        fun normalize(text: String): String = word.findAll(text.lowercase(Locale.ROOT)).joinToString(" ") { it.value }
        private fun endsWithWords(text: String, suffix: String) = text == suffix || text.endsWith(" $suffix")

    }
}
