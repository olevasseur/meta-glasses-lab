package com.olevasseur.glassesapp

import android.app.Application
import android.annotation.SuppressLint
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
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
    val transcript: List<TranscriptLine> = emptyList(),
    val lastTool: String = "None",
    val error: String? = null,
    val handsFree: HandsFreeState = HandsFreeState(),
) {
    val ptt: Boolean get() = settings.mode == ConversationMode.PTT
    val idleMinutes: Int get() = settings.idleMinutes
    val translationLanguage: String? get() = settings.translationLanguage
    val translationPhoneSpeaker: Boolean get() = settings.translationPhoneSpeaker
}

class ConversationViewModel(application: Application) : AndroidViewModel(application) {
    // Same lifetime as its owning Service; onCleared removes this reference.
    @SuppressLint("StaticFieldLeak")
    internal var service: ConversationService? = null
    @Volatile private var muted = false
    private val mutableState = MutableStateFlow(ConversationState())
    val state = mutableState.asStateFlow()
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS) // Longer than the backend event poll (20 seconds).
        .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    private val backend = BackendClient(http, BuildConfig.BACKEND_URL, BuildConfig.APP_SHARED_SECRET)
    private val memory = TranscriptMemory(backend, File(application.noBackupFilesDir, "memory-outbox"), ::error)
    private val memoryUploads = viewModelScope.launch {
        while (isActive) { memory.flush(); delay(5_000) }
    }
    private val audio = ConversationAudio(application, viewModelScope)
    val gestures = HeadGestures(application, viewModelScope)
    val workout = WorkoutCoach(application, viewModelScope, backend, ::error)
    private var transcriptBoundary = true
    private var screenTalking = false
    private var glassesTalking = false
    private val settingsStore = ConversationSettingsStore(application.conversationSettingsStore)
    private var overResumeJob: Job? = null
    private var audioReady = false
    private var overAwaitingReply = false
    private val over: OverModeController by lazy {
        OverModeController(application, viewModelScope, glasses.activeSession,
            onEnd = { reason ->
                overAwaitingReply = true
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
            if (!connected) { screenTalking = false; glassesTalking = false; overAwaitingReply = false; gestures.disconnected(); reading.pause() }
            mutableState.update { it.copy(gemini = label, connected = connected) }
            if (state.value.settings.mode == ConversationMode.OVER) {
                if (connected) beginOverTurn() else { overResumeJob?.cancel(); over.stop() }
            }
        },
        onTranscript = ::appendTranscript,
        onTurnEnd = {
            transcriptBoundary = true
            overAwaitingReply = false
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
        onTranslationChanged = { language ->
            transcriptBoundary = true
            reading.clear()
            if (language != null) workout.stop(interrupted = true)
            if (state.value.translationLanguage != language) {
                saveSettings(state.value.settings.copy(translationLanguage = language))
            }
        },
        localTool = { call ->
            if (!state.value.handsFree.shouldRun || !audioReady) ToolResult(toolError("Conversation is paused"))
            else if (call.name in ReadToMeController.TOOL_NAMES) reading.tool(call)
            else (workout.localTool(call) ?: gestures.localTool(call))?.let { ToolResult(it) } ?: vision.tool(call)
        },
        onEvent = { event -> if (state.value.handsFree.shouldRun && audioReady) gestures.onEvent(event) },
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
        onHold = { service?.captureHold() },
        onDoubleTap = { service?.toggleInput() },
        onInputLost = { finishTalking(); service?.inputsAvailable(false) },
        onInputReady = { service?.inputsAvailable(true) },
        onError = ::error,
    )
    private val jobNotifications = JobNotifications(application)
    init {
        viewModelScope.launch {
            // Load the durable cursor before polling so service recreation cannot replay it.
            val cursor = try { settingsStore.jobCursor(BuildConfig.BACKEND_URL) }
            catch (_: java.io.IOException) {
                error("Could not load job notifications; reopen the app to retry.")
                return@launch
            }
            JobEvents(
                fetch = backend::events,
                initialCursor = cursor,
                saveCursor = { settingsStore.saveJobCursor(BuildConfig.BACKEND_URL, it) },
                deliver = { event ->
                    mutableState.update { it.copy(lastTool = "${event.status}: ${event.summary}") }
                    (gestures.state.value.question == null && gemini.sendJobEvent(event)) || jobNotifications.show(event)
                },
            ).listen()
        }
    }

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
        workout.bind { text ->
            gestures.state.value.question == null && gemini.sendWorkoutCue(text)
        }
        gestures.observe(glasses.activeSession, ::sendGesture,
            workout.state.map { it.needsMotion }.distinctUntilChanged(), workout::onSample, workout::motionUnavailable)
        viewModelScope.launch {
            workout.state.map { it.needsMotion }.distinctUntilChanged().collect { needed ->
                if (needed && state.value.running && !state.value.stopping) glasses.startInputs()
                // Releasing workout Motion must preserve Inputs, camera and Over Speech.
            }
        }
        viewModelScope.launch {
            try {
                val saved = settingsStore.settings.first()
                workout.setSensitivity(saved.workoutSensitivity)
                mutableState.update { it.copy(settings = saved, settingsLoaded = true) }
            } catch (_: java.io.IOException) {
                mutableState.update { it.copy(settingsLoaded = true) }
                error("Could not load conversation settings; using defaults.")
            }
        }
    }

    fun permissionsGranted(granted: Boolean) {
        mutableState.update { it.copy(permissions = granted) }
        // Bluetooth is optional for a phone-only session; the service initializes DAT separately.
        if (!granted) error("Microphone permission is required. Allow it in Android app settings.")
    }
    fun error(message: String) { mutableState.update { it.copy(error = message) } }
    fun setSettings(settings: ConversationSettings) {
        if (state.value.running || state.value.stopping || !state.value.settingsLoaded) return
        saveSettings(settings)
        service?.setMode(settings.mode)
    }
    private fun saveSettings(settings: ConversationSettings) {
        val value = settings.normalized()
        mutableState.update { it.copy(settings = value) }
        viewModelScope.launch {
            try { settingsStore.save(value) }
            catch (_: java.io.IOException) { error("Could not save conversation settings.") }
        }
    }
    internal fun applyMode(mode: ConversationMode) {
        if (state.value.settings.mode != mode) saveSettings(state.value.settings.copy(mode = mode))
    }
    internal fun controls(value: HandsFreeState) {
        muted = value.muted
        mutableState.update { it.copy(handsFree = value) }
        if (!value.shouldRun) {
            audioReady = false
            overResumeJob?.cancel(); over.stop()
            workout.stop(interrupted = true)
            gestures.disconnected(); reading.pause()
        }
        gemini.suspendAudio(!value.shouldRun || !audioReady)
    }
    fun setWorkoutSensitivity(value: Float) {
        if (!state.value.settingsLoaded || !value.isFinite()) return
        workout.setSensitivity(value)
        saveSettings(state.value.settings.copy(workoutSensitivity = value))
    }
    fun setIdleMinutes(value: Int) {
        if (state.value.settingsLoaded) saveSettings(state.value.settings.copy(idleMinutes = value))
    }
    fun arm() { service?.arm() }
    fun toggleInput() { service?.toggleInput() }
    fun start() { service?.startConversation() }
    fun stop() { service?.stopAll() }
    fun setTranslation(language: String?) {
        if (!canChangeTranslation()) return
        require(language == null || language in Translation.languages)
        saveSettings(state.value.settings.copy(translationLanguage = language))
        restartPersona()
    }
    fun setTranslationSpeaker(enabled: Boolean) {
        if (!canChangeTranslation()) return
        saveSettings(state.value.settings.copy(translationPhoneSpeaker = enabled))
        restartPersona()
    }
    private fun canChangeTranslation() = state.value.settingsLoaded && !state.value.stopping &&
        (!state.value.running || (state.value.connected && state.value.handsFree.shouldRun && audioReady))

    private fun restartPersona() {
        service?.activity()
        transcriptBoundary = true
        if (state.value.running) gemini.start(state.value.settings)
    }
    fun setMock(enabled: Boolean) {
        if (state.value.handsFree.armed || state.value.running || state.value.stopping) return
        if (glasses.setMock(enabled)) mutableState.update { it.copy(mock = enabled) }
    }

    internal fun startSession() {
        if (state.value.running || state.value.stopping || !state.value.permissions || !state.value.settingsLoaded) return
        if (!BuildConfig.DEBUG || BuildConfig.APP_SHARED_SECRET.isBlank()) {
            error("Set APP_SHARED_SECRET in android/local.properties and rebuild the debug app.")
            return
        }
        mutableState.update { it.copy(running = true, gemini = "Opening microphone…", error = null) }
        startJob = viewModelScope.launch {
            var sessionStarted = false
            state.map { it.handsFree.shouldRun }.distinctUntilChanged().collectLatest { allowed ->
                if (!allowed) return@collectLatest
                try {
                    val speechActivity = SpeechEnergyDetector()
                    var voicedSamples = 0
                    audio.start(forcePhone = state.value.mock, onAudio = { pcm ->
                        gemini.sendAudio(if (muted) ByteArray(pcm.size) else pcm)
                        over.audio(pcm)
                        // Over may have no transcript until sign-off; sustained speech is not idle.
                        if (!muted && speechActivity.accept(pcm)) {
                            voicedSamples += pcm.size / 2
                            if (voicedSamples >= 16_000) {
                                voicedSamples = 0
                                viewModelScope.launch {
                                    if (state.value.handsFree.shouldRun &&
                                        (state.value.settings.mode == ConversationMode.AUTO || state.value.talking)) service?.activity()
                                }
                            }
                        }
                    },
                        onRoute = { label -> mutableState.update { it.copy(route = label) } },
                        onError = { message -> viewModelScope.launch { error(message); stop() } },
                        onBluetoothLost = { service?.bluetoothLost() },
                    )
                    audioReady = true
                    gemini.suspendAudio(false)
                    service?.audioStarted()
                    if (!sessionStarted) {
                        memory.begin()
                        transcriptBoundary = true
                        gemini.start(state.value.settings)
                        sessionStarted = true
                    } else beginOverTurn()
                    awaitCancellation()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error(e.message ?: "Cannot start audio"); stop() }
                finally {
                    audioReady = false
                    gemini.suspendAudio(true)
                    overResumeJob?.cancel(); over.stop()
                    withContext(NonCancellable) { audio.stop() }
                    mutableState.update { it.copy(route = "Audio paused") }
                }
            }
        }
    }

    fun talk(pressed: Boolean) {
        if (!state.value.connected || !state.value.ptt || !state.value.handsFree.shouldRun) return
        service?.activity()
        screenTalking = pressed
        gemini.setTalking(screenTalking || glassesTalking)
    }
    private fun beginOverTurn() {
        if (!state.value.running || state.value.stopping || !state.value.connected ||
            !state.value.handsFree.shouldRun || !audioReady || overAwaitingReply ||
            state.value.settings.mode != ConversationMode.OVER) return
        gemini.setTalking(true)
        if (state.value.talking) over.start(state.value.settings, state.value.mock || state.value.route.startsWith("Phone"))
    }

    private fun sendGesture(text: String): Boolean {
        if (!state.value.handsFree.shouldRun || !audioReady) return false
        val sent = gemini.sendGestureText(text)
        if (state.value.settings.mode == ConversationMode.OVER && !state.value.talking) {
            overAwaitingReply = sent
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
        if (!enabled || (state.value.translationLanguage == null && state.value.connected && state.value.handsFree.shouldRun && audioReady)) vision.setWatchMode(enabled)
    }
    fun toggleGlassesTalking() {
        if (!state.value.connected || !state.value.ptt || !state.value.handsFree.shouldRun) return
        glassesTalking = !glassesTalking
        gemini.setTalking(glassesTalking || screenTalking)
    }

    internal fun stopSession() {
        if (state.value.stopping || !state.value.running) return
        mutableState.update { it.copy(stopping = true, connected = false, talking = false) }
        overResumeJob?.cancel(); over.stop()
        vision.stop()
        reading.clear()
        workout.stop(interrupted = true)
        gemini.stop()
        memory.stop()
        gestures.disconnected()
        screenTalking = false; glassesTalking = false
        stopJob = viewModelScope.launch {
            startJob?.cancelAndJoin(); startJob = null
            audio.stop()
            memory.flush()
            workout.flush()
            mutableState.update { it.copy(running = false, stopping = false, gemini = "Stopped", route = "No audio session") }
        }
    }

    private fun appendTranscript(speaker: String, text: String) {
        memory.append(if (speaker == "You") "user" else "model", text)
        service?.activity()
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
        vision.stop()
        reading.clear()
        service = null
        workout.stop(interrupted = true)
        gemini.close(); glasses.close()
        memory.stop()
        // viewModelScope is already cancelled; finish releasing platform audio on a cleanup scope.
        val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        cleanup.launch {
            stopJob?.join()
            memoryUploads.join()
            startJob?.join()
            audio.stop()
            memory.flush()
            workout.flush()
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
            cleanup.cancel()
        }
        super.onCleared()
    }
}
