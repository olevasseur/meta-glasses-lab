package com.olevasseur.glassesapp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolWaitCueTest {
    @Test fun onlySlowEnabledCallsCueOnce() = runTest {
        var cues = 0
        withToolWaitCue(false, { cues++ }) { delay(2000) }
        withToolWaitCue(true, { cues++ }) { delay(1499) }
        assertEquals(0, cues)
        val slow = launch { withToolWaitCue(true, { cues++ }) { delay(5000) } }
        runCurrent()
        advanceTimeBy(1500); runCurrent()
        assertEquals(1, cues)
        slow.join()
        assertEquals(1, cues)
    }

    @Test fun brokenSpeakerCannotCancelTheTool() = runTest {
        val result = withToolWaitCue(true, { error("speaker unavailable") }) { delay(2000); "saved" }
        assertEquals("saved", result)
    }

    @Test fun cancellationAndFailureCannotLeaveDelayedCues() = runTest {
        var cues = 0
        val call = launch { withToolWaitCue(true, { cues++ }) { delay(5000) } }
        runCurrent()
        advanceTimeBy(500)
        call.cancel(); call.join()
        runCatching { withToolWaitCue(true, { cues++ }) { error("failed") } }
        advanceTimeBy(5000); runCurrent()
        assertEquals(0, cues)
    }
}
