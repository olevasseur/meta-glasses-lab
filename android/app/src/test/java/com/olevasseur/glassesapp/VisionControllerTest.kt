package com.olevasseur.glassesapp

import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisionControllerTest {
    private fun ToolResult.getString(key: String) = response.getString(key)
    private fun look(question: Any = "What do I see?") = FunctionCall("1", "look", JSONObject().put("question", question))

    @Test fun imageUsesRealtimeVideoWithJpegAndPreservesBytes() {
        val jpeg = byteArrayOf(-1, -40, 0, 42, -1, -39)
        val root = JSONObject(LiveProtocol.image(jpeg))
        assertEquals(setOf("realtimeInput"), root.keys().asSequence().toSet())
        val realtime = root.getJSONObject("realtimeInput")
        assertEquals(setOf("video"), realtime.keys().asSequence().toSet())
        val video = realtime.getJSONObject("video")
        assertEquals("image/jpeg", video.getString("mimeType"))
        assertArrayEquals(jpeg, Base64.getDecoder().decode(video.getString("data")))
    }

    @Test fun dimensionsRespectLongSideAspectRatioAndNeverUpscale() {
        assertEquals(576 to 1024, visionDimensions(720, 1280))
        assertEquals(1024 to 576, visionDimensions(1920, 1080))
        assertEquals(640 to 360, visionDimensions(640, 360))
        assertEquals(1 to 1024, visionDimensions(1, 4096))
        assertThrows(IllegalArgumentException::class.java) { visionDimensions(0, 5) }
    }

    @Test fun localToolsNeverHitBackendAndUnknownClientToolsFailClosed() = runTest {
        val local = mutableListOf<String>()
        val remote = mutableListOf<String>()
        for (name in listOf("look", "set_watch_mode", "future_local", "append_to_doc")) {
            val result = routeTool(FunctionCall("1", name, JSONObject()), setOf("future_local"),
                { local += it.name; ToolResult(JSONObject()) }, { remote += it.name; JSONObject() })
            if (name == "future_local") assertEquals("error", result.getString("status"))
        }
        assertEquals(listOf("look", "set_watch_mode"), local)
        assertEquals(listOf("append_to_doc"), remote)
    }

    @Test fun lookSendsBeforeAcknowledgementAndStopsCameraAfterIdle() = runTest {
        val events = mutableListOf<String>()
        val controller = VisionController(this, { events += "capture"; byteArrayOf(1) },
            { events += "send"; true }, { events += "stop" }, {}, {})
        assertTrue(events.isEmpty()) // Lazy: no camera at construction.
        val result = controller.tool(look())
        assertArrayEquals(byteArrayOf(1), result.jpeg)
        events += result.getString("status")
        assertEquals(listOf("capture", "send", "frame_sent"), events)
        advanceTimeBy(4_000)
        controller.tool(look()) // A fresh look resets idle shutdown.
        advanceTimeBy(4_999); runCurrent()
        assertFalse("stop" in events)
        advanceTimeBy(1); runCurrent()
        assertEquals("stop", events.last())
    }

    @Test fun visualToolResponseBindsImageToCallWithoutChangingStatusPayload() {
        val call = look()
        val response = JSONObject().put("status", "frame_sent")
        val wire = JSONObject(LiveProtocol.toolResponse(call, response, byteArrayOf(4, 2)))
            .getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals(call.id, wire.getString("id"))
        assertEquals("look", wire.getString("name"))
        assertEquals("{\"status\":\"frame_sent\"}", wire.getJSONObject("response").toString())
        val blob = wire.getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData")
        assertEquals("image/jpeg", blob.getString("mimeType"))
        assertArrayEquals(byteArrayOf(4, 2), Base64.getDecoder().decode(blob.getString("data")))
        assertFalse(JSONObject(LiveProtocol.toolResponse(call, toolError("Camera unavailable")))
            .getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0).has("parts"))
    }

    @Test fun badArgumentsFailedSendAndTimeoutNeverClaimFrameSent() = runTest {
        var captures = 0
        val controller = VisionController(this, { captures++; byteArrayOf(1) }, { false }, {}, {}, {})
        assertEquals("error", controller.tool(look(17)).getString("status"))
        assertEquals("error", controller.tool(look(" ")).getString("status"))
        assertEquals("error", controller.tool(FunctionCall("2", "set_watch_mode", JSONObject().put("on", "true"))).getString("status"))
        assertEquals(0, captures)
        assertEquals("error", controller.tool(look()).getString("status"))
        val stalled = VisionController(this, { awaitCancellation() }, { fail("Should not send"); true }, {}, {}, {})
        val result = async { stalled.tool(look()) }
        advanceTimeBy(15_000); runCurrent()
        assertEquals("error", result.await().getString("status"))
        controller.stop(); stalled.stop()
    }

    @Test fun watchSendsAtMostOncePerSecondAndRepeatedEnableDoesNotExtendLimit() = runTest {
        var frames = 0
        var enabled = false
        var stops = 0
        val controller = VisionController(this, { byteArrayOf(1) }, { frames++; true }, { stops++ }, { enabled = it }, {})
        controller.setWatchMode(true); runCurrent()
        assertTrue(enabled)
        assertEquals(1, frames)
        advanceTimeBy(999); runCurrent(); assertEquals(1, frames)
        advanceTimeBy(1); runCurrent(); assertEquals(2, frames)
        advanceTimeBy(59_000); controller.setWatchMode(true)
        advanceTimeBy(60_000); runCurrent()
        assertFalse(enabled)
        assertEquals(120, frames)
        assertTrue(stops > 0)
        advanceTimeBy(5_000); assertEquals(120, frames)
    }

    @Test fun stopCancelsInFlightCaptureAndWatchWithoutSendingStaleFrames() = runTest {
        var sent = false
        var cancelled = false
        var enabled = false
        val controller = VisionController(this,
            { try { delay(10_000); byteArrayOf(1) } finally { cancelled = true } },
            { sent = true; true }, {}, { enabled = it }, {})
        controller.setWatchMode(true); runCurrent()
        controller.stop(); runCurrent()
        advanceTimeBy(20_000)
        assertTrue(cancelled)
        assertFalse(sent)
        assertFalse(enabled)
    }

    @Test fun disconnectDuringLookReturnsAnErrorAndWatchAndLookShareRateLimit() = runTest {
        val times = mutableListOf<Long>()
        var stalled = false
        val controller = VisionController(this, {
            if (stalled) awaitCancellation() else byteArrayOf(1)
        }, { times += testScheduler.currentTime; true }, {}, {}, {})
        controller.setWatchMode(true); runCurrent()
        val look = async { controller.tool(look()) }
        advanceTimeBy(999); runCurrent()
        assertEquals(listOf(0L), times)
        advanceTimeBy(1); runCurrent()
        assertEquals("frame_sent", look.await().getString("status"))
        assertEquals(listOf(0L, 1000L), times)
        controller.stop(); runCurrent()
        controller.setWatchMode(true); runCurrent()
        assertEquals(listOf(0L, 1000L), times)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf(0L, 1000L, 2000L), times)
        controller.stop(); runCurrent()
        stalled = true
        val interrupted = async { controller.tool(look()) }
        advanceTimeBy(1_000); runCurrent(); controller.stop(); runCurrent()
        assertEquals("error", interrupted.await().getString("status"))
        assertEquals(3, times.size)
    }

    @Test fun watchFailureTurnsOffAndAllowsFreshEnableWithoutOldCleanupStoppingIt() = runTest {
        var enabled = false
        val errors = mutableListOf<String>()
        var works = false
        val controller = VisionController(this, { check(works) { "Camera unavailable" }; byteArrayOf(1) },
            { true }, {}, { enabled = it }, { errors += it })
        controller.setWatchMode(true); runCurrent()
        assertFalse(enabled)
        assertEquals(listOf("Camera unavailable"), errors)
        works = true
        controller.setWatchMode(true); runCurrent()
        controller.stop()
        controller.setWatchMode(true); runCurrent()
        assertTrue(enabled)
        controller.stop(); runCurrent()
    }

    @Test fun simultaneousLooksSerializeCaptureAndCancellationPropagates() = runTest {
        var inFlight = 0
        var peak = 0
        val controller = VisionController(this, {
            inFlight++; peak = maxOf(peak, inFlight)
            try { delay(100); byteArrayOf(1) } finally { inFlight-- }
        }, { true }, {}, {}, {})
        val first = async { controller.tool(look()) }
        val second = async { controller.tool(look()) }
        advanceTimeBy(1_200); runCurrent()
        assertEquals("frame_sent", first.await().getString("status"))
        assertEquals("frame_sent", second.await().getString("status"))
        assertEquals(1, peak)
        val cancelled = async { controller.tool(look()) }
        advanceTimeBy(900); runCurrent(); cancelled.cancel(); runCurrent()
        assertTrue(cancelled.isCancelled)
        assertEquals(0, inFlight)
        controller.stop()
    }
}
