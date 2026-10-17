package com.olevasseur.glassesapp

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
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
        var rejectTools = false
        override fun request() = request
        override fun queueSize() = queued
        override fun send(text: String): Boolean {
            if (rejectTools && text.contains("functionResponses")) return false
            sent += text; return true
        }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?): Boolean { cancelled = true; return true }
        override fun cancel() { cancelled = true }
        fun receive(text: String) = listener.onMessage(this, text)
        fun open() = listener.onOpen(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(101).message("Switching Protocols").build())
    }

    private class Fixture(scope: CoroutineScope, local: suspend (FunctionCall) -> ToolResult = { ToolResult(JSONObject()) },
        action: (GeminiSession) -> Unit = {}, canAnnounce: () -> Boolean = { true },
        event: (ServerEvent) -> Unit = {}, clock: () -> Long = { 0L },
        intercept: (Request) -> Unit = {}, transcript: () -> String = { "" }, end: (GeminiSession) -> Unit = {}) : Closeable {
        val reopens = mutableListOf<String>()
        val fallbacks = mutableListOf<Pair<String, String>>()
        var ended = 0
        var recoveredReplies = 0
        val audio = Audio()
        val errors = mutableListOf<String>()
        val states = mutableListOf<String>()
        val backendCalls = AtomicInteger()
        val tokenRequests = mutableListOf<JSONObject>()
        private val created = Channel<Socket>(Channel.UNLIMITED)
        private val http = OkHttpClient.Builder().addInterceptor { chain ->
            intercept(chain.request())
            val token = chain.request().url.encodedPath == "/token"
            if (!token && chain.request().url.encodedPath != "/usage/live") backendCalls.incrementAndGet()
            if (token) { val buffer = okio.Buffer(); chain.request().body!!.writeTo(buffer); tokenRequests += JSONObject(buffer.readUtf8()) }
            val body = if (token) """{"token":"test","model":"gemini-3.8-live","expires_at":"2099-01-01T00:00:00Z"}"""
                else """{"status":"ok"}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val session: GeminiSession = GeminiSession(scope, BackendClient(http, "http://localhost/", "fixture"), audio,
            { label, _ -> states += label }, { _, _ -> }, { ended++; end(sessionRef()) }, {}, {}, { errors += it; sessionRef().stop() }, localTool = local,
            onClientTool = { _, _ -> action(sessionRef()) }, onEvent = event,
            canAnnounce = canAnnounce, onRecoveredReply = { recoveredReplies++ },
            onOverTurnReopen = { reopens += it; sessionRef().setTalking(true) },
            overTranscript = transcript, onStallFallback = { id, text -> fallbacks += id to text; text.isNotBlank() },
            elapsedMs = clock, retryDelay = { 1L },
            sockets = WebSocket.Factory { request, listener -> Socket(request, listener).also { created.trySend(it) } })
        private fun sessionRef() = session
        suspend fun connected(meeting: Boolean = false, settings: ConversationSettings = ConversationSettings()): Socket {
            session.start(settings, meeting)
            return nextSocket()
        }
        suspend fun nextSocket(completeSetup: Boolean = true): Socket {
            val socket = withTimeout(5_000) { created.receive() }
            socket.open(); yield()
            if (completeSetup) { socket.receive("""{"setupComplete":{}}"""); yield() }
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

    @Test fun hardwareAppendSequenceKeepsOverClosedUntilConfirmationAndPlaybackDrain() = runBlocking {
        // Includes turn=0 (no preceding activityEnd), combined events, and providers
        // which emit only the final continuation's turnComplete.
        for (armed in listOf(false, true)) for (completion in listOf("combined", "before_result", "after_result", "final_only")) {
            var now = 4_116L // 22:58:04.116 UTC, relative to 22:58:00.
            val release = java.util.concurrent.CountDownLatch(1)
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            lateinit var fixture: Fixture
            Fixture(this, clock = { now }, intercept = { request ->
                if (request.url.encodedPath == "/tools/append_to_doc") {
                    started.complete(Unit)
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
            }, end = { session ->
                launch {
                    while (!fixture.audio.playbackIdle) kotlinx.coroutines.delay(1)
                    session.setTalking(true)
                }
            }).use { f ->
                fixture = f
                val socket = f.connected(settings = ConversationSettings(mode = ConversationMode.OVER))
                if (armed) { f.session.setTalking(true); f.session.setTalking(false) }
                val starts = socket.sent.count { it.contains("activityStart") }
                val call = JSONObject(tool(name = "append_to_doc"))
                if (completion == "combined") call.put("serverContent", JSONObject().put("turnComplete", true))
                socket.receive(call.toString()); yield()
                if (completion == "before_result") {
                    socket.receive("""{"serverContent":{"turnComplete":true}}"""); yield()
                }
                withTimeout(1_000) { started.await() }
                now = 4_123
                f.session.setTalking(true) // A stale UI/playback callback must also be gated.
                f.session.sendAudio(ByteArray(640))
                assertEquals(starts, socket.sent.count { it.contains("activityStart") })
                assertEquals(0, f.ended)
                now = 7_588
                release.countDown()
                withTimeout(1_000) { while (socket.sent.none { it.contains("functionResponses") }) kotlinx.coroutines.delay(1) }
                if (completion == "after_result") {
                    socket.receive("""{"serverContent":{"turnComplete":true}}"""); yield()
                }
                assertEquals(0, f.ended)
                assertEquals(starts, socket.sent.count { it.contains("activityStart") })
                // Confirmation arrives without another user activity or microphone frame.
                now = 8_000
                socket.receive(audioMessage); yield()
                socket.receive("""{"serverContent":{"turnComplete":true}}"""); yield()
                assertEquals(1, f.audio.chunks.size)
                assertEquals(1, f.ended)
                assertEquals(starts, socket.sent.count { it.contains("activityStart") })
                f.audio.chunks.clear()
                withTimeout(1_000) { while (socket.sent.count { it.contains("activityStart") } == starts) kotlinx.coroutines.delay(1) }
                assertEquals(starts + 1, socket.sent.count { it.contains("activityStart") })
                assertTrue(f.reopens.isEmpty())
            }
        }
    }

    @Test fun photoSaveKeepsTurnClosedUntilFollowUpAndReplaysOnlyReceipt() = runBlocking {
        for (mode in listOf(ConversationMode.AUTO, ConversationMode.PTT, ConversationMode.OVER)) {
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            var captures = 0
            Fixture(this, local = {
                captures++
                release.await()
                ToolResult(JSONObject().put("status", "ok").put("filename", "glasses-2026-10-10_13-14-15.jpg"))
            }).use { f ->
                val socket = f.connected(settings = ConversationSettings(mode = mode))
                if (mode != ConversationMode.AUTO) { f.session.setTalking(true); f.session.setTalking(false) }
                val starts = socket.sent.count { it.contains("activityStart") }
                socket.receive(JSONObject(tool(name = "save_photo"))
                    .put("serverContent", JSONObject().put("turnComplete", true)).toString()); yield()
                assertEquals(0, f.ended)
                assertEquals(starts, socket.sent.count { it.contains("activityStart") })
                release.complete(Unit)
                withTimeout(1_000) { while (socket.sent.none { it.contains("functionResponses") }) kotlinx.coroutines.delay(1) }
                assertEquals(0, f.ended)
                val receipt = socket.sent.single { it.contains("functionResponses") }
                assertFalse(receipt.contains("inlineData"))
                assertFalse(socket.sent.any { it.contains("image/jpeg") })
                socket.receive(tool(name = "save_photo")); yield()
                assertEquals(1, captures)
                assertEquals(0, f.backendCalls.get())
                socket.receive(audioMessage); yield()
                socket.receive("""{"serverContent":{"turnComplete":true}}"""); yield()
                assertEquals(1, f.ended)
                assertEquals(1, f.audio.chunks.size)
                assertEquals(starts, socket.sent.count { it.contains("activityStart") })
            }
        }
    }

    @Test fun autoAndPttDeliverToolConfirmationWithoutAnotherUserTurn() = runBlocking {
        for (mode in listOf(ConversationMode.AUTO, ConversationMode.PTT)) {
            Fixture(this).use { f ->
                val socket = f.connected(settings = ConversationSettings(mode = mode))
                if (mode == ConversationMode.PTT) { f.session.setTalking(true); f.session.setTalking(false) }
                val starts = socket.sent.count { it.contains("activityStart") }
                val call = JSONObject(tool(name = "append_to_doc")).put("serverContent", JSONObject().put("turnComplete", true))
                socket.receive(call.toString()); yield()
                withTimeout(1_000) { while (socket.sent.none { it.contains("functionResponses") }) kotlinx.coroutines.delay(1) }
                assertEquals(0, f.ended)
                socket.receive(audioMessage); yield()
                socket.receive("""{"serverContent":{"turnComplete":true}}"""); yield()
                assertEquals(1, f.ended)
                assertEquals(1, f.audio.chunks.size)
                assertEquals(starts, socket.sent.count { it.contains("activityStart") })
                assertTrue(f.reopens.isEmpty())
            }
        }
    }

    @Test fun tokenNetworkFailuresReportMacAndKeepRetrying() = runBlocking {
        val attempts = AtomicInteger()
        Fixture(this, intercept = { request ->
            if (request.url.encodedPath == "/token" && attempts.incrementAndGet() <= 2) {
                throw java.net.UnknownHostException("private host must not appear in UI")
            }
        }).use { f ->
            f.connected()
            assertEquals(3, attempts.get())
            val retry = f.states.filter { it.contains("retry in") }
            assertEquals(2, retry.size)
            assertTrue(retry.all { GeminiRecovery.announcement(it) == "Can't reach your Mac" })
            assertFalse(f.states.any { it.contains("busy") || it.contains("private host") })
        }
    }

    @Test fun pureStallRetriesExactPcmOnceOnFreshSocketWithContextAndIgnoresOldEvents() = runBlocking {
        var now = 0L
        val cursor = DiagnosticLog.ring.after(0, 500).lastOrNull()?.sequence ?: 0
        Fixture(this, clock = { now }).use { f ->
            val socket = f.connected(settings = ConversationSettings(mode = ConversationMode.OVER))
            socket.receive("""{"serverContent":{"inputTranscription":{"text":"earlier topic"},"turnComplete":true}}""")
            socket.receive("""{"sessionResumptionUpdate":{"resumable":true,"newHandle":"abandoned"}}"""); yield()
            f.reopens.clear()
            f.session.setTalking(true)
            val pcm = ByteArray(12_802) { it.toByte() }
            f.session.sendAudio(pcm)
            f.session.setTalking(false)
            val afterEnd = socket.sent.size
            f.session.sendAudio(ByteArray(640))
            assertEquals(afterEnd, socket.sent.size)
            now = 7_999
            kotlinx.coroutines.delay(120)
            assertTrue(f.reopens.isEmpty())
            now = 8_000
            val retry = f.nextSocket()
            assertTrue(socket.cancelled)
            assertFalse(retry.sent.first().contains("abandoned"))
            assertTrue(f.tokenRequests.last().getString("reconnect_context").contains("earlier topic"))
            f.session.setTalking(true) // Ready/UI callbacks cannot open a new user turn during replay.
            withTimeout(1_000) { while (retry.sent.none { it.contains("activityEnd") }) kotlinx.coroutines.delay(1) }
            assertEquals(1, retry.sent.count { it.contains("activityStart") })
            val chunks = retry.sent.filter { it.contains("audio/pcm") }.map {
                java.util.Base64.getDecoder().decode(JSONObject(it).getJSONObject("realtimeInput").getJSONObject("audio").getString("data"))
            }
            assertEquals(listOf(6400, 6400, 2), chunks.map { it.size })
            assertArrayEquals(pcm, chunks.fold(ByteArray(0)) { all, chunk -> all + chunk })
            socket.receive(tool(name = "append_to_doc")); socket.receive(audioMessage)
            socket.receive("""{"serverContent":{"inputTranscription":{"text":"late private speech"},"turnComplete":true}}""")
            socket.listener.onFailure(socket, java.io.IOException("late"), null)
            yield()
            assertTrue(f.audio.chunks.isEmpty())
            assertEquals(0, f.backendCalls.get())
            retry.receive(tool(name = "append_to_doc")); yield()
            withTimeout(1_000) { while (f.backendCalls.get() == 0) kotlinx.coroutines.delay(1) }
            assertEquals(1, f.backendCalls.get())
            assertEquals(1, f.recoveredReplies)
            assertFalse(f.session.stallRecovering)
            assertTrue(f.fallbacks.isEmpty())
            val logs = DiagnosticLog.ring.after(cursor).map { it.event }
            assertTrue(logs.any { it.contains("stall_retry started bytes=12802") })
            assertTrue(logs.any { it.contains("stall_retry outcome=ok latency_ms=") })
            assertFalse(logs.any { it.contains("late private speech") })
        }
    }

    @Test fun secondPureStallFallsBackOnceAndRetiresRetrySocket() = runBlocking {
        var now = 0L
        Fixture(this, clock = { now }, transcript = { "buy milk" }).use { f ->
            f.connected(settings = ConversationSettings(mode = ConversationMode.OVER))
            f.session.setTalking(true); f.session.sendAudio(ByteArray(640)); f.session.setTalking(false)
            now = 8_000
            val retry = f.nextSocket()
            withTimeout(1_000) { while (retry.sent.none { it.contains("activityEnd") }) kotlinx.coroutines.delay(1) }
            now = 16_000
            withTimeout(1_000) { while (f.fallbacks.isEmpty()) kotlinx.coroutines.delay(1) }
            assertEquals("buy milk", f.fallbacks.single().second)
            assertEquals(listOf("stall_fallback"), f.reopens)
            assertTrue(retry.cancelled)
            retry.receive(tool(name = "append_to_doc")); yield()
            assertEquals(0, f.backendCalls.get())
            val next = f.nextSocket()
            assertFalse(next.sent.any { it.contains("audio/pcm") })
            assertEquals(1, f.recoveredReplies)
            now = 100_000; kotlinx.coroutines.delay(120)
            assertEquals(1, f.fallbacks.size)
        }
    }

    @Test fun nonReplayableTurnAndFailedReconnectSaveTranscriptWithoutAnotherReplay() = runBlocking {
        for (overflow in listOf(false, true)) {
            var now = 0L
            Fixture(this, clock = { now }, transcript = { "save this thought" }).use { f ->
                f.connected(settings = ConversationSettings(mode = ConversationMode.OVER))
                f.session.setTalking(true)
                f.session.sendAudio(ByteArray(if (overflow) 5_760_002 else 640))
                f.session.setTalking(false)
                now = 8_000
                if (!overflow) {
                    val retry = f.nextSocket(completeSetup = false)
                    retry.listener.onFailure(retry, java.io.IOException("failed reconnect"), null)
                }
                withTimeout(1_000) { while (f.fallbacks.isEmpty()) kotlinx.coroutines.delay(1) }
                assertEquals("save this thought", f.fallbacks.single().second)
                val next = f.nextSocket()
                assertFalse(next.sent.any { it.contains("audio/pcm") })
                assertEquals(if (overflow) 0 else 1, f.recoveredReplies)
            }
        }
    }

    @Test fun retryTranscriptKeepsFortyFiveSecondWaitAndNeverFallsBack() = runBlocking {
        var now = 0L
        Fixture(this, clock = { now }).use { f ->
            f.connected(settings = ConversationSettings(mode = ConversationMode.OVER))
            f.session.setTalking(true); f.session.sendAudio(ByteArray(640)); f.session.setTalking(false)
            now = 8_000
            val retry = f.nextSocket()
            withTimeout(1_000) { while (retry.sent.none { it.contains("activityEnd") }) kotlinx.coroutines.delay(1) }
            retry.receive("""{"serverContent":{"inputTranscription":{"text":"heard on retry"}}}"""); yield()
            now = 16_000; kotlinx.coroutines.delay(120)
            assertTrue(f.reopens.isEmpty())
            now = 53_000
            withTimeout(1_000) { while (f.reopens.isEmpty()) kotlinx.coroutines.delay(1) }
            assertEquals(listOf("no_reply_watchdog"), f.reopens)
            assertTrue(f.fallbacks.isEmpty())
            assertFalse(retry.cancelled)
        }
    }

    @Test fun stopCancelsReplayAndSuspensionDefersItUntilResume() = runBlocking {
        for (stop in listOf(false, true)) {
            var now = 0L
            Fixture(this, clock = { now }).use { f ->
                f.connected(settings = ConversationSettings(mode = ConversationMode.OVER))
                f.session.setTalking(true); f.session.sendAudio(ByteArray(12_800)); f.session.setTalking(false)
                now = 8_000
                val retry = f.nextSocket(completeSetup = false)
                f.session.suspendAudio(true)
                retry.receive("""{"setupComplete":{}}"""); yield()
                kotlinx.coroutines.delay(120)
                assertFalse(retry.sent.any { it.contains("activityStart") })
                if (stop) f.session.stop() else f.session.suspendAudio(false)
                if (stop) {
                    kotlinx.coroutines.delay(120)
                    assertFalse(retry.sent.any { it.contains("activityStart") })
                    assertTrue(f.fallbacks.isEmpty())
                    assertTrue(retry.cancelled)
                } else {
                    withTimeout(1_000) { while (retry.sent.none { it.contains("activityEnd") }) kotlinx.coroutines.delay(1) }
                    assertEquals(2, retry.sent.count { it.contains("audio/pcm") })
                }
            }
        }
    }

    @Test fun malformedRetrySetupSavesEmptyTranscriptInsteadOfDroppingTurn() = runBlocking {
        var now = 0L
        Fixture(this, clock = { now }).use { f ->
            f.connected(settings = ConversationSettings(mode = ConversationMode.OVER))
            f.session.setTalking(true); f.session.sendAudio(ByteArray(640)); f.session.setTalking(false)
            now = 8_000
            val retry = f.nextSocket(completeSetup = false)
            retry.receive("invalid json"); yield()
            assertEquals("", f.fallbacks.single().second)
            assertEquals(listOf("stall_fallback"), f.reopens)
            assertTrue(f.errors.isEmpty())
        }
    }

    @Test fun progressBeforeActivityEndAlsoPreventsRetry() = runBlocking {
        var now = 0L
        Fixture(this, clock = { now }).use { f ->
            val socket = f.connected(settings = ConversationSettings(mode = ConversationMode.OVER))
            f.session.setTalking(true); f.session.sendAudio(ByteArray(640))
            socket.receive("""{"serverContent":{"inputTranscription":{"text":"heard early"}}}"""); yield()
            f.session.setTalking(false)
            now = 45_000
            withTimeout(1_000) { while (f.reopens.isEmpty()) kotlinx.coroutines.delay(1) }
            assertFalse(socket.cancelled)
            assertTrue(f.fallbacks.isEmpty())
            assertEquals(0, f.recoveredReplies)
        }
    }

    @Test fun autoPttMeetingAndTranslatorNeverReplayOrSaveStalls() = runBlocking {
        for ((settings, meeting) in listOf(
            ConversationSettings(mode = ConversationMode.AUTO) to false,
            ConversationSettings(mode = ConversationMode.PTT) to false,
            ConversationSettings(mode = ConversationMode.OVER) to true,
            ConversationSettings(mode = ConversationMode.OVER, translationLanguage = "fr") to false,
        )) {
            var now = 0L
            Fixture(this, clock = { now }).use { f ->
                val socket = f.connected(settings = settings, meeting = meeting)
                f.session.setTalking(true); f.session.sendAudio(ByteArray(640)); f.session.setTalking(false)
                now = 100_000; kotlinx.coroutines.delay(120)
                assertFalse(socket.cancelled)
                assertTrue(f.fallbacks.isEmpty())
                assertEquals(0, f.recoveredReplies)
            }
        }
    }

    @Test fun emptyCompleteReopensImmediatelyButInputTranscriptIsNotAReply() = runBlocking {
        Fixture(this).use { f ->
            val socket = f.connected(settings = ConversationSettings(mode = ConversationMode.OVER))
            f.session.setTalking(true); f.session.setTalking(false)
            socket.receive("""{"serverContent":{"inputTranscription":{"text":"heard"},"turnComplete":true}}""")
            yield()
            assertEquals(listOf("empty_turn_complete"), f.reopens)
            assertTrue(socket.sent.last().contains("activityStart"))
            assertEquals(0, f.ended)
        }
    }

    @Test fun toolWaitsForResultAndSpokenContinuationEvenAfterEarlyComplete() = runBlocking {
        var now = 0L
        val result = kotlinx.coroutines.CompletableDeferred<Unit>()
        Fixture(this, clock = { now }, local = { result.await(); ToolResult(JSONObject()) }).use { f ->
            val socket = f.connected(settings = ConversationSettings(mode = ConversationMode.OVER))
            f.session.setTalking(true); f.session.setTalking(false)
            socket.receive(tool(name = "look")); yield(); yield()
            socket.receive("""{"serverContent":{"turnComplete":true}}"""); yield()
            now = 8_000
            kotlinx.coroutines.delay(120)
            assertTrue(f.reopens.isEmpty())
            assertEquals(0, f.ended)
            now = 30_000
            result.complete(Unit); yield(); yield()
            assertTrue(socket.sent.any { it.contains("functionResponses") })
            socket.receive("""{"serverContent":{"turnComplete":true}}"""); yield()
            assertEquals(0, f.ended)
            socket.receive(audioMessage); yield()
            socket.receive("""{"serverContent":{"turnComplete":true}}"""); yield()
            assertEquals(1, f.ended)
            now = 45_000
            kotlinx.coroutines.delay(120)
            assertTrue(f.reopens.isEmpty())
        }
    }

    @Test fun toolDeadlineIsBoundedAndLateResultCannotLeakIntoReopenedTurn() = runBlocking {
        var now = 0L
        val result = kotlinx.coroutines.CompletableDeferred<Unit>()
        Fixture(this, clock = { now }, local = { result.await(); ToolResult(JSONObject()) }).use { f ->
            val socket = f.connected(settings = ConversationSettings(mode = ConversationMode.OVER))
            f.session.setTalking(true); f.session.setTalking(false)
            socket.receive(tool(name = "look")); yield(); yield()
            now = 44_999
            kotlinx.coroutines.delay(120)
            assertTrue(f.reopens.isEmpty())
            now = 45_000
            withTimeout(1_000) { while (f.reopens.isEmpty()) kotlinx.coroutines.delay(10) }
            assertEquals(listOf("tool_reply_watchdog"), f.reopens)
            assertTrue(socket.sent.last().contains("activityStart"))
            result.complete(Unit); yield(); yield()
            assertFalse(socket.sent.any { it.contains("functionResponses") })
            socket.receive(tool(name = "look")); yield(); yield()
            assertFalse(socket.sent.any { it.contains("functionResponses") })
        }
    }

    @Test fun rejectedToolSendDoesNotStartReplyClockAndSuccessfulSendGetsFullEightSeconds() = runBlocking {
        var now = 0L
        Fixture(this, clock = { now }).use { f ->
            val socket = f.connected(settings = ConversationSettings(mode = ConversationMode.OVER))
            f.session.setTalking(true); f.session.setTalking(false)
            socket.rejectTools = true
            socket.receive(tool(name = "look")); yield(); yield()
            assertFalse(socket.sent.any { it.contains("functionResponses") })
            now = 40_000
            kotlinx.coroutines.delay(120)
            assertTrue(f.reopens.isEmpty())
            now = 44_000
            socket.rejectTools = false
            socket.receive(tool(name = "look")); yield(); yield()
            assertTrue(socket.sent.any { it.contains("functionResponses") })
            now = 51_999
            kotlinx.coroutines.delay(120)
            assertTrue(f.reopens.isEmpty())
            now = 52_000
            withTimeout(1_000) { while (f.reopens.isEmpty()) kotlinx.coroutines.delay(10) }
            assertEquals(listOf("tool_reply_watchdog"), f.reopens)
        }
    }

    @Test fun inputOnlyReplyIsBoundedAndSuspensionDefersRecoveryUntilResume() = runBlocking {
        var now = 0L
        Fixture(this, clock = { now }).use { f ->
            val socket = f.connected(settings = ConversationSettings(mode = ConversationMode.OVER))
            f.session.setTalking(true); f.session.setTalking(false)
            socket.receive("""{"serverContent":{"inputTranscription":{"text":"heard"}}}"""); yield()
            now = 8_000
            kotlinx.coroutines.delay(120)
            assertTrue(f.reopens.isEmpty())
            f.session.suspendAudio(true)
            now = 45_000
            kotlinx.coroutines.delay(120)
            assertTrue(f.reopens.isEmpty())
            f.session.suspendAudio(false)
            withTimeout(1_000) { while (f.reopens.isEmpty()) kotlinx.coroutines.delay(10) }
            assertEquals(listOf("no_reply_watchdog"), f.reopens)
        }
    }

    @Test fun stoppingCancelsOverWatchdogAndPttDoesNotUseIt() = runBlocking {
        for (mode in listOf(ConversationMode.OVER, ConversationMode.PTT)) {
            var now = 0L
            Fixture(this, clock = { now }).use { f ->
                f.connected(settings = ConversationSettings(mode = mode))
                f.session.setTalking(true); f.session.setTalking(false)
                if (mode == ConversationMode.OVER) f.session.stop()
                now = 100_000
                kotlinx.coroutines.delay(120)
                assertTrue(f.reopens.isEmpty())
            }
        }
    }

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

    @Test fun overTranscriptionFallbackAndVoiceStopShareTheFinalTurn() = runBlocking {
        lateinit var fixture: Fixture
        lateinit var over: OverModeController
        var now = 0L
        var recognizerStops = 0
        var controls = HandsFreeState(mode = ConversationMode.OVER).start(0).inputsAvailable(false)
        val stop = VoiceStop(this, { controls.shouldRun }, { fixture.audio.playbackIdle }, {
            controls = controls.captureHold(1)
            fixture.session.stop()
        })
        Fixture(this, local = stop::tool, event = { event ->
            event.inputText?.let(over::inputTranscription)
            stop.event(event)
        }).use { f ->
            fixture = f
            over = OverModeController(this, { _, _ ->
                object : OverSpeechInput {
                    override fun startOnDevice() = Unit // Still preparing: Gemini fallback owns sign-off.
                    override fun audio(pcm: ByteArray) = Unit
                    override fun stop() { recognizerStops++ }
                }
            }, { f.session.setTalking(false) }, {}, { now })
            try {
                val settings = ConversationSettings(mode = ConversationMode.OVER)
                val socket = f.connected(settings = settings)
                f.session.setTalking(true)
                over.start(settings)
                f.session.sendAudio(byteArrayOf(1, 2))
                socket.receive("""{"serverContent":{"inputTranscription":{"text":"Good"}}}""")
                socket.receive("""{"serverContent":{"inputTranscription":{"text":"bye, over."}}}""")
                yield()
                assertFalse(stop.pending)
                now = 1_200
                over.tick()
                assertEquals(1, socket.sent.count { it.contains("activityEnd") })
                assertEquals(0, recognizerStops)
                val afterEnd = socket.sent.size
                f.session.sendAudio(byteArrayOf(3, 4))
                assertEquals(afterEnd, socket.sent.size)
                socket.receive(audioMessage)
                socket.receive("""{"serverContent":{"turnComplete":true}}""")
                yield()
                assertTrue(stop.pending)
                kotlinx.coroutines.delay(30)
                assertFalse(socket.cancelled)
                f.audio.chunks.clear()
                withTimeout(1_000) { while (!socket.cancelled) kotlinx.coroutines.delay(10) }
                assertTrue(controls.armed)
                assertFalse(controls.conversation)
                assertEquals(0, f.backendCalls.get())
            } finally {
                stop.reset()
                over.stop()
            }
        }
    }

    @Test fun voiceStopRepliesLocallyThenClosesSocketAfterSpeakerDrains() = runBlocking {
        for (mode in ConversationMode.entries) {
            lateinit var fixture: Fixture
            var controls = HandsFreeState(mode = mode).start(0)
            val stop = VoiceStop(this, { controls.shouldRun }, { fixture.audio.playbackIdle }, {
                controls = controls.captureHold(1)
                fixture.session.stop()
            })
            Fixture(this, local = stop::tool, event = stop::event).use { f ->
                fixture = f
                val socket = f.connected(settings = ConversationSettings(mode = mode))
                socket.receive(tool(name = VoiceStop.TOOL)); yield(); yield()
                assertEquals(0, f.backendCalls.get())
                assertTrue(socket.sent.any { it.contains("functionResponses") && it.contains("Bye!") })
                socket.receive(audioMessage)
                socket.receive("""{"serverContent":{"turnComplete":true}}""")
                yield()
                kotlinx.coroutines.delay(30)
                assertFalse(socket.cancelled)
                f.audio.chunks.clear()
                withTimeout(1_000) { while (!socket.cancelled) kotlinx.coroutines.delay(10) }
                assertTrue(controls.armed)
                assertFalse(controls.conversation)
                stop.reset()
            }
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
            assertTrue(f.nextSocket().sent.first().contains("keep-me"))
        }
    }

    @Test fun hardwareDropThenTwoPolicyClosesRestoresContextBeforeOverActivity() = runBlocking {
        Fixture(this).use { f ->
            val first = f.connected(settings = ConversationSettings(mode = ConversationMode.OVER))
            first.receive("""{"sessionResumptionUpdate":{"resumable":true,"newHandle":"checkpoint"}}""")
            first.receive("""{"serverContent":{"inputTranscription":{"text":"Add a note to small talk. Over."}}}""")
            first.receive(tool(name = "append_to_doc"))
            withTimeout(2_000) { while (first.sent.none { it.contains("functionResponses") }) yield() }
            first.receive("""{"serverContent":{"outputTranscription":{"text":"I've added that note to The Fine Art of Small"}}}""")
            first.receive(audioMessage); yield()
            first.listener.onFailure(first, java.io.IOException("drop"), null)
            val reused = f.nextSocket(completeSetup = false)
            assertTrue(reused.sent.single().contains("checkpoint"))
            assertEquals(1, f.tokenRequests.size)
            reused.listener.onClosing(reused, 1008, "")
            val freshToken = f.nextSocket(completeSetup = false)
            assertTrue(freshToken.sent.single().contains("checkpoint"))
            assertEquals(2, f.tokenRequests.size)
            freshToken.listener.onClosing(freshToken, 1008, "")
            val freshSession = f.nextSocket()
            assertEquals(3, f.tokenRequests.size)
            assertFalse(freshSession.sent.first().contains("checkpoint"))
            val recap = f.tokenRequests.last().getString("reconnect_context")
            assertEquals(1, freshSession.sent.size) // Setup only: history never requests a model reply.
            assertTrue(recap.contains("small talk"))
            assertTrue(recap.contains("Fine Art of Small"))
            assertTrue(recap.contains("append_to_doc"))
            assertTrue(recap.contains("ok"))
            assertEquals(1, f.recoveredReplies)
            f.session.setTalking(true)
            assertTrue(freshSession.sent.last().contains("activityStart"))
            assertEquals(1, f.backendCalls.get()) // The saved note was never replayed.
            assertTrue(f.tokenRequests.none { it.optJSONArray("avoid_models")?.length()?.let { n -> n > 0 } == true })
            assertTrue(f.errors.isEmpty())
        }
    }

    @Test fun generatingUpdateAndRepeatedNetworkLossKeepLastCheckpointWithoutRecap() = runBlocking {
        Fixture(this).use { f ->
            var socket = f.connected()
            socket.receive("""{"sessionResumptionUpdate":{"resumable":true,"newHandle":"last-valid"}}""")
            socket.receive("""{"sessionResumptionUpdate":{"resumable":false,"newHandle":""}}"""); yield()
            repeat(4) {
                socket.listener.onClosing(socket, 1011, "Internal error")
                socket = f.nextSocket()
                assertTrue(socket.sent.single().contains("last-valid"))
            }
            assertEquals(1, f.tokenRequests.size)
        }
    }

    @Test fun explicitInvalidHandleRestoresHistoryAndIntentionalRestartClearsIt() = runBlocking {
        Fixture(this).use { f ->
            val first = f.connected()
            first.receive("""{"serverContent":{"inputTranscription":{"text":"private previous topic"}}}""")
            first.receive("""{"sessionResumptionUpdate":{"resumable":true,"newHandle":"expired"}}"""); yield()
            first.listener.onClosing(first, 1008, "invalid resumption handle")
            val replacement = f.nextSocket()
            assertEquals(1, replacement.sent.size)
            assertTrue(f.tokenRequests.last().getString("reconnect_context").contains("private previous topic"))
            f.session.start(ConversationSettings())
            assertEquals(1, f.nextSocket().sent.size)
            assertFalse(f.tokenRequests.last().has("reconnect_context"))
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
            socket.receive("""{"serverContent":{"outputTranscription":{"text":"Done."},"turnComplete":true}}"""); yield()
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
