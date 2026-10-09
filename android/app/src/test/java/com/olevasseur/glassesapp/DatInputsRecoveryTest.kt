package com.olevasseur.glassesapp

import com.meta.wearable.dat.core.session.DeviceSessionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DatInputsRecoveryTest {
    @Test fun `stopped session is released then acquired and inputs attached again`() = runTest {
        val states = mutableListOf<MutableStateFlow<DeviceSessionState>>()
        val events = mutableListOf<String>()
        val log = mutableListOf<String>()
        val owner = SerialSessionJob(backgroundScope)
        owner.start {
            recoverDatInputs(log::add) { ready ->
                val state = MutableStateFlow(DeviceSessionState.STARTING).also(states::add)
                val number = states.size
                events += "acquire $number"
                try {
                    awaitDatInputsSession(state) {
                        events += "attach $number"
                        ready()
                        try { awaitCancellation() } finally { events += "detach $number" }
                    }
                } finally { events += "release $number" }
            }
        }
        runCurrent()
        states[0].value = DeviceSessionState.STARTED; runCurrent()
        states[0].value = DeviceSessionState.STOPPED; runCurrent()
        assertEquals(listOf("acquire 1", "attach 1", "detach 1", "release 1"), events)
        advanceTimeBy(999); runCurrent()
        assertEquals(1, states.size)
        advanceTimeBy(1); runCurrent()
        states[1].value = DeviceSessionState.STARTED; runCurrent()
        assertEquals(listOf("acquire 2", "attach 2"), events.takeLast(2))
        assertTrue(log.any { "attempt=2 outcome=ACTIVE" in it })
        assertTrue(log.any { "outcome=session=STOPPED" in it })
        owner.stop(); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, states.size)
    }

    @Test fun `creation errors back off to thirty seconds and disarm cancels retry`() = runTest {
        val times = mutableListOf<Long>()
        val log = mutableListOf<String>()
        val job = backgroundScope.launch {
            recoverDatInputs(log::add) {
                times += testScheduler.currentTime
                throw DatInputsFailure("createSession code=NO_ELIGIBLE_DEVICE")
            }
        }
        runCurrent()
        for (wait in listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 30_000L)) {
            advanceTimeBy(wait); runCurrent()
        }
        assertEquals(listOf(0L, 1_000L, 3_000L, 8_000L, 18_000L, 48_000L, 78_000L), times)
        assertTrue(log.any { "attempt=7 outcome=createSession code=NO_ELIGIBLE_DEVICE" in it })
        job.cancel(); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(7, times.size)
    }

    @Test fun `paused session waits for resume without reacquiring or reattaching`() = runTest {
        val state = MutableStateFlow(DeviceSessionState.STARTED)
        var acquired = 0
        var attached = 0
        val job = backgroundScope.launch {
            recoverDatInputs({}) {
                acquired++
                awaitDatInputsSession(state) { attached++; awaitCancellation() }
            }
        }
        runCurrent()
        state.value = DeviceSessionState.PAUSED; runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, acquired)
        state.value = DeviceSessionState.STARTED; runCurrent()
        assertEquals(1, attached)
        job.cancel()
    }

    @Test fun `startup timeout releases session and retries and early stop does not attach`() = runTest {
        var attempts = 0
        var releases = 0
        val log = mutableListOf<String>()
        val job = backgroundScope.launch {
            recoverDatInputs(log::add) {
                attempts++
                try {
                    awaitDatInputsSession(MutableStateFlow(if (attempts == 1) DeviceSessionState.STARTING else DeviceSessionState.STOPPED)) {
                        fail("Must not attach inputs before STARTED")
                    }
                } finally { releases++ }
            }
        }
        runCurrent(); advanceTimeBy(15_000); runCurrent()
        assertEquals(1, releases)
        assertTrue(log.any { "outcome=activation timed out" in it })
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, attempts)
        assertEquals(2, releases)
        assertTrue(log.any { "session=STOPPED before inputs" in it })
        job.cancel()
    }

    @Test fun `pause before first started suspends startup timeout until resume`() = runTest {
        val state = MutableStateFlow(DeviceSessionState.STARTING)
        var attempts = 0
        var attached = 0
        val job = backgroundScope.launch {
            recoverDatInputs({}) {
                attempts++
                awaitDatInputsSession(state) { attached++; awaitCancellation() }
            }
        }
        runCurrent()
        state.value = DeviceSessionState.PAUSED; runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, attempts)
        assertEquals(0, attached)
        state.value = DeviceSessionState.STARTED; runCurrent()
        assertEquals(1, attached)
        job.cancel()
    }

    @Test fun `device error and stopping both recover and cancellation adds no retry`() = runTest {
        val state = MutableStateFlow(DeviceSessionState.STARTED)
        val log = mutableListOf<String>()
        var attempts = 0
        val job = backgroundScope.launch {
            recoverDatInputs(log::add) {
                attempts++
                awaitDatInputsSession(state) {
                    if (attempts == 1) throw DatInputsFailure("session code=SESSION_ENDED_BY_DEVICE")
                    awaitCancellation()
                }
            }
        }
        runCurrent()
        assertTrue(log.any { "SESSION_ENDED_BY_DEVICE" in it })
        advanceTimeBy(1_000); runCurrent()
        state.value = DeviceSessionState.STOPPING; runCurrent()
        assertTrue(log.any { "outcome=session=STOPPING" in it })
        job.cancel(); runCurrent()
        val count = log.size
        advanceTimeBy(60_000); runCurrent()
        assertEquals(count, log.size)
        assertEquals(2, attempts)
    }
}
