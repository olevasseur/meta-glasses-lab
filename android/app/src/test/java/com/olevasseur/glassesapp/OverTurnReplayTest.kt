package com.olevasseur.glassesapp

import org.junit.Assert.*
import org.junit.Test

class OverTurnReplayTest {
    @Test fun exactCopyCapAndOverflowNeverReplayTruncatedAudio() {
        val turn = OverTurnReplay()
        val source = ByteArray(6400) { it.toByte() }
        repeat(900) { turn.append(source) }
        assertEquals(5_760_000, turn.size)
        assertTrue(turn.replayable)
        val expected = source.copyOf()
        source.fill(0)
        assertArrayEquals(expected, turn.chunk(0))
        assertArrayEquals(expected, turn.chunk(5_753_600))
        turn.append(byteArrayOf(1, 2))
        assertFalse(turn.replayable)
        assertEquals(0, turn.size)
        turn.append(source)
        assertFalse(turn.beginRetry(8_000))
    }

    @Test fun onlyOneRetryAndNoRetryAfterProgressOrEmptyAudio() {
        val turn = OverTurnReplay()
        assertFalse(turn.beginRetry(0))
        turn.append(byteArrayOf(1, 2))
        assertTrue(turn.beginRetry(8_000))
        assertFalse(turn.beginRetry(16_000))
        val heard = OverTurnReplay().apply { append(byteArrayOf(1, 2)); progress = true }
        assertFalse(heard.beginRetry(8_000))
    }

    @Test fun emptyTranscriptAsksForRepeatAndSavedTranscriptConfirmsInbox() {
        assertEquals("Gemini didn't answer. Please say that again.", stallFallbackAnnouncement(false))
        assertEquals("Gemini didn't answer. I saved what I heard to your inbox.", stallFallbackAnnouncement(true))
    }

    @Test fun fallbackPrefersGeminiTranscriptAndStripsSignOff() {
        val turn = OverTurnReplay().apply { append(byteArrayOf(1, 2)); transcript = "add a note on device" }
        assertEquals("add a note on device", turn.fallbackText())
        turn.heard = "Add a note to Fast and Slow. Gilbert says belief comes first. Over."
        assertEquals("Add a note to Fast and Slow. Gilbert says belief comes first.", turn.fallbackText())
        assertTrue(turn.beginRetry(8_000))
        assertEquals("", turn.heard)
        assertEquals("Add a note to Fast and Slow. Gilbert says belief comes first.", turn.fallbackText())
        assertEquals("The game is over.", "The game is over. Over.".withoutSignOff())
        assertFalse(ServerEvent(inputText = "heard").overProgress())
        assertTrue(ServerEvent(calls = listOf(FunctionCall("1", "append_to_doc", org.json.JSONObject()))).overProgress())
    }
}
