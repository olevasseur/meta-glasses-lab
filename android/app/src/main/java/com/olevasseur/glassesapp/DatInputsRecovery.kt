package com.olevasseur.glassesapp

import com.meta.wearable.dat.core.session.DeviceSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class DatInputsFailure(message: String) : Exception(message)

/** The caller owns cancellation (disarm/device change), including during backoff. */
internal suspend fun recoverDatInputs(report: (String) -> Unit, attempt: suspend (ready: () -> Unit) -> String) {
    val delays = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
    var retry = 0
    var number = 0L
    while (currentCoroutineContext().isActive) {
        report("DAT inputs attempt=${++number} acquiring")
        val outcome = try { attempt { report("DAT inputs attempt=$number outcome=ACTIVE") } }
        catch (_: TimeoutCancellationException) { "activation timed out" }
        catch (e: CancellationException) { throw e }
        catch (e: DatInputsFailure) { e.message ?: "DAT failure" }
        catch (e: Exception) { "exception=${e.javaClass.simpleName}" }
        val waitMs = delays[retry]
        report("DAT inputs attempt=$number outcome=$outcome; controls unavailable; retry in ${waitMs / 1_000}s; use screen/notification")
        retry = (retry + 1).coerceAtMost(delays.lastIndex)
        delay(waitMs)
    }
}

/** STOPPED is terminal; PAUSED retains this session and its capability collectors. */
internal suspend fun awaitDatInputsSession(
    states: StateFlow<DeviceSessionState>,
    attach: suspend () -> Unit,
): String = coroutineScope {
    var initial: DeviceSessionState
    do {
        initial = withTimeout(15_000) {
            states.first { it == DeviceSessionState.STARTED || it == DeviceSessionState.PAUSED || it.ended() }
        }
        if (initial == DeviceSessionState.PAUSED) states.first { it != DeviceSessionState.PAUSED }
    } while (initial == DeviceSessionState.PAUSED)
    if (initial.ended()) return@coroutineScope "session=$initial before inputs"
    val inputs = launch { attach() }
    try { "session=${states.first { it.ended() }}" }
    finally { inputs.cancel() }
}

private fun DeviceSessionState.ended() = this == DeviceSessionState.STOPPED || this == DeviceSessionState.STOPPING
