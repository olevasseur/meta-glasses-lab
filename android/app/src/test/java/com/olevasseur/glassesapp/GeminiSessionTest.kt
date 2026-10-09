package com.olevasseur.glassesapp

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiSessionTest {
    private class Audio : LiveAudio {
        val chunks = mutableListOf<ByteArray>()
        var flushFailure = false
        var flushes = 0
        override val translationSpeaker = object : TranslationPlayback {
            override val playing = false
            override fun play(pcm: ByteArray, onError: () -> Unit) = Unit
            override fun stop() = Unit
        }
        override val playbackIdle get() = chunks.isEmpty()
        override fun enqueue(pcm: ByteArray) { chunks += pcm }
        override fun flush() { flushes++; chunks.clear(); check(!flushFailure) }
    }

    private class Socket(private val request: Request, val listener: WebSocketListener) : WebSocket {
        val sent = mutableListOf<String>()
        var queued = 0L
        var cancelled = false
        override fun request() = request
        override fun queueSize() = queued
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?): Boolean { cancelled = true; return true }
        override fun cancel() { cancelled = true }
        fun receive(text: String) = listener.onMessage(this, text)
        fun open() = listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols").build())
    }

    private class Fixture(scope: CoroutineScope, local: suspend (FunctionCall) -> ToolResult = { ToolResult(JSONObject()) },
        action: (GeminiSession) -> Unit = {}, canAnnounce: () -> Boolean = { true }) : Closeable {
        val audio = Audio()
        val errors = mutableListOf<String>()
        val backendCalls = AtomicInteger()
        val tokenRequests = mutableListOf<JSONObject>()
        private val created = Channel<Socket>(Channel.UNLIMITED)
        private val http = OkHttpClient.Builder().addInterceptor { chain ->
            val token = chain.request().url.encodedPath == "/token"
            if (!token && chain.request().url.encodedPath != "/usage/live") backendCalls.incrementAndGet()
            if (token) { val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer); tokenRequests += JSONObject(buffer.readUtf8()) }
            val body = if (token) """{"token":"test","model":"gemini-3.8-live","expires_at":"2099-01-01T00:00:00Z"}"""
                else """{"status":"ok"}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val session: GeminiSession = GeminiSession(scope, BackendClient(http, "http://localhost/", "fixture"), audio,
            { _, _ -> }, { _, _ -> }, {}, {}, {}, { errors += it; sessionRef().stop() }, localTool = local,
            onClientTool = { _, _ -> action(sessionRef()) },
            canAnnounce = canAnnounce,
            elapsedMs = { 0L }, retryDelay = { 1L },
            sockets = WebSocket.Factory { request, listener -> Socket(request, listener).also { created.trySend(it) } })
        private fun sessionRef() = session
        suspend fun connected(meeting: Boolean = false, settings: ConversationSettings = ConversationSettings()): Socket {
            session.start(settings, meeting)
            return nextSocket()
        }
        suspend fun nextSocket(): Socket {
            val socket = withTimeout(5_000) { created.receive() }
            socket.open(); yield()
            socket.receive("""{"setupComplete":{}}"""); yield()
            return socket
        }
        override fun close() {
            audio.flushFailure = false
            session.close()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    private fun tool(id: String = "1", name: String = "remember") =
        """{"toolCall":{"functionCalls":[{"id":"$id","name":"$name","args":{}}]}}"""
    private val audioMessage = """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQI="}}]}}}"""

    @Test fun overSignOffImmediatelySendsEndAndRejectsRemainderUntilNextStart() = runBlocking {
        Fixture(this).use { f ->
            val socket = f.connected(settings = ConversationSettings(mode = ConversationMode.OVER))
            val cursor = DiagnosticLog.ring.after(0, 500).lastOrNull()?.sequence ?: 0
            var now = 0L
            lateinit var recognized: (String, Boolean) -> Unit
            val controller = OverModeController(this, { text, _ ->
                recognized = text
                object : OverSpeechInput {
                    override fun startOnDevice() = Unit
                    override fun audio(pcm: ByteArray) = Unit
                    override fun stop() { fail("Sign-off must not wait for recognizer shutdown") }
                }
            }, { f.session.setTalking(false) }, {}, { now })
            repeat(2) {
                f.session.setTalking(true)
                controller.start(ConversationSettings(mode = ConversationMode.OVER))
                f.session.sendAudio(byteArrayOf(1, 2))
                val before = socket.sent.size
                recognized("over", true)
                now += 1200
                controller.tick()
                // No yield, recognizer cleanup, transcription or model event needed to send end.
                assertEquals(before + 1, socket.sent.size)
                assertTrue(socket.sent.last().contains("activityEnd"))
                f.session.sendAudio(byteArrayOf(3, 4))
                f.session.setTalking(false)
                assertEquals(before + 1, socket.sent.size)
                socket.receive("""{"serverContent":{"inputTranscription":{"text":"private phrase"}}}""")
                socket.receive(audioMessage)
                socket.receive("""{"serverContent":{"turnComplete":true}}""")
                yield()
            }
            val input = socket.sent.map { JSONObject(it).optJSONObject("realtimeInput") }.filterNotNull()
            assertEquals(listOf("activityStart", "audio", "activityEnd", "activityStart", "audio", "activityEnd"),
                input.map { it.keys().next() })
            val logs = DiagnosticLog.ring.after(cursor).filter { it.feature == "live-turn" }
            for (event in listOf("activityStart_sent", "activityEnd_sent", "first_inputTranscription",
                "first_model_content", "first_audio", "turnComplete")) {
                assertEquals(event, 2, logs.count { it.event.contains("event=$event ") })
            }
            assertFalse(logs.any { it.event.contains("private phrase") })
            assertTrue(logs.all { it.timestamp.isNotBlank() && it.event.contains("monotonic_ms=") })
        }
    }

    @Test fun meetingInteractionUsesNormalTokenAndRejectsTranslationSwitch() = runBlocking {
        Fixture(this).use { f ->
            f.session.start(ConversationSettings(), meetingInteraction = true)
            val socket = f.nextSocket()
            assertEquals("auto", f.tokenRequests.single().getString("mode"))
            socket.receive(tool(name = "start_translation")); yield(); yield()
            assertEquals(0, f.backendCalls.get())
            assertTrue(socket.sent.any { it.contains("Stop the meeting") })
            socket.receive(audioMessage); yield()
            assertEquals(1, f.audio.chunks.size) // Addressed short session has ordinary playback.
        }
    }

    @Test fun pausedSessionRejectsRemoteAndLocalToolsAndAudio() = runBlocking {
        var localCalls = 0
        Fixture(this, { localCalls++; ToolResult(JSONObject()) }).use { f ->
            val socket = f.connected()
            f.session.suspendAudio(true)
            socket.receive(tool())
            socket.receive(tool("2", "look"))
            socket.receive(audioMessage)
            yield()
            assertEquals(0, f.backendCalls.get())
            assertEquals(0, localCalls)
            assertTrue(f.audio.chunks.isEmpty())
            assertEquals(2, socket.sent.count { it.contains("ignored") })
            f.session.suspendAudio(false)
            socket.receive(tool("3", "look")); yield(); yield()
            assertEquals(1, localCalls)
        }
    }

    @Test fun interruptionFlushesImmediatelyAndInvalidatesAlreadyQueuedAudio() = runBlocking {
        Fixture(this).use { f ->
            val socket = f.connected()
            val before = f.audio.flushes
            socket.receive(audioMessage) // Queued for Main, not yet played.
            socket.receive("""{"serverContent":{"interrupted":true}}""")
            assertEquals(before + 1, f.audio.flushes)
            yield()
            assertTrue(f.audio.chunks.isEmpty())
            socket.receive(audioMessage); yield()
            assertEquals(1, f.audio.chunks.size)
        }
    }

    @Test fun flushFailureInSocketCallbackIsReportedOnOwnerInsteadOfEscaping() = runBlocking {
        Fixture(this).use { f ->
            val socket = f.connected()
            f.audio.flushFailure = true
            socket.receive("""{"serverContent":{"interrupted":true}}""")
            yield()
            assertEquals(listOf("Audio interruption failed; stop and retry"), f.errors)
        }
    }

    @Test fun queuedUplinkFailureCannotCancelReplacementSession() = runBlocking {
        Fixture(this).use { f ->
            val old = f.connected()
            old.queued = 2_000_000
            f.session.sendAudio(byteArrayOf(1, 2)) // Queues a reconnect from the capture thread.
            f.session.start(ConversationSettings()) // User switches mode before that callback runs.
            val replacement = f.nextSocket()
            f.session.sendAudio(byteArrayOf(1, 2))
            assertFalse(replacement.cancelled)
            assertTrue(replacement.sent.any { it.contains("audio/pcm;rate=16000") })
            old.receive(audioMessage); yield()
            assertTrue(f.audio.chunks.isEmpty())
        }
    }

    @Test fun pauseFlushFailureStillStopsSocketWithoutRecursiveFatalOrThrow() = runBlocking {
        Fixture(this).use { f ->
            val socket = f.connected()
            f.audio.flushFailure = true
            f.session.suspendAudio(true)
            assertTrue(socket.cancelled)
            assertEquals(listOf("Speaker reset failed; stop and retry"), f.errors)
        }
    }

    @Test fun immediateToolSessionSwitchCannotLeaveOldJobBlockingNewCallId() = runBlocking {
        var calls = 0
        val immediate = CoroutineScope(coroutineContext + Dispatchers.Unconfined)
        Fixture(immediate, {
            calls++
            ToolResult(JSONObject().put("status", "ok").apply { if (calls == 1) put("client_action", "restart") })
        }, { it.start(ConversationSettings()) }).use { f ->
            val old = f.connected()
            old.receive(tool("same-id", "set_watch_mode"))
            val replacement = f.nextSocket()
            replacement.receive(tool("same-id", "set_watch_mode"))
            assertEquals(2, calls)
        }
    }

    @Test fun silentMeetingRejectsToolsUntilDirectlyAddressed() = runBlocking {
        var calls = 0
        Fixture(this, { calls++; ToolResult(JSONObject()) }).use { f ->
            val socket = f.connected(meeting = true)
            socket.receive(tool("1", "look")); yield(); yield()
            assertEquals(0, calls)
            socket.receive("""{"serverContent":{"inputTranscription":{"text":"Hey assistant, look"}}}""")
            socket.receive(tool("2", "look")); yield(); yield()
            assertEquals(1, calls)
            f.session.suspendAudio(true)
            socket.receive(tool("3", "look")); yield(); yield()
            assertEquals(1, calls)
        }
    }
    @Test fun transientClosesKeepRetryingBeyondOldLimitAndRequestFallback() = runBlocking {
        Fixture(this).use { f ->
            var socket = f.connected()
            repeat(12) {
                socket.listener.onClosing(socket, 1011, "Internal error encountered")
                socket = f.nextSocket()
            }
            assertTrue(f.errors.isEmpty())
            assertTrue(f.tokenRequests.any { it.optJSONArray("avoid_models")?.length() == 1 })
            f.session.stop()
            socket.listener.onClosed(socket, 1011, "Internal error encountered")
            yield()
            assertTrue(f.errors.isEmpty())
        }
    }
    @Test fun realPolicyConfigurationRejectionIsFatalButQuotaIsRecoverable() = runBlocking {
        Fixture(this).use { f ->
            val first = f.connected()
            first.listener.onClosing(first, 1008, "RESOURCE_EXHAUSTED quota")
            val next = f.nextSocket()
            assertTrue(f.errors.isEmpty())
            next.listener.onClosing(next, 1008, "Invalid token constraints fieldMask")
            yield()
            assertEquals(1, f.errors.size)
            assertTrue(f.errors.single().contains("configuration"))
        }
    }

    @Test fun validHandleSurvivesTransientCloseAndExpiredTokenRefresh() = runBlocking {
        Fixture(this).use { f ->
            val first = f.connected()
            first.receive("""{"sessionResumptionUpdate":{"resumable":true,"newHandle":"keep-me"}}""")
            yield()
            first.listener.onClosing(first, 1011, "Internal error encountered")
            val second = f.nextSocket()
            assertTrue(second.sent.first().contains("keep-me"))
            assertEquals(1, f.tokenRequests.size)
            second.listener.onClosing(second, 1008, "token expired")
            val third = f.nextSocket()
            assertTrue(third.sent.first().contains("keep-me"))
            assertEquals(2, f.tokenRequests.size)
            third.receive("""{"sessionResumptionUpdate":{"resumable":false}}"""); yield()
            third.listener.onClosing(third, 1011, "Internal error encountered")
            assertFalse(f.nextSocket().sent.first().contains("keep-me"))
        }
    }

    @Test fun recoveredOfflineRecapWaitsForPendingHumanDecision() = runBlocking {
        var pendingDecision = true
        Fixture(this, canAnnounce = { !pendingDecision }).use { f ->
            val first = f.connected()
            first.listener.onClosing(first, 1011, "Internal error encountered")
            val recovered = f.nextSocket()
            val before = recovered.sent.size
            assertFalse(f.session.sendRecap("[while you were offline: saved note]"))
            assertFalse(f.session.sendWorkoutCue("rep 5"))
            assertEquals(before, recovered.sent.size)
            pendingDecision = false
            assertTrue(f.session.sendRecap("[while you were offline: saved note]"))
            assertFalse(f.session.sendRecap("another recap"))
            assertEquals(before + 1, recovered.sent.size)
        }
    }

    @Test fun offlineRecapUsesOverTurnBoundaryWithoutEndingUserSpeech() = runBlocking {
        Fixture(this).use { f ->
            val socket = f.connected(settings = ConversationSettings(mode = ConversationMode.OVER))
            f.session.setTalking(true)
            val before = socket.sent.size
            assertFalse(f.session.sendRecap("offline recap"))
            assertEquals(before, socket.sent.size)
            f.session.setTalking(false)
            assertFalse(f.session.sendRecap("offline recap"))
            socket.receive("""{"serverContent":{"turnComplete":true}}"""); yield()
            assertTrue(f.session.sendRecap("offline recap"))
            assertTrue(socket.sent.last().contains("offline recap"))
        }
    }

    @Test fun warmSetupStaysMutedUntilAudioReadyAndKeepsSessionBetweenTurns() = runBlocking {
        Fixture(this).use { f ->
            f.session.start(ConversationSettings())
            f.session.suspendAudio(true)
            val socket = f.nextSocket()
            f.session.sendAudio(byteArrayOf(1, 2))
            assertFalse(f.session.sendRecap("saved offline"))
            socket.receive(audioMessage); yield()
            assertTrue(f.audio.chunks.isEmpty())
            assertFalse(socket.sent.any { it.contains("audio/pcm;rate=16000") })
            f.session.suspendAudio(false)
            repeat(2) {
                f.session.sendAudio(byteArrayOf(1, 2))
                socket.receive(audioMessage); yield()
                assertTrue(f.audio.chunks.isNotEmpty()) // No turnComplete needed to start playback.
                socket.receive("""{"serverContent":{"turnComplete":true}}"""); yield()
            }
            assertEquals(1, f.tokenRequests.size)
            assertFalse(socket.cancelled)
            f.audio.flush()
            assertTrue(f.session.sendRecap("saved offline"))
        }
    }

    @Test fun independentToolsStartBeforeEitherFinishes() = runBlocking {
        val started = Channel<String>(Channel.UNLIMITED)
        val release = Channel<Unit>()
        Fixture(this, { call ->
            started.send(call.id)
            release.receive()
            ToolResult(JSONObject().put("status", "ok"))
        }).use { f ->
            val socket = f.connected()
            socket.receive(tool("a", "look"))
            socket.receive(tool("b", "look"))
            withTimeout(1000) { assertEquals(setOf("a", "b"), setOf(started.receive(), started.receive())) }
            repeat(2) { release.send(Unit) }
            yield()
            assertEquals(2, socket.sent.count { it.contains("functionResponses") })
        }
    }

}
