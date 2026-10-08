package com.olevasseur.glassesapp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class DiagnosticLogTest {
    @Test fun diagnosticTransportUsesAuthAndLeavesBatchAvailableOnFailure() = kotlinx.coroutines.runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.start()
            val backend = BackendClient(okhttp3.OkHttpClient(), server.url("/").toString(), "test-secret")
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"status":"ok"}"""))
            backend.diagnosticHealth()
            val health = server.takeRequest()
            assertEquals("GET", health.method)
            assertEquals("/diagnostics/health", health.path)
            assertEquals("test-secret", health.getHeader("X-App-Secret"))
            val ring = DiagnosticRing()
            ring.add("audio", "route ready")
            val batch = org.json.JSONArray().put(ring.after(0).single().json())
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503).setBody("{}"))
            assertTrue(runCatching { backend.uploadLogs(batch) }.isFailure)
            val failed = server.takeRequest()
            assertEquals("/logs", failed.path)
            assertEquals("POST", failed.method)
            assertEquals("test-secret", failed.getHeader("X-App-Secret"))
            assertEquals(batch.toString(), org.json.JSONObject(failed.body.readUtf8()).getJSONArray("entries").toString())
            assertEquals(1, ring.after(0).size)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"accepted":1}"""))
            assertEquals(1, backend.uploadLogs(batch).getInt("accepted"))
            assertEquals(batch.toString(), org.json.JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("entries").toString())
        }
    }

    @Test fun featureTagsCoverLocalAndBackendTools() {
        mapOf("look" to "vision", "read_text" to "read-to-me", "append_to_doc" to "notes",
            "set_visual_memory" to "visual-memory", "start_translation" to "translator",
            "write_journal" to "journal", "codex_task" to "jobs", "start_meeting" to "meeting",
            "start_workout" to "workout", "remember" to "memory", "next_step" to "coach",
            "await_gesture_confirmation" to "gestures").forEach { (tool, feature) ->
            assertEquals(feature, diagnosticFeature(tool))
        }
        assertEquals("tools", diagnosticFeature("unknown"))
    }

    @Test fun unicodeBatchesStayBelowServerByteLimit() {
        val ring = DiagnosticRing()
        repeat(100) { ring.add("dat", "界".repeat(512)) }
        val batch = ring.after(0)
        assertTrue(batch.isNotEmpty() && batch.size < 100)
        val json = org.json.JSONObject().put("entries", org.json.JSONArray().apply { batch.forEach { put(it.json()) } })
        assertTrue(json.toString().toByteArray(Charsets.UTF_8).size < 131072)
        assertTrue(ring.after(batch.last().sequence).isNotEmpty())
    }

    @Test fun ringBoundsRetryCursorAndRedaction() {
        val ring = DiagnosticRing(2) { listOf("private-secret") }
        ring.add("audio", "private-secret auth_tokens/hidden\nready")
        val first = ring.after(0).single()
        assertFalse(first.event.contains("private-secret"))
        assertFalse(first.event.contains("auth_tokens/hidden"))
        assertFalse(first.event.contains('\n'))
        assertEquals(first, ring.after(0).single()) // Retry gets stable ID.
        ring.add("vision", "captured")
        ring.add("motion", "live")
        assertEquals(1, ring.dropped)
        assertEquals(listOf("vision", "motion"), ring.after(first.sequence).map { it.feature })
        assertEquals(1, ring.after(first.sequence, 1).size)
        assertEquals(0, ring.after(3).size)
        assertFalse(ring.export().contains(first.id))
    }

    @Test fun latencyIgnoresSilenceAndMeasuresOncePerSpeech() {
        var clock = 100L
        val latency = ResponseLatency { clock }
        latency.input(ByteArray(3200))
        assertNull(latency.firstAudio())
        val speech = ByteArray(3200)
        repeat(1600) { i ->
            val sample = (sin(i * 2 * Math.PI * 220 / 16000) * 5000).toInt()
            speech[2 * i] = sample.toByte(); speech[2 * i + 1] = (sample shr 8).toByte()
        }
        latency.reset()
        latency.input(speech)
        clock = 400
        latency.activityEnd()
        clock = 900
        latency.input(ByteArray(3200))
        assertEquals(800L, latency.firstAudio())
        assertEquals(300L, latency.endWaitMs)
        assertNull(latency.firstAudio())
        latency.input(speech)
        assertNull(latency.firstAudio()) // More PCM/audio parts cannot produce extra latencies.
        latency.reset()
        assertNull(latency.endWaitMs)
        assertNull(latency.firstAudio())
    }

    @Test fun audioProbesHaveExpectedDurationAndBoundedAmplitude() {
        val beep = diagnosticBeep()
        assertEquals(12000, beep.size)
        val playback = diagnosticPlayback(ByteArray(160000))
        assertEquals(2, diagnosticPlayback(byteArrayOf(1, 0)).size)
        assertEquals(240000, playback.size) // 5 seconds at 24 kHz mono PCM16.
        assertTrue(playback.all { it == 0.toByte() })
        assertTrue(beep.any { it != 0.toByte() })
        val converted = diagnosticPlayback(byteArrayOf(1, 0, 2, 0))
        assertArrayEquals(byteArrayOf(1, 0, 1, 0, 2, 0), converted)
    }
}
