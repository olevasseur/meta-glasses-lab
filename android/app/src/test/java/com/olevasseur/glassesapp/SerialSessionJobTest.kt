package com.olevasseur.glassesapp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SerialSessionJobTest {
    @Test fun rapidRestartWaitsForCleanupAndRejectsStaleLostCallback() = runTest {
        val events = mutableListOf<String>()
        val owner = SerialSessionJob(backgroundScope)
        owner.start { current ->
            events += "acquire old"
            try { awaitCancellation() }
            finally {
                withContext(NonCancellable) { delay(100); events += "release old" }
                if (current()) events += "lost callback"
            }
        }
        runCurrent()
        owner.stop()
        owner.start { events += "acquire new"; awaitCancellation() }
        runCurrent()
        assertEquals(listOf("acquire old"), events)
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("acquire old", "release old", "acquire new"), events)
        owner.stop()
    }

    @Test fun stopWhileWaitingCannotAcquireOrLoseTrackOfEarlierCleanup() = runTest {
        val events = mutableListOf<String>()
        val owner = SerialSessionJob(backgroundScope)
        owner.start {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { delay(100); events += "released" } }
        }
        runCurrent(); owner.stop()
        owner.start { events += "cancelled start" }
        runCurrent(); owner.stop()
        owner.start { events += "last start" }
        runCurrent()
        assertTrue(events.isEmpty())
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf("released", "last start"), events)
    }

    @Test fun repeatedStartKeepsOneOwner() = runTest {
        val owner = SerialSessionJob(backgroundScope)
        var count = 0
        repeat(5) { owner.start { count++; awaitCancellation() } }
        runCurrent()
        assertEquals(1, count)
        assertTrue(owner.active)
        owner.stop(); runCurrent()
        assertFalse(owner.active)
    }
}
