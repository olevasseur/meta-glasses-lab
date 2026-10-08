package com.olevasseur.glassesapp

import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

/** Lifecycle runs on Main; the audio thread only enters the synchronized send gate. */
class GeminiSession(
    private val scope: CoroutineScope,
    private val backend: BackendClient,
    private val audio: ConversationAudio,
    private val onState: (String, Boolean) -> Unit,
    private val onTranscript: (String, String) -> Unit,
    private val onTurnEnd: () -> Unit,
    private val onTool: (String) -> Unit,
    private val onTalking: (Boolean) -> Unit,
    private val onFatal: (String) -> Unit,
    private val onEvent: (ServerEvent) -> Unit = {},
    private val localTool: suspend (FunctionCall) -> ToolResult = { ToolResult(toolError("Client tool is unavailable")) },
    private val onTranslationChanged: (String?) -> Unit = {},
    private val onClientTool: (String, JSONObject) -> Unit = { _, _ -> },
) {
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private val sendLock = Any()
    private val audioGeneration = AtomicInteger()
    private var socket: WebSocket? = null
    private var ready = false
    private var active = false
    private var audioSuspended = false
    private val meetingGate = MeetingGate()
    private val meetingAudio = MeetingAudioBuffer()
    private var wakeFallback: Job? = null
    private var replayingWake = false
    private var ptt = false
    private var settings = ConversationSettings()
    private var talking = false
    private var replyPending = false
    private var token: LiveToken? = null
    private var handle: String? = null
    private var reconnectJob: Job? = null
    private var setupTimeout: Job? = null
    private var talkTimeout: Job? = null
    private var attempts = 0
    private val toolJobs = mutableMapOf<String, Job>()
    private val responses = mutableMapOf<String, Pair<FunctionCall, JSONObject>>()
    private val pendingResponses = mutableSetOf<String>()
    private val cancelledCalls = mutableSetOf<String>()
    private val translationLanguage: String? get() = settings.translationLanguage
    private val translationPhoneSpeaker: Boolean get() = settings.translationPhoneSpeaker
    private val translationBuffer = TranslationBuffer()

    fun start(pushToTalk: Boolean) {
        start(ConversationSettings(mode = if (pushToTalk) ConversationMode.PTT else ConversationMode.AUTO))
    }

    fun start(options: ConversationSettings, meeting: Boolean = false) {
        stop()
        synchronized(sendLock) {
            settings = options.normalized().let { if (meeting) it.copy(translationLanguage = null) else it }
            meetingGate.configure(meeting, settings.meetingOver)
            active = true; ptt = settings.mode != ConversationMode.AUTO && !meeting
        }
        if (!meeting) onTranslationChanged(translationLanguage)
        onTalking(false)
        onState("Switching session…", false)
        reconnectJob = scope.launch { connect() }
    }

    private suspend fun connect() {
        if (!active) return
        onState(if (handle == null) "Connecting…" else "Resuming…", false)
        try {
            val reusable = token?.takeIf {
                handle != null && runCatching { Instant.parse(it.expiresAt).isAfter(Instant.now().plusSeconds(30)) }.getOrDefault(false)
            }
            val credentials = reusable ?: backend.token(settings.mode, settings.silenceMs, translationLanguage, meetingGate.active).also { token = it }
            if (!active) return
            val request = Request.Builder().url(LiveProtocol.ENDPOINT)
                .header("Authorization", "Token ${credentials.token}").build()
            synchronized(sendLock) { socket = client.newWebSocket(request, listener(credentials.model)) }
            setupTimeout = scope.launch {
                delay(20_000)
                if (!ready) reconnect("Gemini setup timed out")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            reconnect("Cannot get backend token; check backend and shared secret")
        }
    }

    private fun listener(model: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            scope.launch {
                if (webSocket === socket && active) webSocket.send(LiveProtocol.setup(model, handle))
                else webSocket.close(1000, "Stopped")
            }
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = try { LiveProtocol.parse(text) } catch (_: Exception) {
                scope.launch { if (webSocket === socket && active) onFatal("Invalid Gemini message; stop and retry") }
                return
            }
            val generation = synchronized(sendLock) {
                if (webSocket !== socket || !active) return
                if (event.interrupted) {
                    audio.flush()
                    audioGeneration.incrementAndGet()
                } else audioGeneration.get()
            }
            // Flush on the socket callback, before waiting for the UI thread. The generation
            // also invalidates audio events already queued for delivery to that thread.
            scope.launch {
                if (webSocket !== socket || !active) return@launch
                try { receive(event, generation) }
                catch (_: Exception) { onFatal("Invalid Gemini message or audio; stop and retry") }
            }
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onMessage(webSocket, bytes.utf8())
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            scope.launch {
                if (webSocket !== socket || !active) return@launch
                if (meetingGate.active && handle != null && (code == 1007 || code == 1008)) {
                    handle = null; token = null
                    onTranscript("You", " [Capture note: Live context could not resume; starting fresh while retaining the saved meeting transcript.] ")
                    reconnect("Starting a fresh meeting connection")
                } else if (code == 1007 || code == 1008) onFatal("Gemini rejected setup ($code); check token constraints and resumption field mask")
                else reconnect("Gemini disconnected ($code)")
            }
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch { if (webSocket === socket && active) reconnect("Gemini disconnected ($code)") }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scope.launch {
                if (webSocket !== socket || !active) return@launch
                if (response?.code == 401 || response?.code == 403) token = null
                reconnect("Gemini connection lost")
            }
        }
    }

    private fun receive(event: ServerEvent, generation: Int) {
        event.error?.let { onFatal("Gemini reported an API error; check the backend session configuration"); return }
        if (!event.inputText.isNullOrEmpty() || !event.outputText.isNullOrEmpty() || event.audio.isNotEmpty() || event.calls.isNotEmpty()) replyPending = true
        if (event.turnComplete || event.interrupted) replyPending = false
        onEvent(event)
        if (event.resumable == true && event.handle != null) {
            handle = event.handle
            DiagnosticMetrics.handleUpdatedMs = android.os.SystemClock.elapsedRealtime()
        } else if (event.resumable == false) DiagnosticMetrics.handleUpdatedMs = null
        if (!audioSuspended && !event.interrupted && event.audio.isNotEmpty()) DiagnosticMetrics.response()
        if (event.setupComplete) {
            setupTimeout?.cancel()
            attempts = 0
            synchronized(sendLock) {
                ready = true
                if (meetingGate.active) {
                    val dropped = meetingAudio.droppedBytes
                    meetingAudio.drain().forEach { socket?.send(LiveProtocol.audio(it)) }
                    meetingAudio.clear()
                    if (dropped > 0) onTranscript("You", " [Capture gap: ${dropped / 32_000} seconds of audio lost during disconnection.] ")
                }
            }
            onState("Connected", true)
            pendingResponses.toList().forEach { id -> responses[id]?.let { deliver(it.first, it.second) } }
        }
        if (event.interrupted && !replayingWake) { wakeFallback?.cancel(); meetingGate.endTurn() }
        event.inputText?.takeIf { it.isNotEmpty() }?.let {
            // Late transcription during a call/final drain is archived, but cannot
            // reopen permission to speak or execute tools after audio resumes.
            if (!audioSuspended) meetingGate.input(it)
            wakeFallback?.cancel()
            if (meetingGate.active && meetingGate.mayRespond && !audioSuspended) {
                val request = meetingGate.requestText
                wakeFallback = scope.launch {
                    delay(2_000)
                    // The scribe sometimes ignores even a recognized address. Explicit text
                    // ends the turn; late/missing input transcription still fails closed.
                    replayingWake = true
                    meetingGate.input(request)
                    sendText("Assistant, respond to this directly addressed request, using tools if needed: " + JSONObject.quote(request))
                    delay(30_000)
                    replayingWake = false
                    meetingGate.endTurn()
                }
            }
            onTranscript("You", it)
        }
        if (meetingGate.active && meetingGate.mayRespond &&
            (event.audio.sumOf { it.size } > 1024 || !event.outputText.isNullOrEmpty() || event.calls.isNotEmpty())) {
            wakeFallback?.cancel()
            replayingWake = false
        }
        // Interruption wins over stale audio if both ever occur in the same event.
        synchronized(sendLock) {
            if (generation == audioGeneration.get()) {
                if (event.interrupted) {
                    translationBuffer.clear()
                    audio.translationSpeaker.stop()
                } else if (translationLanguage != null && translationPhoneSpeaker) {
                    translationBuffer.accept(event, translationLanguage!!, audioSuspended)?.let { utterance ->
                        if (utterance.phone && utterance.pcm.isNotEmpty()) {
                            audio.flush()
                            if (ptt) setTalking(false)
                            else socket?.send("""{"realtimeInput":{"audioStreamEnd":true}}""")
                            audio.translationSpeaker.play(utterance.pcm) {
                                onFatal("Phone translation playback failed; stop and retry with phone speaker off")
                            }
                        } else audio.enqueue(utterance.pcm)
                    }
                } else if (!audioSuspended && meetingGate.mayRespond) event.audio.forEach(audio::enqueue)
            }
        }
        event.outputText?.takeIf { it.isNotEmpty() && meetingGate.mayRespond }?.let { onTranscript("Gemini", it) }
        if (event.turnComplete || event.interrupted) { DiagnosticMetrics.latency.reset(); onTurnEnd() }
        event.cancelledIds.forEach { id ->
            cancelledCalls += id
            toolJobs.remove(id)?.cancel()
            pendingResponses -= id
        }
        event.calls.forEach { call ->
            if (meetingGate.mayRespond && (!meetingGate.active || !audioSuspended)) callTool(call)
            else deliver(call, JSONObject().put("status", "ignored").put("message", "Meeting is silent; no direct address."))
        }
        if (event.turnComplete && !replayingWake) meetingGate.endTurn()
        if (event.goAway) reconnect("Gemini is renewing the connection")
    }

    private fun callTool(call: FunctionCall) {
        if (call.id in cancelledCalls) return
        responses[call.id]?.let { deliver(it.first, it.second); return }
        if (toolJobs.containsKey(call.id)) return
        logDiagnosticTool(call.name, finished = false)
        onTool("${call.name}: running…")
        toolJobs[call.id] = scope.launch {
            val result = try {
                if (meetingGate.active && call.name in setOf("start_translation", "stop_translation")) {
                    ToolResult(toolError("Stop the meeting before starting translation"))
                } else if (translationLanguage != null && call.name !in setOf("start_translation", "stop_translation")) {
                    ToolResult(toolError("Leave translation mode to use assistant tools"))
                } else routeTool(call, token?.clientTools.orEmpty(), localTool, backend::tool)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Never automatically replay a POST whose side effects may already have happened.
                ToolResult(toolError("Tool request failed; outcome may be unknown. Ask the user before retrying."))
            }
            if (call.id !in cancelledCalls && active) {
                // Retain no captured images across reconnects or duplicate calls. A lost visual
                // response needs a fresh look, not a cached success describing an old frame.
                responses[call.id] = call to if (result.jpeg == null) result.response
                    else toolError("This visual response cannot be replayed; request a fresh look if needed.")
                logDiagnosticTool(call.name, finished = true, failed = result.response.has("error") || result.response.optString("status") == "error")
                onTool("${call.name}: ${result.response}")
                deliver(call, result.response, result.jpeg)
                if (result.response.optString("status") == "ok" && result.response.has("client_action")) {
                    onClientTool(call.name, result.response)
                }
                if (result.response.optString("status") == "ok" && call.name in setOf("start_translation", "stop_translation")) {
                    val language = Translation.switchedLanguage(call, result.response)
                    // Fresh token/context; stop clears old tool jobs, handles and playback.
                    start(settings.copy(translationLanguage = language))
                    return@launch
                }
            }
            toolJobs.remove(call.id)
        }
    }

    private fun deliver(call: FunctionCall, result: JSONObject, jpeg: ByteArray? = null) {
        val sent = synchronized(sendLock) { ready && socket?.send(LiveProtocol.toolResponse(call, result, jpeg)) == true }
        if (sent) pendingResponses -= call.id else pendingResponses += call.id
    }

    fun sendGestureText(text: String): Boolean {
        var endedActivity = false
        val sent = synchronized(sendLock) {
            val ws = socket
            if (!active || !ready || audioSuspended || meetingGate.active || translationLanguage != null ||
                audio.translationSpeaker.playing || ws == null || ws.queueSize() > 256_000) return@synchronized false
            sendGestureTurn(text, settings.mode, talking, ws::send) {
                talking = false
                endedActivity = true
            }
        }
        if (endedActivity) onTalking(false)
        if (sent) replyPending = true
        return sent
    }

    fun canNavigateCoach(): Boolean = synchronized(sendLock) {
        active && ready && !audioSuspended && !meetingGate.active && translationLanguage == null &&
            !replyPending && audio.playbackIdle && !audio.translationSpeaker.playing &&
            (!talking || settings.mode == ConversationMode.OVER) &&
            socket?.let { it.queueSize() <= 256_000 } == true
    }

    fun sendJobEvent(event: JobEvent): Boolean = sendIdleText(LiveProtocol.jobEvent(event))

    fun sendWorkoutCue(text: String): Boolean = sendIdleText(gestureTextTurn(text))

    private fun sendIdleText(message: String): Boolean = synchronized(sendLock) {
        val ws = socket
        // An automatic cue must not end a manual turn, interrupt a reply, or speak during a call.
        if (!active || !ready || audioSuspended || meetingGate.active || translationLanguage != null || talking || replyPending || !audio.playbackIdle ||
            ws == null || ws.queueSize() > 256_000) return@synchronized false
        val sent = ws.send(message)
        if (sent) replyPending = true
        sent
    }

    fun sendRecap(text: String): Boolean = sendIdleText(gestureTextTurn(text))

    private fun sendText(text: String): Boolean = synchronized(sendLock) {
        active && ready && !audioSuspended && socket?.send(gestureTextTurn(text)) == true
    }

    /** Stop input while retaining the socket for final transcript delivery. */
    fun finishInput() = synchronized(sendLock) {
        if (ready && !ptt) socket?.send("""{"realtimeInput":{"audioStreamEnd":true}}""")
    }

    fun sendAudio(pcm: ByteArray) = synchronized(sendLock) {
        if (active && !ready && !audioSuspended && meetingGate.active) meetingAudio.add(pcm)
        if (active && ready && !audioSuspended && !audio.translationSpeaker.playing && (!ptt || talking)) {
            DiagnosticMetrics.latency.input(pcm)
            val ws = socket ?: return@synchronized
            // Don't accumulate stale microphone data behind a stalled connection.
            if (ws.queueSize() > (if (meetingGate.active) 2_000_000 else 1_000_000) || !ws.send(LiveProtocol.audio(pcm))) {
                if (meetingGate.active) meetingAudio.add(pcm)
                ready = false
                scope.launch { reconnect("Audio uplink stalled") }
            }
        }
    }

    fun sendImage(jpeg: ByteArray): Boolean = synchronized(sendLock) {
        val ws = socket
        // Drop frames under backpressure; never queue a camera backlog behind audio.
        active && ready && !audioSuspended && meetingGate.mayRespond && translationLanguage == null &&
            ws != null && ws.queueSize() < 64_000 && ws.send(LiveProtocol.image(jpeg))
    }

    fun suspendAudio(suspended: Boolean) {
        // Keep an Over activity open across interruptions; a call is not a sign-off.
        if (suspended && settings.mode != ConversationMode.OVER) setTalking(false)
        synchronized(sendLock) {
            if (audioSuspended == suspended) return
            audioSuspended = suspended
            DiagnosticMetrics.latency.reset()
            audioGeneration.incrementAndGet()
            if (suspended) {
                wakeFallback?.cancel(); replayingWake = false
                meetingGate.endTurn()
                meetingAudio.clear()
                audio.flush()
                if (replyPending) translationBuffer.discardTurn() else translationBuffer.clear()
                audio.translationSpeaker.stop()
            }
        }
    }

    fun setTalking(value: Boolean) {
        val changed = synchronized(sendLock) {
            if (!active || !ready || !ptt || (value && (audioSuspended || audio.translationSpeaker.playing)) || talking == value) false
            else if (socket?.send(LiveProtocol.activity(value)) == true) { talking = value; true }
            else false
        }
        if (!changed) return
        if (!value) replyPending = true
        onTalking(value)
        talkTimeout?.cancel()
        if (value) {
            audio.flush()
            if (settings.mode == ConversationMode.PTT) {
                talkTimeout = scope.launch { delay(60_000); setTalking(false) }
            }
        }
    }

    private fun reconnect(reason: String) {
        if (!active) return
        setupTimeout?.cancel()
        talkTimeout?.cancel()
        synchronized(sendLock) {
            ready = false; talking = false; replyPending = false
            socket?.cancel(); socket = null
        }
        audio.flush()
        audio.translationSpeaker.stop()
        translationBuffer.clear()
        wakeFallback?.cancel(); replayingWake = false
        meetingGate.endTurn()
        onTalking(false)
        if (++attempts > 8) { onFatal("$reason. Reconnect limit reached; tap Start to retry."); return }
        onState("$reason · reconnecting${if (handle == null) " (new session)" else ""}", false)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(minOf(1000L shl (attempts - 1), 15_000L))
            connect()
        }
    }

    fun stop() {
        synchronized(sendLock) {
            if (talking && ready) socket?.send(LiveProtocol.activity(false))
            active = false; ready = false; talking = false; replyPending = false
            meetingAudio.clear()
            meetingGate.configure(false)
            socket?.close(1000, "User stopped"); socket = null
        }
        wakeFallback?.cancel(); replayingWake = false
        reconnectJob?.cancel(); reconnectJob = null
        setupTimeout?.cancel(); talkTimeout?.cancel()
        toolJobs.values.forEach { it.cancel() }; toolJobs.clear()
        responses.clear(); pendingResponses.clear(); cancelledCalls.clear()
        token = null; handle = null; attempts = 0
        DiagnosticMetrics.handleUpdatedMs = null
        DiagnosticMetrics.latency.reset()
        DiagnosticMetrics.latencyMs = null
        audioGeneration.incrementAndGet()
        translationBuffer.clear()
        audio.translationSpeaker.stop()
        audio.flush()
    }

    fun close() { stop(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}
