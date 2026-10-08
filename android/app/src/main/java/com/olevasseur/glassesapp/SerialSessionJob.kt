package com.olevasseur.glassesapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Main-thread DAT owner: cancellation must finish before another session can be acquired. */
internal class SerialSessionJob(private val scope: CoroutineScope) {
    private var job: Job? = null
    private var generation = 0
    private val ownership = Mutex()
    val active: Boolean get() = job?.isActive == true

    fun start(block: suspend (isCurrent: () -> Boolean) -> Unit) {
        if (active) return
        val current = ++generation
        val next = scope.launch(start = CoroutineStart.LAZY) {
            ownership.withLock { block { current == generation } }
        }
        job = next
        next.start()
    }

    fun stop() {
        generation++
        job?.cancel()
        // The ownership lock stays held through the cancelled session's finally block.
    }
}
