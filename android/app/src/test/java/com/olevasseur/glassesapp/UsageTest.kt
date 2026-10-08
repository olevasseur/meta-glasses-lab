package com.olevasseur.glassesapp

import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UsageTest {
    @Test fun reportsAreCumulativeAndOmitUnknownTokens() {
        val counter = LiveUsageCounter("model", 1000, "id")
        assertFalse(counter.snapshot(2000).has("total_tokens"))
        val event = LiveProtocol.parse("""{"usageMetadata":{"promptTokenCount":100,"responseTokenCount":20,"totalTokenCount":120}}""")
        counter.add(event.usage)
        counter.add(JSONObject().put("totalTokenCount", 30))
        val report = counter.snapshot(61000)
        assertEquals(60.0, report.getDouble("seconds"), 0.001)
        assertEquals(150, report.getInt("total_tokens"))
        assertEquals(report.toString(), counter.snapshot(61000).toString())
        assertEquals(0.0, counter.snapshot(0).getDouble("seconds"), 0.001)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun automaticCaptureUsesBackendsThrottleAndResetsOnRestart() = runTest {
        var captures = 0
        val controller = VisualMemoryController(this,
            { captures++; byteArrayOf(1) }, { captures.toLong() },
            { _, _, _ -> JSONObject().put("status", "quota_throttled").put("capture_interval_seconds", 60) },
            { JSONObject() }, { testScheduler.currentTime }, {}, {}, {})
        controller.setEnabled(true, 2)
        runCurrent()
        assertEquals(1, captures)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, captures)
        advanceTimeBy(40_000); runCurrent()
        assertEquals(2, captures)
        controller.stop()
    }

    @Test fun throttleAndWarningsAreVisible() {
        assertEquals(60000L, captureIntervalMs(JSONObject().put("capture_interval_seconds", 60)))
        assertEquals(20000L, captureIntervalMs(JSONObject().put("capture_interval_seconds", -1)))
        assertEquals(3600000L, captureIntervalMs(JSONObject().put("capture_interval_seconds", Long.MAX_VALUE)))
        val report = JSONObject("""{"today":{"live_minutes":12,"text_calls":40},"warnings":["Near daily quota"]}""")
        assertTrue(quotaDiagnostic(report).contains("Near daily quota"))
        assertTrue(quotaDiagnostic(report).contains("12 Live min"))
    }
}
