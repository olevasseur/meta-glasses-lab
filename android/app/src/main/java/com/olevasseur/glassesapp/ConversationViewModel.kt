package com.olevasseur.glassesapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class TranscriptLine(val speaker: String, val text: String)
data class ConversationState(
    val glasses: String = "Grant permissions to connect glasses",
    val inputs: String = "Inactive",
    val gemini: String = "Stopped",
    val route: String = "No audio session",
    val permissions: Boolean = false,
    val ptt: Boolean = false,
    val running: Boolean = false,
    val stopping: Boolean = false,
    val connected: Boolean = false,
    val talking: Boolean = false,
    val mock: Boolean = false,
    val camera: String = "Camera off",
    val watchMode: Boolean = false,
    val transcript: List<TranscriptLine> = emptyList(),
    val lastTool: String = "None",
    val error: String? = null,
)

class ConversationViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(ConversationState())
    val state = mutableState.asStateFlow()
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private val backend = BackendClient(http, BuildConfig.BACKEND_URL, BuildConfig.APP_SHARED_SECRET)
    private val audio = ConversationAudio(application, viewModelScope)
    private var transcriptBoundary = true
    private var screenTalking = false
    private var glassesTalking = false
    private val gemini = GeminiSession(
        scope = viewModelScope, backend = backend, audio = audio,
        onState = { label, connected ->
            if (!connected) { screenTalking = false; glassesTalking = false; vision.stop() }
            mutableState.update { it.copy(gemini = label, connected = connected) }
        },
        onTranscript = ::appendTranscript,
        onTurnEnd = { transcriptBoundary = true },
        onTool = { result -> mutableState.update { it.copy(lastTool = result) } },
        onTalking = { value ->
            if (!value) { screenTalking = false; glassesTalking = false }
            mutableState.update { it.copy(talking = value) }
        },
        onFatal = { message -> error(message); stop() },
        localTool = { if (it.name in ReadToMeController.TOOL_NAMES) reading.tool(it) else vision.tool(it) },
    )
    private val camera: GlassesCamera by lazy {
        GlassesCamera(viewModelScope, glasses) { label -> mutableState.update { it.copy(camera = label) } }
    }
    private val vision: VisionController by lazy {
        VisionController(viewModelScope, camera::capture, gemini::sendImage, camera::stop,
            onWatch = { enabled -> mutableState.update { it.copy(watchMode = enabled) } }, onError = ::error)
    }
    private val reading: ReadToMeController by lazy {
        ReadToMeController({ vision.sendFrame(camera::captureText) }, vision::stop, audio::flush)
    }
    val glasses = GlassesController(application, viewModelScope,
        onGlasses = { label -> mutableState.update { it.copy(glasses = label) } },
        onInputs = { label -> mutableState.update { it.copy(inputs = label) } },
        onHold = {
            if (state.value.connected && state.value.ptt) {
                glassesTalking = !glassesTalking
                gemini.setTalking(glassesTalking || screenTalking)
            }
        },
        onInputLost = { glassesTalking = false; gemini.setTalking(screenTalking) },
        onError = ::error,
    )
    private var startJob: Job? = null
    private var stopJob: Job? = null

    fun permissionsGranted(granted: Boolean) {
        mutableState.update { it.copy(permissions = granted) }
        // Bluetooth is optional for a phone-only session; activity initializes DAT separately.
        if (!granted) error("Microphone permission is required. Allow it in Android app settings.")
    }
    fun error(message: String) { mutableState.update { it.copy(error = message) } }
    fun setMode(ptt: Boolean) { if (!state.value.running) mutableState.update { it.copy(ptt = ptt) } }
    fun setMock(enabled: Boolean) {
        if (state.value.running || state.value.stopping) return
        if (glasses.setMock(enabled)) mutableState.update { it.copy(mock = enabled) }
    }

    fun start() {
        if (state.value.running || state.value.stopping || !state.value.permissions) return
        if (!BuildConfig.DEBUG || BuildConfig.APP_SHARED_SECRET.isBlank()) {
            error("Set APP_SHARED_SECRET in android/local.properties and rebuild the debug app.")
            return
        }
        mutableState.update { it.copy(running = true, gemini = "Opening microphone…", error = null) }
        startJob = viewModelScope.launch {
            try {
                audio.start(forcePhone = state.value.mock, onAudio = gemini::sendAudio,
                    onRoute = { label -> mutableState.update { it.copy(route = label) } },
                    onError = { message -> viewModelScope.launch { error(message); stop() } },
                )
                gemini.start(state.value.ptt)
                if (state.value.ptt) glasses.startInputs()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(e.message ?: "Cannot start audio"); stop() }
        }
    }

    fun talk(pressed: Boolean) {
        if (!state.value.connected || !state.value.ptt) return
        screenTalking = pressed
        gemini.setTalking(screenTalking || glassesTalking)
    }
    fun finishTalking() { screenTalking = false; glassesTalking = false; gemini.setTalking(false) }

    fun setWatchMode(enabled: Boolean) {
        if (!enabled || state.value.connected) vision.setWatchMode(enabled)
    }

    fun stop() {
        if (state.value.stopping || !state.value.running) return
        mutableState.update { it.copy(stopping = true, connected = false, talking = false) }
        vision.stop()
        reading.clear()
        gemini.stop()
        glasses.stopInputs()
        screenTalking = false; glassesTalking = false
        stopJob = viewModelScope.launch {
            startJob?.cancelAndJoin(); startJob = null
            audio.stop()
            mutableState.update { it.copy(running = false, stopping = false, gemini = "Stopped", route = "No audio session") }
        }
    }

    private fun appendTranscript(speaker: String, text: String) {
        mutableState.update { state ->
            val previous = state.transcript.lastOrNull()
            val lines = if (!transcriptBoundary && previous?.speaker == speaker) {
                state.transcript.dropLast(1) + previous.copy(text = (previous.text + text).takeLast(12_000))
            } else state.transcript + TranscriptLine(speaker, text.takeLast(12_000))
            state.copy(transcript = lines.takeLast(200))
        }
        transcriptBoundary = false
    }

    override fun onCleared() {
        vision.stop()
        reading.clear()
        gemini.close(); glasses.close()
        // viewModelScope is already cancelled; finish releasing platform audio on a cleanup scope.
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        cleanup.launch {
            stopJob?.join()
            audio.stop()
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
            cleanup.cancel()
        }
        super.onCleared()
    }
}
