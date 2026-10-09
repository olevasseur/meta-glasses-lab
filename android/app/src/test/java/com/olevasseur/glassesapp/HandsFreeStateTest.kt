package com.olevasseur.glassesapp

import org.junit.Assert.*
import org.junit.Test

class HandsFreeStateTest {
    @Test fun `arming listens for buttons without starting the microphone`() {
        val armed = HandsFreeState().arm(100)
        assertTrue(armed.armed)
        assertFalse(armed.conversation)
        assertFalse(armed.shouldRun)
    }

    @Test fun `capture stop preserves controls and next hold starts again`() {
        val started = HandsFreeState().arm(0).captureHold(1)
        assertTrue(started.shouldRun)
        val stopped = started.captureHold(2)
        assertTrue(stopped.armed)
        assertFalse(stopped.shouldRun)
        assertTrue(stopped.captureHold(3).shouldRun)
        assertFalse(stopped.stop().armed)
    }

    @Test fun `call and focus overlap cannot resume prematurely`() {
        val active = HandsFreeState().start(0)
        val paused = active.pause(PauseReason.CALL, true).pause(PauseReason.FOCUS, true)
        assertFalse(paused.shouldRun)
        assertFalse(paused.pause(PauseReason.FOCUS, false).shouldRun)
        assertFalse(paused.pause(PauseReason.CALL, false).shouldRun)
        assertTrue(paused.pause(PauseReason.CALL, false).pause(PauseReason.FOCUS, false).shouldRun)
    }

    @Test fun `start during call cannot bypass call guard`() {
        val calling = HandsFreeState().pause(PauseReason.CALL, true)
        assertFalse(calling.start(1).shouldRun)
        assertFalse(calling.start(1).stop().start(2).shouldRun)
    }

    @Test fun `bluetooth loss requires explicit restart despite focus gain`() {
        val disconnected = HandsFreeState().start(0).pause(PauseReason.BLUETOOTH, true)
        assertFalse(disconnected.pause(PauseReason.FOCUS, false).shouldRun)
        assertTrue(disconnected.start(1).shouldRun)
    }

    @Test fun `losing and recovering DAT inputs never pauses any conversation mode`() {
        ConversationMode.entries.forEach { mode ->
            val ready = HandsFreeState(mode = mode).start(0).inputsAvailable(true)
            assertTrue(ready.inputsAvailable)
            val lost = ready.inputsAvailable(false)
            assertTrue(lost.shouldRun)
            assertTrue(lost.conversation)
            assertFalse(lost.inputsAvailable)
            assertEquals(ready, lost.inputsAvailable(true))
            // Recovery cannot override independent audio interruption guards.
            PauseReason.entries.forEach { reason ->
                val paused = ready.pause(reason, true).inputsAvailable(false)
                assertFalse(paused.shouldRun)
                assertFalse(paused.inputsAvailable(true).shouldRun)
            }
            assertFalse(lost.stop().inputsAvailable(true).inputsAvailable)
            assertFalse(lost.stop().inputsAvailable(true).shouldRun)
        }
    }

    @Test fun `stop cancels resume after a call or focus gain`() {
        val stopped = HandsFreeState().start(0).pause(PauseReason.CALL, true).stop()
        assertFalse(stopped.pause(PauseReason.CALL, false).pause(PauseReason.FOCUS, false).shouldRun)
    }

    @Test fun `mode switch clears mute and mute is unavailable while paused or in ptt`() {
        val muted = HandsFreeState().start(0).toggleMute(1)
        assertTrue(muted.muted)
        assertFalse(muted.toggleMute(2).muted)
        val ptt = muted.toggleMode(2)
        assertTrue(ptt.ptt)
        assertFalse(ptt.muted)
        assertEquals(ptt, ptt.toggleMute(3))
        val paused = HandsFreeState().start(0).pause(PauseReason.CALL, true)
        assertEquals(paused, paused.toggleMute(3))
    }

    @Test fun `idle expiry has an exact boundary and activity resets it`() {
        val active = HandsFreeState().start(10_000)
        assertFalse(active.expired(69_999, 60_000))
        assertTrue(active.expired(70_000, 60_000))
        assertFalse(active.activity(69_000).expired(70_000, 60_000))
        assertFalse(active.stop().expired(100_000, 60_000))
        assertTrue(active.pause(PauseReason.CALL, true).expired(70_000, 60_000))
        assertTrue(HandsFreeState().arm(0).expired(60_000, 60_000))
    }

    @Test fun `idle preference is bounded and repeated starts are idempotent`() {
        assertEquals(1, HandsFreeState.idleMinutes(-1))
        assertEquals(60, HandsFreeState.idleMinutes(500))
        assertEquals(5, HandsFreeState.idleMinutes(5))
        val started = HandsFreeState().start(0)
        assertEquals(started, started.start(0))
    }
    @Test fun `notification modes cycle through Over without allowing mute or changing pause guards`() {
        val auto = HandsFreeState().start(0).toggleMute(1).pause(PauseReason.CALL, true)
        val ptt = auto.toggleMode(2)
        val over = ptt.toggleMode(3)
        assertEquals(ConversationMode.OVER, over.mode)
        assertFalse(over.ptt)
        assertFalse(over.muted)
        assertFalse(over.shouldRun)
        assertEquals(setOf(PauseReason.CALL), over.pauses)
        val resumed = over.pause(PauseReason.CALL, false)
        assertTrue(resumed.shouldRun)
        assertEquals(resumed, resumed.toggleMute(4))
        assertEquals(ConversationMode.AUTO, resumed.toggleMode(5).mode)
    }

    @Test fun `all saved modes survive arming call focus and capture stop restart`() {
        ConversationMode.entries.forEach { mode ->
            val controls = HandsFreeState(mode = mode).arm(0).captureHold(1)
            val paused = controls.pause(PauseReason.CALL, true).pause(PauseReason.FOCUS, true)
            assertFalse(paused.shouldRun)
            assertFalse(paused.pause(PauseReason.CALL, false).shouldRun)
            val resumed = paused.pause(PauseReason.CALL, false).pause(PauseReason.FOCUS, false)
            assertTrue(resumed.shouldRun)
            assertEquals(mode, resumed.mode)
            val restarted = resumed.captureHold(2).captureHold(3)
            assertTrue(restarted.shouldRun)
            assertEquals(mode, restarted.mode)
            assertFalse(paused.stop().pause(PauseReason.CALL, false).pause(PauseReason.FOCUS, false).shouldRun)
        }
    }

}
