package com.olevasseur.glassesapp

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One optional cue for a slow call; completion, cancellation and failure cancel the timer. */
internal suspend fun <T> withToolWaitCue(enabled: Boolean, cue: () -> Unit, block: suspend () -> T): T = coroutineScope {
    val timer = if (enabled) launch { delay(1500); runCatching(cue) } else null
    try { block() } finally { timer?.cancel() }
}
