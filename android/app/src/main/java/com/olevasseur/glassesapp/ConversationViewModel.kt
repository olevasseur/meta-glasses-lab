package com.olevasseur.glassesapp

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient

data class TranscriptLine(val speaker: String, val text: String)
data class ConversationState(
    val glasses: String = "Grant permissions to connect glasses",
    val inputs: String = "Inactive",
    val gemini: String = "Stopped",
    val route: String = "No audio session",
    val permissions: Boolean = false,
    val settings: ConversationSettings = ConversationSettings(),
    val settingsLoaded: Boolean = false,
    val overStatus: String = "Inactive",
    val running: Boolean = false,
    val stopping: Boolean = false,
    val connected: Boolean = false,
    val talking: Boolean = false,
    val mock: Boolean = false,
    val camera: String = "Camera off",
    val watchMode: Boolean = false,
    val visualMemory: Boolean = false,
    val visualCapturing: Boolean = false,
    val transcript: List<TranscriptLine> = emptyList(),
    val lastTool: String = "None",
    val error: String? = null,
) {
    val ptt: Boolean get() = settings.mode == ConversationMode.PTT
}

class ConversationViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow(ConversationState())
    val state = mutableState.asStateFlow()
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private val backend = BackendClient(http, BuildConfig.BACKEND_URL, BuildConfig.APP_SHARED_SECRET)
    private val memory = TranscriptMemory(backend, File(application.noBackupFilesDir, "memory-outbox"), ::error)
    private val memoryUploads = viewModelScope.launch {
        while (isActive) { memory.flush(); delay(5_000) }
    }
    private val audio = ConversationAudio(application, viewModelScope)
    val gestures = HeadGestures(application, viewModelScope) { visualMemory.motion(it) }
    private var transcriptBoundary = true
    private var screenTalking = false
    private var glassesTalking = false
    private val settingsStore = ConversationSettingsStore(application.conversationSettingsStore)
    private var overResumeJob: Job? = null
    private val over: OverModeController by lazy {
        OverModeController(application, viewModelScope, glasses.activeSession,
            onEnd = { reason ->
                gemini.setTalking(false)
                mutableState.update { it.copy(overStatus = if (reason == OverTurnDetector.End.SILENCE_TIMEOUT)
                    "Silence timeout · waiting for reply" else "Waiting for reply") }
                if (reason == OverTurnDetector.End.SIGN_OFF) stop()
            },
            onStatus = { label -> mutableState.update { it.copy(overStatus = label) } },
            onError = { message ->
                mutableState.update { it.copy(overStatus = "Keyword recognition unavailable · pause to finish your turn") }
                error(message)
            },
        )
    }
    private val gemini: GeminiSession = GeminiSession(
        scope = viewModelScope, backend = backend, audio = audio,
        onState = { label, connected ->
            if (!connected) { screenTalking = false; glassesTalking = false; gestures.disconnected(); visualMemory.stop(); vision.stop() }
            mutableState.update { it.copy(gemini = label, connected = connected) }
            if (state.value.settings.mode == ConversationMode.OVER) {
                if (connected) beginOverTurn() else { overResumeJob?.cancel(); over.stop() }
            }
        },
        onTranscript = ::appendTranscript,
        onTurnEnd = {
            transcriptBoundary = true
            if (state.value.settings.mode == ConversationMode.OVER && !state.value.talking) {
                overResumeJob?.cancel()
                overResumeJob = viewModelScope.launch { audio.awaitPlaybackIdle(); beginOverTurn() }
            }
        },
        onTool = { result -> mutableState.update { it.copy(lastTool = result) } },
        onTalking = { value ->
            if (!value) { screenTalking = false; glassesTalking = false }
            mutableState.update { it.copy(talking = value) }
        },
        onFatal = { message -> error(message); stop() },
        localTool = { call -> gestures.localTool(call)?.let { ToolResult(it) } ?: if (call.name in VisualMemoryController.TOOL_NAMES) visualMemory.tool(call) else vision.tool(call) },
        onEvent = gestures::onEvent,
    )
    private val camera: GlassesCamera by lazy {
        GlassesCamera(viewModelScope, glasses) { label -> mutableState.update { it.copy(camera = label) } }
    }
    private val vision: VisionController by lazy {
        VisionController(viewModelScope, camera::capture, gemini::sendImage, camera::stop,
            onWatch = { enabled -> mutableState.update { it.copy(watchMode = enabled) } }, onError = ::error)
    }
    private var visualLocation: String? = null
    private val visualMemory: VisualMemoryController by lazy {
        VisualMemoryController(viewModelScope, vision::captureForMemory, ::visualImageHash,
            upload = { jpeg, label, pinned -> backend.visualFrame(jpeg, label, pinned, visualLocation) },
            delete = backend::deleteVisual, now = android.os.SystemClock::elapsedRealtime,
            onActive = { capturing ->
                mutableState.update { it.copy(visualMemory = visualMemory.active, visualCapturing = capturing) }
                gestures.requestMotion(capturing)
                if (capturing) glasses.startInputs()
            }, chime = { visualCaptureChime(viewModelScope) }, onError = ::error)
    }
    fun setVisualMemory(enabled: Boolean) {
        if (!enabled || state.value.connected) visualMemory.setEnabled(enabled)
    }
    fun setVisualLocation(label: String?) { visualLocation = label?.trim()?.takeIf { it.isNotEmpty() }?.take(100) }
    fun rememberVisual() { if (state.value.connected) runVisualTool("remember_this") }
    fun deleteVisualHour() { runVisualTool("delete_visual_memory") }
    private fun runVisualTool(name: String) {
        viewModelScope.launch {
            val result = visualMemory.tool(FunctionCall("screen", name, org.json.JSONObject()))
            mutableState.update { it.copy(lastTool = result.response.toString()) }
        }
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
        onInputLost = { glassesTalking = false; if (state.value.ptt) gemini.setTalking(screenTalking) },
        onError = ::error,
    )
    private var startJob: Job? = null
    private var stopJob: Job? = null

    fun setGestures(enabled: Boolean) {
        gestures.setEnabled(enabled)
        if (state.value.running && !state.value.stopping) {
            // Disabling Motion must not stop a camera sharing this session.
            // Inputs/session stay attached until conversation Stop.
            if (enabled) glasses.startInputs()
        }
    }

    init {
        gestures.observe(glasses.activeSession, ::sendGesture)
        viewModelScope.launch {
            try {
                val saved = settingsStore.settings.first()
                mutableState.update { it.copy(settings = saved, settingsLoaded = true) }
            } catch (_: java.io.IOException) {
                mutableState.update { it.copy(settingsLoaded = true) }
                error("Could not load conversation settings; using defaults.")
            }
        }
    }

    fun permissionsGranted(granted: Boolean) {
        mutableState.update { it.copy(permissions = granted) }
        // Bluetooth is optional for a phone-only session; activity initializes DAT separately.
        if (!granted) error("Microphone permission is required. Allow it in Android app settings.")
    }
    fun error(message: String) { mutableState.update { it.copy(error = message) } }
    fun setSettings(settings: ConversationSettings) {
        if (state.value.running || state.value.stopping || !state.value.settingsLoaded) return
        val value = settings.normalized()
        mutableState.update { it.copy(settings = value) }
        viewModelScope.launch {
            try { settingsStore.save(value) }
            catch (_: java.io.IOException) { error("Could not save conversation settings.") }
        }
    }
    fun setMock(enabled: Boolean) {
        if (state.value.running || state.value.stopping) return
        if (glasses.setMock(enabled)) mutableState.update { it.copy(mock = enabled) }
    }

    fun start() {
        if (state.value.running || state.value.stopping || !state.value.permissions || !state.value.settingsLoaded) return
        if (!BuildConfig.DEBUG || BuildConfig.APP_SHARED_SECRET.isBlank()) {
            error("Set APP_SHARED_SECRET in android/local.properties and rebuild the debug app.")
            return
        }
        mutableState.update { it.copy(running = true, gemini = "Opening microphone…", error = null) }
        startJob = viewModelScope.launch {
            try {
                audio.start(forcePhone = state.value.mock, onAudio = { pcm -> gemini.sendAudio(pcm); over.audio(pcm) },
                    onRoute = { label -> mutableState.update { it.copy(route = label) } },
                    onError = { message -> viewModelScope.launch { error(message); stop() } },
                )
                memory.begin()
                transcriptBoundary = true
                if (state.value.settings.mode != ConversationMode.AUTO || gestures.state.value.enabled) glasses.startInputs()
                gemini.start(state.value.settings)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(e.message ?: "Cannot start audio"); stop() }
        }
    }

    fun talk(pressed: Boolean) {
        if (!state.value.connected || !state.value.ptt) return
        screenTalking = pressed
        gemini.setTalking(screenTalking || glassesTalking)
    }
    private fun beginOverTurn() {
        if (!state.value.running || state.value.stopping || !state.value.connected || state.value.settings.mode != ConversationMode.OVER) return
        gemini.setTalking(true)
        if (state.value.talking) over.start(state.value.settings, state.value.mock || state.value.route.startsWith("Phone"))
    }

    private fun sendGesture(text: String): Boolean {
        val sent = gemini.sendGestureText(text)
        if (state.value.settings.mode == ConversationMode.OVER && !state.value.talking) {
            overResumeJob?.cancel()
            over.stop()
            mutableState.update { it.copy(overStatus = "Waiting for reply") }
        }
        if (sent) appendTranscript("You", text)
        return sent
    }

    fun finishTalking() {
        if (!state.value.ptt) return
        screenTalking = false; glassesTalking = false
        gemini.setTalking(false)
    }

    fun setWatchMode(enabled: Boolean) {
        if (!enabled || state.value.connected) vision.setWatchMode(enabled)
    }

    fun stop() {
        if (state.value.stopping || !state.value.running) return
        mutableState.update { it.copy(stopping = true, connected = false, talking = false) }
        overResumeJob?.cancel(); over.stop()
        visualMemory.stop()
        vision.stop()
        gemini.stop()
        memory.stop()
        gestures.disconnected()
        glasses.stopInputs()
        screenTalking = false; glassesTalking = false
        stopJob = viewModelScope.launch {
            startJob?.cancelAndJoin(); startJob = null
            audio.stop()
            memory.flush()
            mutableState.update { it.copy(running = false, stopping = false, gemini = "Stopped", route = "No audio session") }
        }
    }

    private fun appendTranscript(speaker: String, text: String) {
        memory.append(if (speaker == "You") "user" else "model", text)
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
        overResumeJob?.cancel(); over.stop()
        visualMemory.stop()
        vision.stop()
        gemini.close(); glasses.close()
        memory.stop()
        // viewModelScope is already cancelled; finish releasing platform audio on a cleanup scope.
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        cleanup.launch {
            stopJob?.join()
            memoryUploads.join()
            audio.stop()
            memory.flush()
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
            cleanup.cancel()
        }
        super.onCleared()
    }
}
