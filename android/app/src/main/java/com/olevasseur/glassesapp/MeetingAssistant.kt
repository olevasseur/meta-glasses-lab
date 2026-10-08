package com.olevasseur.glassesapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Local user intent opens one normal Live exchange; recording has an independent lifetime. */
class MeetingAssistant(
    private val scope: CoroutineScope,
    private val open: () -> Unit,
    private val close: () -> Unit,
    private val send: (String) -> Boolean,
    private val playbackIdle: suspend () -> Unit,
    private val listening: (Boolean) -> Unit,
) {
    @Volatile var active = false
        private set
    private var request: String? = null
    private var timeout: Job? = null
    private var finishing: Job? = null

    fun recognized(text: String, final: Boolean, phrase: String) {
        if (!final || active) return
        val pattern = Regex("(?i)^\\s*" + phrase.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) } + "\\b[,.!? ]*")
        val match = pattern.find(text) ?: return
        begin(text.substring(match.range.last + 1).trim().takeIf { it.isNotEmpty() })
    }

    fun begin(text: String? = null) {
        if (active) return
        active = true; request = text
        listening(false); open()
        timeout = scope.launch { delay(60_000); stop() }
    }

    fun ready() { request?.let { if (send(it)) request = null } }

    fun turnEnded() {
        if (!active || finishing != null) return
        finishing = scope.launch {
            // Let the current server event enqueue its last audio before checking playback.
            delay(100)
            playbackIdle()
            stop()
        }
    }

    fun stop(resumeListening: Boolean = true) {
        timeout?.cancel(); timeout = null
        finishing?.cancel(); finishing = null
        request = null
        if (active) { active = false; close() }
        if (resumeListening) listening(true)
    }
}
