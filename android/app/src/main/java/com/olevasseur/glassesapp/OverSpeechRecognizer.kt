package com.olevasseur.glassesapp

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.system.Os
import android.system.ErrnoException
import android.system.OsConstants
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.speech.addSpeech
import com.meta.wearable.dat.speech.removeSpeech
import com.meta.wearable.dat.speech.types.SpeechState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** DAT first; Android fallback shares our PCM recorder, never deliberately opens a second mic. */
class OverSpeechRecognizer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val sessions: StateFlow<DeviceSession?>,
    private val onText: (String, Boolean) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (String) -> Unit,
) : OverSpeechInput {
    private var job: Job? = null
    private var platform: SpeechRecognizer? = null
    private var readPipe: ParcelFileDescriptor? = null
    private var writePipe: ParcelFileDescriptor? = null
    private val pipeLock = Any()
    @Volatile private var generation = 0
    private var pump: Job? = null
    private val pendingAudio = SpeechPcmBuffer()
    @Volatile private var acceptingAudio = false

    fun start(forcePhone: Boolean) {
        val previous = job
        stop()
        val epoch = generation
        job = scope.launch {
            previous?.join() // Finish removing the previous turn's shared Speech capability.
            if (!forcePhone || SmokeHooks.wavEnabled) {
                try {
                    listenOnGlasses()
                } catch (e: CancellationException) {
                    if (e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                } catch (_: Exception) { /* Missing permission/device/capability: try shared PCM. */ }
            }
            if (epoch == generation) startPlatform(epoch)
        }
    }

    override fun startOnDevice() { stop(); startPlatform(generation) }

    private fun startPump(epoch: Int) {
        if (pump?.isActive == true) return
        acceptingAudio = true
        pump = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    synchronized(pipeLock) {
                        writePipe?.let { pipe ->
                            pendingAudio.drain { bytes, offset, count ->
                                try { Os.write(pipe.fileDescriptor, bytes, offset, count) }
                                catch (e: ErrnoException) {
                                    if (e.errno == OsConstants.EAGAIN) 0 else throw e
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    scope.launch {
                        if (epoch == generation) failAudio("Speech service stopped consuming shared audio")
                    }
                    return@launch
                }
                delay(10)
            }
        }
    }

    private fun failAudio(reason: String) {
        acceptingAudio = false
        pump?.cancel(); pump = null
        pendingAudio.clear()
        closePlatform()
        onError("$reason. Keyword audio could not be evaluated; pause to finish your turn or restart.")
    }

    private suspend fun listenOnGlasses() {
        check(Wearables.checkPermissionStatus(Permission.MICROPHONE).getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted)
        val session = withTimeout(8000) { sessions.first { it != null } } ?: error("No DAT session")
        coroutineScope {
            withTimeout(8000) { session.state.first { it == DeviceSessionState.STARTED } }
            val speech = session.addSpeech().fold(onSuccess = { it }, onFailure = { _, _ -> null })
                ?: error("No DAT speech")
            try {
                // A newly attached capability has no previous turn's cached StateFlow result.
                launch(start = CoroutineStart.UNDISPATCHED) {
                    speech.transcriptions.collect { result -> result?.let { onText(it.text, it.isFinal) } }
                }
                launch { speech.errors.collect { if (it != null) error("DAT speech failed") } }
                launch { session.errors.collect { error("DAT session failed") } }
                launch { sessions.first { it !== session }; error("DAT session replaced") }
                check(speech.start().fold(onSuccess = { true }, onFailure = { _, _ -> false }))
                withTimeout(5000) { speech.state.first { it == SpeechState.STARTED } }
                onStatus("Listening for sign-off · glasses speech")
                launch { session.state.first { it != DeviceSessionState.STARTED }; error("DAT session stopped") }
                // Speech may stop after inactivity; fallback keeps the user turn open.
                speech.state.first { it == SpeechState.STOPPED }
                error("DAT speech stopped")
            } finally {
                speech.stop()
                session.removeSpeech()
            }
        }
    }

    private fun startPlatform(epoch: Int) {
        if (epoch != generation) return
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            onError("Voice keywords need Android 13+ with on-device speech installed. Pause to finish your turn, or switch modes after Stop.")
            return
        }
        try {
            closePlatform()
            startPump(epoch)
            val pipes = ParcelFileDescriptor.createPipe()
            readPipe = pipes[0]
            synchronized(pipeLock) {
                writePipe = pipes[1]
                Os.fcntlInt(pipes[1].fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
            }
            val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            platform = recognizer
            var segmentedResultDelivered = false
            fun current() = epoch == generation && platform === recognizer
            fun result(bundle: Bundle?, final: Boolean) {
                if (current()) bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onText(it, final) }
            }
            fun restart() {
                if (!current()) return
                closePlatform()
                onStatus("Preparing keyword recognition · audio buffered…")
                job = scope.launch { delay(250); if (epoch == generation) startPlatform(epoch) }
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { if (current()) onStatus("Listening for sign-off · on-device speech") }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit // The shared-PCM detector owns the silence timeout.
                override fun onPartialResults(partialResults: Bundle?) = result(partialResults, false)
                override fun onSegmentResults(segmentResults: Bundle) { segmentedResultDelivered = true; result(segmentResults, true) }
                override fun onResults(results: Bundle?) { if (!segmentedResultDelivered) result(results, true); restart() }
                override fun onEndOfSegmentedSession() = restart()
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
                override fun onError(error: Int) {
                    if (!current()) return
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) restart()
                    else {
                        failAudio("On-device speech unavailable ($error)")
                    }
                }
            })
            recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipes[0])
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            })
            onStatus("Starting on-device speech · shared microphone audio")
        } catch (_: Exception) {
            failAudio("Could not start on-device speech")
        }
    }

    /** Startup and EAGAIN retain PCM; neither can block the Gemini upload thread. */
    override fun audio(pcm: ByteArray) {
        if (!acceptingAudio) return
        if (!pendingAudio.offer(pcm)) {
            acceptingAudio = false
            val epoch = generation
            scope.launch {
                if (epoch == generation) failAudio("Keyword audio buffer exceeded 16 seconds")
            }
        }
    }

    private fun closePlatform() {
        val previous = platform
        platform = null
        previous?.cancel(); previous?.destroy()
        synchronized(pipeLock) { runCatching { writePipe?.close() }; writePipe = null }
        runCatching { readPipe?.close() }; readPipe = null
    }

    override fun stop() {
        generation++; job?.cancel()
        acceptingAudio = false
        pump?.cancel(); pump = null
        closePlatform(); pendingAudio.clear()
    }
}
