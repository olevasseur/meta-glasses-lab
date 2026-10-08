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
    private val localTool: suspend (FunctionCall) -> ToolResult = { ToolResult(toolError("Vision is unavailable")) },
) {
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private val sendLock = Any()
    private val audioGeneration = AtomicInteger()
    private var socket: WebSocket? = null
    private var ready = false
    private var active = false
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

    fun start(pushToTalk: Boolean) {
        stop()
        synchronized(sendLock) { active = true; ptt = pushToTalk }
        reconnectJob = scope.launch { connect() }
    }

    private suspend fun connect() {
        if (!active) return
        onState(if (handle == null) "Connecting…" else "Resuming…", false)
        try {
            val reusable = token?.takeIf {
                handle != null && runCatching { Instant.parse(it.expiresAt).isAfter(Instant.now().plusSeconds(30)) }.getOrDefault(false)
            }
            val credentials = reusable ?: backend.token(ptt).also { token = it }
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
                if (code == 1007 || code == 1008) onFatal("Gemini rejected setup ($code); check token constraints and resumption field mask")
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
            synchronized(sendLock) { ready = true }
            onState("Connected", true)
            pendingResponses.toList().forEach { id -> responses[id]?.let { deliver(it.first, it.second) } }
        }
        // Interruption wins over stale audio if both ever occur in the same event.
        synchronized(sendLock) {
            if (!event.interrupted && generation == audioGeneration.get()) event.audio.forEach(audio::enqueue)
        }
        event.inputText?.takeIf { it.isNotEmpty() }?.let { onTranscript("You", it) }
        event.outputText?.takeIf { it.isNotEmpty() }?.let { onTranscript("Gemini", it) }
        if (event.turnComplete || event.interrupted) onTurnEnd()
        event.cancelledIds.forEach { id ->
            cancelledCalls += id
            toolJobs.remove(id)?.cancel()
            pendingResponses -= id
        }
        event.calls.forEach(::callTool)
        if (event.goAway) reconnect("Gemini is renewing the connection")
    }

    private fun callTool(call: FunctionCall) {
        if (call.id in cancelledCalls) return
        responses[call.id]?.let { deliver(it.first, it.second); return }
        if (toolJobs.containsKey(call.id)) return
        onTool("${call.name}: running…")
        toolJobs[call.id] = scope.launch {
            val result = try {
                routeTool(call, token?.clientTools.orEmpty(), localTool, backend::tool)
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
                onTool("${call.name}: ${result.response}")
                deliver(call, result.response, result.jpeg)
            }
            toolJobs.remove(call.id)
        }
    }

    private fun deliver(call: FunctionCall, result: JSONObject, jpeg: ByteArray? = null) {
        val sent = synchronized(sendLock) { ready && socket?.send(LiveProtocol.toolResponse(call, result, jpeg)) == true }
        if (sent) pendingResponses -= call.id else pendingResponses += call.id
    }

    fun sendAudio(pcm: ByteArray) = synchronized(sendLock) {
        if (active && ready && (!ptt || talking)) {
            val ws = socket ?: return@synchronized
            // Don't accumulate stale microphone data behind a stalled connection.
            if (ws.queueSize() > 1_000_000 || !ws.send(LiveProtocol.audio(pcm))) {
                ready = false
                scope.launch { reconnect("Audio uplink stalled") }
            }
        }
    }

    fun sendImage(jpeg: ByteArray): Boolean = synchronized(sendLock) {
        val ws = socket
        // Drop frames under backpressure; never queue a camera backlog behind audio.
        active && ready && ws != null && ws.queueSize() < 64_000 && ws.send(LiveProtocol.image(jpeg))
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
            socket?.close(1000, "User stopped"); socket = null
        }
        reconnectJob?.cancel(); reconnectJob = null
        setupTimeout?.cancel(); talkTimeout?.cancel()
        toolJobs.values.forEach { it.cancel() }; toolJobs.clear()
        responses.clear(); pendingResponses.clear(); cancelledCalls.clear()
        token = null; handle = null; attempts = 0
    }

    fun close() { stop(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}
