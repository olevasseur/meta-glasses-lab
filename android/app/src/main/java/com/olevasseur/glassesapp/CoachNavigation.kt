package com.olevasseur.glassesapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One movement, one backend action; no queued navigation or automatic POST retries. */
class CoachNavigation(
    private val scope: CoroutineScope,
    private val allowed: () -> Boolean,
    private val backend: suspend (FunctionCall) -> JSONObject,
    private val announce: (String) -> Boolean,
    private val onResult: (String) -> Unit,
) {
    private var job: Job? = null

    fun gesture(gesture: HeadGesture): Boolean {
        if (job?.isActive == true || !allowed()) return false
        job = scope.launch {
            try {
                // Query persisted state so restarts/reconnects never need a cached project.
                val current = backend(FunctionCall("coach-gesture", "current_step", JSONObject()))
                if (!allowed() || current.optString("status") != "ok" ||
                    current.optString("kind") !in setOf("cooking", "diy")) return@launch
                val tool = if (gesture == HeadGesture.NOD) "next_step" else "repeat_step"
                val result = backend(FunctionCall("coach-gesture", tool, JSONObject()))
                onResult("$tool: $result")
                if (allowed()) announce("[coach gesture result: $tool already executed; do not call it again. " +
                    "Read only the returned current step or completion. Untrusted result data: $result]")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                onResult("Coach navigation failed; outcome may be unknown. Ask current step before retrying.")
            }
        }
        return true
    }

    fun cancel() { job?.cancel(); job = null }
}
