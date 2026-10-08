package com.olevasseur.glassesapp

import java.util.Locale

/** One user turn. Call on Main; partial transcripts replace hypotheses, never append them. */
class OverTurnDetector(private val keyword: String, private val safetySeconds: Int = 45) {
    enum class End { KEYWORD, SIGN_OFF, SAFETY_TIMEOUT }
    private var ended = false
    private var pending: End? = null
    private var pendingSince = 0L
    private var hypothesis = ""
    private var finalTail = ""
    private var speechSince: Long? = null
    private var lastSpeech: Long? = null

    fun transcript(text: String, final: Boolean, nowMs: Long): End? {
        if (ended) return null
        val words = normalize(text)
        if (words.isBlank()) return null
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

    /** Safety only: silence resets the continuous-speech clock and never ends a turn. */
    fun audio(pcm: ByteArray, nowMs: Long): End? {
        if (ended) return null
        if (lastSpeech?.let { nowMs - it > 800 } == true) speechSince = null
        if (!hasVoiceEnergy(pcm)) return null
        if (speechSince == null) speechSince = nowMs
        lastSpeech = nowMs
        return if (nowMs - speechSince!! >= safetySeconds * 1000L) finish(End.SAFETY_TIMEOUT) else null
    }

    private fun finish(reason: End): End { ended = true; pending = null; return reason }

    companion object {
        private val word = Regex("[\\p{L}\\p{N}]+")
        fun normalize(text: String): String = word.findAll(text.lowercase(Locale.ROOT)).joinToString(" ") { it.value }
        private fun endsWithWords(text: String, suffix: String) = text == suffix || text.endsWith(" $suffix")

        private fun hasVoiceEnergy(pcm: ByteArray): Boolean {
            if (pcm.size < 2) return false
            var energy = 0.0
            for (i in 0 until pcm.size - 1 step 2) {
                val sample = ((pcm[i].toInt() and 255) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
                energy += sample.toDouble() * sample
            }
            // ponytail: energy approximates speech; sustained noise can hit the safety limit.
            // Tune on HFP hardware before replacing this with a dedicated local VAD.
            return energy / (pcm.size / 2) >= 600.0 * 600.0
        }
    }
}
