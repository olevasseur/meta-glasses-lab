package com.olevasseur.glassesapp

import android.content.Context
import android.os.SystemClock
import com.meta.wearable.dat.core.session.DeviceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

/** Keeps keyword recognition and silence detection scoped to one manually opened user turn. */
class OverModeController(
    context: Context,
    private val scope: CoroutineScope,
    sessions: StateFlow<DeviceSession?>,
    private val onEnd: (OverTurnDetector.End) -> Unit,
    private val onStatus: (String) -> Unit,
    onError: (String) -> Unit,
) {
    private var detector: OverTurnDetector? = null
    private var tickJob: Job? = null
    @Volatile private var generation = 0
    @Volatile private var listening = false
    private val speech = OverSpeechRecognizer(context, scope, sessions,
        onText = { text, final -> detector?.transcript(text, final, SystemClock.elapsedRealtime())?.let(::finish) },
        onStatus = onStatus, onError = onError,
    )

    fun start(settings: ConversationSettings, forcePhone: Boolean) {
        stop()
        detector = OverTurnDetector(settings.keyword, settings.overSilenceSeconds)
        listening = true
        onStatus("Preparing keyword recognition…")
        speech.start(forcePhone)
        tickJob = scope.launch {
            while (isActive) { delay(100); detector?.tick(SystemClock.elapsedRealtime())?.let(::finish) }
        }
    }

    fun audio(pcm: ByteArray) {
        if (!listening) return
        val epoch = generation
        speech.audio(pcm)
        scope.launch {
            if (epoch == generation) detector?.audio(pcm)?.let(::finish)
        }
    }

    private fun finish(reason: OverTurnDetector.End) { stop(); onEnd(reason) }
    fun stop() {
        listening = false; generation++
        detector = null
        tickJob?.cancel(); tickJob = null
        speech.stop()
    }
}
