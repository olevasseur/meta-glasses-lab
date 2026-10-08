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
    private val onClientTool: (String, JSONObject) -> Unit = { _, _ -> },
) {
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private val sendLock = Any()
    private val audioGeneration = AtomicInteger()
    private var socket: WebSocket? = null
    private var ready = false
    private var active = false
    private val meetingGate = MeetingGate()
    private val meetingAudio = MeetingAudioBuffer()
    private var wakeFallback: Job? = null
    private var replayingWake = false
    private var ptt = false
    private var talking = false
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

    fun start(pushToTalk: Boolean, meeting: Boolean = false, over: Boolean = false) {
        stop()
        meetingGate.configure(meeting, over)
        synchronized(sendLock) { active = true; ptt = pushToTalk && !meeting }
        reconnectJob = scope.launch { connect() }
    }

    private suspend fun connect() {
        if (!active) return
        onState(if (handle == null) "Connecting…" else "Resuming…", false)
        try {
            val reusable = token?.takeIf {
                handle != null && runCatching { Instant.parse(it.expiresAt).isAfter(Instant.now().plusSeconds(30)) }.getOrDefault(false)
            }
            val credentials = reusable ?: backend.token(ptt, meetingGate.active).also { token = it }
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
        if (event.resumable == true && event.handle != null) handle = event.handle
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
            meetingGate.input(it)
            wakeFallback?.cancel()
            if (meetingGate.active && meetingGate.mayRespond) {
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
            if (meetingGate.mayRespond && !event.interrupted && generation == audioGeneration.get()) event.audio.forEach(audio::enqueue)
        }
        event.outputText?.takeIf { it.isNotEmpty() && meetingGate.mayRespond }?.let { onTranscript("Gemini", it) }
        if (event.turnComplete || event.interrupted) onTurnEnd()
        event.cancelledIds.forEach { id ->
            cancelledCalls += id
            toolJobs.remove(id)?.cancel()
            pendingResponses -= id
        }
        event.calls.forEach { call ->
            if (meetingGate.mayRespond) callTool(call)
            else deliver(call, JSONObject().put("status", "ignored").put("message", "Meeting is silent; no direct address."))
        }
        if (event.turnComplete && !replayingWake) meetingGate.endTurn()
        if (event.goAway) reconnect("Gemini is renewing the connection")
    }

    private fun callTool(call: FunctionCall) {
        if (call.id in cancelledCalls) return
        responses[call.id]?.let { deliver(it.first, it.second); return }
        if (toolJobs.containsKey(call.id)) return
        onTool("${call.name}: running…")
        toolJobs[call.id] = scope.launch {
            val result = try {
                backend.tool(call)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Never automatically replay a POST whose side effects may already have happened.
                JSONObject().put("status", "error").put("message", "Tool request failed; outcome may be unknown. Ask the user before retrying.")
            }
            if (call.id !in cancelledCalls && active) {
                responses[call.id] = call to result
                onTool("${call.name}: $result")
                deliver(call, result)
                if (result.optString("status") == "ok" && result.has("client_action")) onClientTool(call.name, result)
            }
            toolJobs.remove(call.id)
        }
    }

    private fun deliver(call: FunctionCall, result: JSONObject) {
        val sent = synchronized(sendLock) { ready && socket?.send(LiveProtocol.toolResponse(call, result)) == true }
        if (sent) pendingResponses -= call.id else pendingResponses += call.id
    }

    fun sendText(text: String) {
        val content = JSONObject().put("turns", org.json.JSONArray().put(JSONObject().put("role", "user")
            .put("parts", org.json.JSONArray().put(JSONObject().put("text", text))))).put("turnComplete", true)
        synchronized(sendLock) { if (ready) socket?.send(JSONObject().put("clientContent", content).toString()) }
    }

    /** End the stream and allow final transcription to arrive before closing the socket. */
    fun finishInput() {
        synchronized(sendLock) {
            if (ready && !ptt) socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audioStreamEnd", true)).toString())
        }
    }

    fun sendAudio(pcm: ByteArray) = synchronized(sendLock) {
        if (active && !ready && meetingGate.active) meetingAudio.add(pcm)
        if (active && ready && (!ptt || talking)) {
            val ws = socket ?: return@synchronized
            // Don't accumulate stale microphone data behind a stalled connection.
            if (ws.queueSize() > (if (meetingGate.active) 2_000_000 else 256_000) || !ws.send(LiveProtocol.audio(pcm))) {
                ready = false
                scope.launch { reconnect("Audio uplink stalled") }
            }
        }
    }

    fun setTalking(value: Boolean) {
        val changed = synchronized(sendLock) {
            if (!active || !ready || !ptt || talking == value) false
            else if (socket?.send(LiveProtocol.activity(value)) == true) { talking = value; true }
            else false
        }
        if (!changed) return
        onTalking(value)
        talkTimeout?.cancel()
        if (value) {
            audio.flush()
            talkTimeout = scope.launch { delay(60_000); setTalking(false) }
        }
    }

    private fun reconnect(reason: String) {
        if (!active) return
        setupTimeout?.cancel()
        talkTimeout?.cancel()
        synchronized(sendLock) {
            ready = false; talking = false
            socket?.cancel(); socket = null
        }
        audio.flush()
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
            active = false; ready = false; talking = false
            meetingAudio.clear()
            socket?.close(1000, "User stopped"); socket = null
        }
        wakeFallback?.cancel(); replayingWake = false
        reconnectJob?.cancel(); reconnectJob = null
        setupTimeout?.cancel(); talkTimeout?.cancel()
        toolJobs.values.forEach { it.cancel() }; toolJobs.clear()
        responses.clear(); pendingResponses.clear(); cancelledCalls.clear()
        token = null; handle = null; attempts = 0
    }

    fun close() { stop(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}
