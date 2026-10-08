package com.olevasseur.glassesapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** Cumulative reports make retries idempotent; provider metadata describes each generation. */
class LiveUsageCounter(val model: String, private val started: Long, val id: String = UUID.randomUUID().toString()) {
    private var input: Long? = null
    private var output: Long? = null
    private var total: Long? = null

    fun add(metadata: JSONObject?) {
        if (metadata == null) return
        fun add(previous: Long?, key: String): Long? =
            if (metadata.has(key) && !metadata.isNull(key)) (previous ?: 0) + metadata.optLong(key, 0).coerceAtLeast(0) else previous
        input = add(input, "promptTokenCount")
        output = add(output, "responseTokenCount")
        total = add(total, "totalTokenCount")
    }

    fun snapshot(now: Long): JSONObject = JSONObject().put("id", id).put("model", model)
        .put("seconds", (now - started).coerceAtLeast(0) / 1000.0).apply {
            input?.let { put("input_tokens", it) }
            output?.let { put("output_tokens", it) }
            total?.let { put("total_tokens", it) }
        }
}

class UsageReporter(private val scope: CoroutineScope, private val backend: BackendClient, private val now: () -> Long) {
    private var counter: LiveUsageCounter? = null
    private var heartbeat: Job? = null

    fun start(model: String) {
        if (counter != null) return
        counter = LiveUsageCounter(model, now())
        heartbeat = scope.launch {
            while (true) { send(counter?.snapshot(now()) ?: break); delay(30_000) }
        }
    }

    fun add(metadata: JSONObject?) { counter?.add(metadata) }

    fun stop() {
        heartbeat?.cancel(); heartbeat = null
        val final = counter?.snapshot(now())
        counter = null
        if (final != null) scope.launch { send(final) }
    }

    private suspend fun send(report: JSONObject) {
        try { backend.reportUsage(report) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { DiagnosticLog.add("diagnostics", "Usage report unavailable; totals may be incomplete", "WARN") }
    }
}

fun quotaDiagnostic(report: JSONObject): String {
    val warnings = report.optJSONArray("warnings")
    val today = report.optJSONObject("today")
    return "Quota: ${today?.optDouble("live_minutes", 0.0)?.toInt() ?: 0} Live min · " +
        "${today?.optInt("text_calls", 0) ?: 0} text calls today\n" +
        (0 until (warnings?.length() ?: 0)).joinToString("\n") { warnings!!.optString(it) }
}

fun captureIntervalMs(result: JSONObject): Long =
    result.optLong("capture_interval_seconds", 20).coerceIn(20, 3600) * 1000
