package com.olevasseur.glassesapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OverReplyWatchdogTest {
    @Test fun pureStallRequiresNoTranscriptionContentOrToolEvenIfTranscriptionIsEmpty() {
        val watchdog = OverReplyWatchdog()
        watchdog.begin(0)
        watchdog.event(ServerEvent(resumable = true, handle = "checkpoint"), 1_000)
        assertFalse(watchdog.progress)
        assertEquals("no_reply_watchdog", watchdog.expired(8_000))
        // Gemini can transcribe a turn and then never act on it: a transcript is not progress.
        for (event in listOf(ServerEvent(inputText = "heard"), ServerEvent(inputText = ""))) {
            watchdog.begin(0)
            watchdog.event(event, 1_000)
            assertFalse(watchdog.progress)
        }
        for (event in listOf(ServerEvent(modelContent = true), ServerEvent(audio = listOf(byteArrayOf(1, 2))),
            ServerEvent(calls = listOf(FunctionCall("1", "append_to_doc", JSONObject()))))) {
            watchdog.begin(0)
            watchdog.event(event, 1_000)
            assertTrue(watchdog.progress)
        }
        watchdog.begin(0)
        watchdog.event(ServerEvent(inputText = "heard"), 1_000)
        assertNull(watchdog.expired(8_000))
        assertNull(watchdog.expired(9_999))
        assertEquals("no_reply_watchdog", watchdog.expired(10_000))
    }

    @Test fun toolExecutionIsCappedAndDuplicatesCannotExtendIt() {
        val watchdog = OverReplyWatchdog()
        watchdog.begin(1_000)
        val call = FunctionCall("1", "look", JSONObject())
        watchdog.event(ServerEvent(calls = listOf(call)), 8_000)
        assertNull(watchdog.event(ServerEvent(turnComplete = true), 10_000))
        assertTrue(watchdog.waiting)
        watchdog.event(ServerEvent(calls = listOf(call, call.copy(id = "2"))), 40_000)
        assertNull(watchdog.expired(52_999))
        assertEquals("tool_reply_watchdog", watchdog.expired(53_000))
    }

    @Test fun lastResultStartsFreshNoReplyClockAndDuplicateDoesNotExtendIt() {
        val watchdog = OverReplyWatchdog()
        watchdog.begin(0)
        val calls = listOf("1", "2").map { FunctionCall(it, "remember", JSONObject()) }
        watchdog.event(ServerEvent(calls = calls, turnComplete = true), 1_000)
        watchdog.toolResult("1", 30_000)
        assertNull(watchdog.expired(40_000))
        watchdog.toolResult("2", 44_000)
        watchdog.event(ServerEvent(calls = calls), 45_000)
        watchdog.toolResult("2", 50_000)
        assertNull(watchdog.expired(51_999))
        assertEquals("tool_reply_watchdog", watchdog.expired(52_000))
    }

    @Test fun toolWithoutPriorActivityEndStillGatesCompletionUntilActualReply() {
        val watchdog = OverReplyWatchdog()
        watchdog.event(ServerEvent(calls = listOf(FunctionCall("1", "append_to_doc", JSONObject())), turnComplete = true), 4_116)
        assertTrue(watchdog.waiting)
        watchdog.toolResult("1", 7_588)
        watchdog.event(ServerEvent(turnComplete = true), 7_600)
        assertTrue(watchdog.waiting)
        watchdog.event(ServerEvent(audio = listOf(byteArrayOf(1, 2)), turnComplete = true), 8_000)
        assertFalse(watchdog.waiting)
    }

    @Test fun postResultContentHasNormalStreamingDeadlineWithoutEarlyGenerationCompletion() {
        val watchdog = OverReplyWatchdog()
        watchdog.begin(0)
        watchdog.event(ServerEvent(calls = listOf(FunctionCall("1", "look", JSONObject()))), 1_000)
        watchdog.toolResult("1", 44_000)
        watchdog.event(ServerEvent(audio = listOf(byteArrayOf(1, 2))), 50_000)
        assertNull(watchdog.expired(94_999))
        assertEquals("tool_reply_watchdog", watchdog.expired(95_000))
        watchdog.event(ServerEvent(turnComplete = true), 95_000)
        assertFalse(watchdog.waiting)
    }

    @Test fun metadataIsNotProgressAndEmptyCompletionRecoversWithoutWaiting() {
        val watchdog = OverReplyWatchdog()
        watchdog.begin(0)
        watchdog.event(ServerEvent(resumable = true, handle = "handle", usage = JSONObject()), 7_999)
        assertEquals("no_reply_watchdog", watchdog.expired(8_000))
        val empty = LiveProtocol.parse("""{"serverContent":{"modelTurn":{"parts":[]},"turnComplete":true}}""")
        assertEquals("empty_turn_complete", watchdog.event(empty, 8_000))
        assertFalse(watchdog.waiting)
    }

    @Test fun healthyLongReplyAndCompletedTurnDoNotTriggerWatchdog() {
        val watchdog = OverReplyWatchdog()
        watchdog.begin(0)
        for (time in 5_000L..95_000L step 5_000) {
            watchdog.event(ServerEvent(audio = listOf(byteArrayOf(1, 2))), time)
            assertNull(watchdog.expired(time))
        }
        assertNull(watchdog.event(ServerEvent(turnComplete = true), 100_000))
        assertNull(watchdog.expired(1_000_000))
    }
}
