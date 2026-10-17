package com.olevasseur.glassesapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class OverModeControllerTest {
    @Test fun fallbackTranscriptJoinsFinalSegmentsAndLatestPartialWithoutSignOff() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        lateinit var result: (String, Boolean) -> Unit
        val controller = OverModeController(scope, { callback, _ ->
            result = callback
            object : OverSpeechInput {
                override fun startOnDevice() = Unit
                override fun audio(pcm: ByteArray) = Unit
                override fun stop() = Unit
            }
        }, {}, {})
        try {
            controller.start(ConversationSettings(mode = ConversationMode.OVER))
            result("Buy", false); result("Buy milk.", true)
            result("And", false); result("And eggs, OVER!", false)
            assertEquals("Buy milk. And eggs,", controller.transcript())
            controller.pause()
            result("model echo", true)
            assertEquals("Buy milk. And eggs,", controller.transcript())
            controller.stop()
            assertEquals("Buy milk. And eggs,", controller.transcript())
            controller.start(ConversationSettings(mode = ConversationMode.OVER, keyword = "go ahead"))
            assertEquals("", controller.transcript())
            result("Keep the clover. Go", true); result("ahead!", false)
            assertEquals("Keep the clover.", controller.transcript())
            result("ahead!", true)
            assertEquals("Keep the clover.", controller.transcript())
        } finally { controller.stop(); scope.cancel() }
    }

    @Test fun partialSpeechArmsSilenceAndLogsTimesWithoutTranscriptContent() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var now = 0L
        var activity = 0
        lateinit var result: (String, Boolean) -> Unit
        val ends = mutableListOf<OverTurnDetector.End>()
        val cursor = DiagnosticLog.ring.after(0, 500).lastOrNull()?.sequence ?: 0
        val controller = OverModeController(scope, { callback, _ ->
            result = callback
            object : OverSpeechInput {
                override fun startOnDevice() = Unit
                override fun audio(pcm: ByteArray) = Unit
                override fun stop() = Unit
            }
        }, ends::add, {}, { now }, { activity++ })
        try {
            controller.start(ConversationSettings(mode = ConversationMode.OVER))
            repeat(1500) { now += 20; controller.audio(ByteArray(640)) }
            assertTrue(ends.isEmpty())
            assertEquals(0, activity)
            result("private quiet sentence", false)
            assertEquals(1, activity)
            repeat(499) { now += 20; controller.audio(ByteArray(640)) }
            assertTrue(ends.isEmpty())
            now += 20; controller.audio(ByteArray(640))
            assertEquals(listOf(OverTurnDetector.End.SILENCE_TIMEOUT), ends)
            val logs = DiagnosticLog.ring.after(cursor).filter { it.feature == "over" }
            assertTrue(logs.any { it.event.contains("first_ms=30000 last_ms=30000") })
            assertFalse(logs.any { it.event.contains("private quiet sentence") })
        } finally { controller.stop(); scope.cancel() }
    }

    @Test fun staysWarmAndCatchesImmediateSignOffOnSuccessiveTurns() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var now = 0L
        var starts = 0
        var stops = 0
        lateinit var result: (String, Boolean) -> Unit
        val heard = mutableListOf<ByteArray>()
        val ends = mutableListOf<OverTurnDetector.End>()
        val controller = OverModeController(scope, { callback, _ ->
            result = callback
            object : OverSpeechInput {
                override fun startOnDevice() { starts++ }
                override fun stop() { stops++ }
                override fun audio(pcm: ByteArray) { heard += pcm }
            }
        }, ends::add, {}, { now })
        try {
            repeat(3) {
                controller.start(ConversationSettings(mode = ConversationMode.OVER))
                controller.start(ConversationSettings(mode = ConversationMode.OVER)) // Idempotent ready callbacks.
                controller.audio(byteArrayOf(1, 2))
                result("Over", true) // First word, no minimum turn-age gate.
                now += 1200
                controller.tick()
                assertEquals(it + 1, ends.size)
                controller.audio(byteArrayOf(3, 4))
                assertArrayEquals(byteArrayOf(0, 0), heard.last()) // Playback cannot trigger sign-off.
                result("over and out", true)
                assertEquals(it + 1, ends.size)
                assertEquals(0, stops)
            }
            assertEquals(1, starts)
            controller.stop()
            assertEquals(1, stops)
            controller.start(ConversationSettings(mode = ConversationMode.OVER))
            assertEquals(2, starts)
        } finally { controller.stop(); scope.cancel() }
    }

    @Test fun transcriptionFallbackCanCatchEarlySignOffWhileServiceWarms() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var now = 0L
        val ends = mutableListOf<OverTurnDetector.End>()
        lateinit var status: (String) -> Unit
        val controller = OverModeController(scope, { _, callback ->
            status = callback
            object : OverSpeechInput {
                override fun startOnDevice() = Unit
                override fun audio(pcm: ByteArray) = Unit
                override fun stop() = Unit
            }
        }, ends::add, {}, { now })
        try {
            controller.start(ConversationSettings(mode = ConversationMode.OVER))
            controller.inputTranscription("Can you hear me? Ov")
            controller.inputTranscription("er.")
            now = 1200
            controller.tick()
            assertEquals(listOf(OverTurnDetector.End.KEYWORD), ends)
            controller.inputTranscription("over and out") // Late transcription cannot reopen an ended turn.
            controller.tick()
            assertEquals(1, ends.size)
            controller.start(ConversationSettings(mode = ConversationMode.OVER))
            controller.inputTranscription("over") // Provisional fallback must not survive local readiness.
            status("Listening for sign-off · on-device speech")
            controller.inputTranscription("over and out") // Healthy local service owns recognition.
            now = 2400
            controller.tick()
            assertEquals(1, ends.size)
        } finally { controller.stop(); scope.cancel() }
    }

    @Test fun startupAndRestartBackpressurePreserveEarlyAudioExactly() {
        val buffer = SpeechPcmBuffer(16)
        val output = mutableListOf<Byte>()
        assertTrue(buffer.offer(byteArrayOf(1, 2, 3, 4)))
        buffer.drain { _, _, _ -> 0 } // Service warming, pipe not consuming yet.
        assertTrue(buffer.offer(byteArrayOf(5, 6)))
        var blocked = false
        buffer.drain { bytes, offset, _ ->
            if (blocked) 0 else {
                output.addAll(bytes.slice(offset until offset + 2))
                blocked = true
                2
            }
        }
        assertEquals(listOf<Byte>(1, 2), output)
        buffer.drain { bytes, offset, count ->
            val n = minOf(2, count)
            output.addAll(bytes.slice(offset until offset + n))
            n
        }
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), output)
        assertFalse(buffer.offer(ByteArray(17))) // Caller surfaces the overflow, never silently drops it.
        assertTrue(buffer.offer(ByteArray(16)))
        buffer.clear()
        buffer.drain { _, _, _ -> fail("Old turn PCM leaked"); 0 }
    }
}
