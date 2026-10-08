package com.olevasseur.glassesapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

internal fun observeDiagnostics(scope: CoroutineScope, states: StateFlow<ConversationState>, backend: BackendClient) {
    scope.launch { DiagnosticLog.uploadLoop(backend) }
    scope.launch {
        var previous = emptyMap<String, String>()
        states.collect { s ->
            val values = mapOf("gemini" to s.gemini, "audio" to s.route,
                "dat" to "${s.glasses} · ${s.inputs}", "over" to s.overStatus,
                "vision" to "${s.camera} watch=${s.watchMode}",
                "visual-memory" to "enabled=${s.visualMemory} capturing=${s.visualCapturing}",
                "meeting" to "active=${s.meeting}", "translator" to "active=${s.translationLanguage != null}",
                "settings" to "mode=${s.settings.mode} mock=${s.mock} patience_ms=${s.settings.silenceMs}",
                "hands-free" to "running=${s.running} paused=${!s.handsFree.shouldRun}",
                "permissions" to "microphone=${s.permissions}")
            values.forEach { (feature, value) -> if (previous[feature] != value) DiagnosticLog.add(feature, value) }
            // Error text can contain user text from a tool. Log occurrence, show details only in existing UI.
            if (s.error != null && previous["error"] != s.error) DiagnosticLog.add("app", "error_shown_on_phone", "ERROR")
            previous = values + ("error" to (s.error ?: ""))
        }
    }
}

internal fun diagnosticBeep(): ByteArray = ByteArray(12_000).also { pcm ->
    repeat(pcm.size / 2) { i ->
        val sample = (sin(2 * PI * 660 * i / 24000) * 3000 * minOf(1.0, i / 240.0, (5999 - i) / 240.0)).toInt()
        pcm[i * 2] = sample.toByte(); pcm[i * 2 + 1] = (sample shr 8).toByte()
    }
}

internal fun diagnosticPlayback(pcm: ByteArray): ByteArray {
    require(pcm.size % 2 == 0)
    val samples = pcm.size / 2
    return ByteArray(samples * 3 / 2 * 2).also { out ->
        repeat(out.size / 2) { i ->
            val source = i * 2 / 3
            out[i * 2] = pcm[source * 2]; out[i * 2 + 1] = pcm[source * 2 + 1]
        }
    }
}
