package com.olevasseur.glassesapp

import android.content.Context
import com.meta.wearable.dat.core.session.DeviceSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

interface OverSpeechInput {
    fun startOnDevice()
    fun audio(pcm: ByteArray)
    fun stop()
}

/** Recognition belongs to the conversation; only hypotheses and silence belong to a turn. */
class OverModeController(
    private val scope: CoroutineScope,
    createSpeech: ((String, Boolean) -> Unit, (String) -> Unit) -> OverSpeechInput,
    private val onEnd: (OverTurnDetector.End) -> Unit,
    private val onStatus: (String) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    constructor(context: Context, scope: CoroutineScope, sessions: StateFlow<DeviceSession?>,
        onEnd: (OverTurnDetector.End) -> Unit, onStatus: (String) -> Unit, onError: (String) -> Unit,
    ) : this(scope, { text, status -> OverSpeechRecognizer(context, scope, sessions, text, status, {
        status("Keyword recognition unavailable · pause to finish your turn")
        onError(it)
    }) }, onEnd, onStatus, android.os.SystemClock::elapsedRealtime)

    private var detector: OverTurnDetector? = null
    private var transcriptionDetector: OverTurnDetector? = null
    private var inputText = ""
    private var turnSettings: ConversationSettings? = null
    private var tickJob: Job? = null
    @Volatile private var generation = 0
    @Volatile private var listening = false
    @Volatile private var started = false
    private var recognitionStatus = "Preparing keyword recognition · audio buffered…"
    private val speech = createSpeech({ text, final ->
        detector?.transcript(text, final, clock())?.let(::finish)
    }, { label ->
        if (recognitionStatus != label) {
            inputText = ""
            transcriptionDetector = turnSettings?.let { OverTurnDetector(it.keyword, it.overSilenceSeconds) }
        }
        recognitionStatus = label
        if (listening) onStatus(label)
    })

    fun start(settings: ConversationSettings) {
        if (listening) return
        turnSettings = settings
        detector = OverTurnDetector(settings.keyword, settings.overSilenceSeconds)
        transcriptionDetector = OverTurnDetector(settings.keyword, settings.overSilenceSeconds)
        inputText = ""
        listening = true
        if (!started) {
            started = true
            recognitionStatus = "Preparing keyword recognition · audio buffered…"
            onStatus(recognitionStatus)
            // HFP owns the mic. Do not wait eight seconds for an unavailable DAT session.
            speech.startOnDevice()
        } else onStatus(recognitionStatus)
        tickJob = scope.launch {
            while (isActive) { delay(100); tick() }
        }
    }

    internal fun tick() {
        detector?.tick(clock())?.let(::finish)
        transcriptionDetector?.tick(clock())?.let(::finish)
    }

    /** Opportunistic only: Gemini often delivers this after activityEnd, too late to help. */
    fun inputTranscription(delta: String) {
        if (!listening || recognitionStatus.startsWith("Listening")) return
        inputText = (inputText + delta).takeLast(512)
        transcriptionDetector?.transcript(inputText, false, clock())?.let(::finish)
    }

    fun audio(pcm: ByteArray) {
        if (!started) return
        // Keep the segmented source alive without recognizing the model's loudspeaker echo.
        speech.audio(if (listening) pcm else ByteArray(pcm.size))
        if (!listening) return
        val epoch = generation
        scope.launch {
            if (epoch == generation) detector?.audio(pcm)?.let(::finish)
        }
    }

    private fun finish(reason: OverTurnDetector.End) {
        pause()
        DiagnosticLog.add("over", "sign_off_detected reason=$reason monotonic_ms=${clock()}")
        onEnd(reason) // No recognizer cancellation/Binder cleanup before activityEnd.
    }

    fun pause() {
        listening = false; generation++
        detector = null; transcriptionDetector = null; inputText = ""; turnSettings = null
        tickJob?.cancel(); tickJob = null
    }

    fun stop() {
        pause()
        started = false
        speech.stop()
    }
}
