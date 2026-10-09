package com.olevasseur.glassesapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OverReplyWatchdogTest {
    @Test fun resultWithoutReplyAndRepeatedToolCallsCannotExtendOverallDeadline() {
        val watchdog = OverReplyWatchdog()
        watchdog.begin(1_000)
        val call = FunctionCall("1", "look", JSONObject())
        watchdog.event(ServerEvent(calls = listOf(call)), 8_000)
        watchdog.toolResult("1")
        assertNull(watchdog.event(ServerEvent(turnComplete = true), 10_000))
        assertTrue(watchdog.waiting)
        watchdog.event(ServerEvent(calls = listOf(call.copy(id = "2"))), 40_000)
        assertNull(watchdog.expired(45_999))
        assertEquals("tool_reply_watchdog", watchdog.expired(46_000))
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
