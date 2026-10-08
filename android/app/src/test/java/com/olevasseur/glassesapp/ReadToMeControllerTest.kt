package com.olevasseur.glassesapp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadToMeControllerTest {
    private fun call(name: String, vararg args: Pair<String, Any>) =
        FunctionCall("test", name, JSONObject().apply { args.forEach { put(it.first, it.second) } })
    private fun read(mode: String = "verbatim", retry: Boolean = false) = call("read_text", "mode" to mode, "retry" to retry)
    private fun control(action: String) = call("reading_control", "action" to action)

    @Test fun laplacianDetectsFlatAndBlurredTextComparedToSharpEdges() {
        val width = 64
        val sharp = IntArray(width * width) { if (it % width % 8 < 4) 0 else 255 }
        val blurred = IntArray(sharp.size) { i ->
            (-3..3).sumOf { offset -> sharp[(i + offset).coerceIn(0, sharp.lastIndex)] } / 7
        }
        assertEquals(0.0, laplacianVariance(IntArray(100) { 128 }, 10, 10), 0.0)
        assertTrue(laplacianVariance(sharp, width, width) > 10 * laplacianVariance(blurred, width, width))
        assertThrows(IllegalArgumentException::class.java) { laplacianVariance(IntArray(4), 2, 2) }
    }

    @Test fun modesRequireValidArgumentsAndLocalToolsNeverGoToBackend() = runTest {
        var captures = 0
        val reader = ReadToMeController({ captures++; byteArrayOf(1) }, {}, {})
        for (request in listOf(read("unknown"), read("find"), call("read_text", "mode" to 3),
            call("read_text", "mode" to "verbatim", "retry" to "true"), read(retry = true))) {
            assertEquals("error", reader.tool(request).response.getString("status"))
        }
        assertEquals(0, captures)
        for (mode in ReadToMeController.MODES) {
            val result = reader.tool(call("read_text", "mode" to mode, "question" to "Price?"))
            assertEquals("frame_sent", result.response.getString("status"))
            assertArrayEquals(byteArrayOf(1), result.jpeg)
        }
        for (name in ReadToMeController.TOOL_NAMES) {
            routeTool(call(name), emptySet(), { ToolResult(JSONObject()) }, { fail("Local tool reached backend"); JSONObject() })
        }
    }

    @Test fun blurryCaptureAllowsOnlyTwoRetriesAndNeverAttachesBadImage() = runTest {
        var attempts = 0
        val reader = ReadToMeController({ attempts++; throw BlurryTextFrame() }, {}, {})
        for (attempt in 1..3) {
            val result = reader.tool(read(retry = attempt > 1))
            assertEquals(if (attempt < 3) "retry_required" else "error", result.response.getString("status"))
            assertNull(result.jpeg)
        }
        assertEquals("error", reader.tool(read(retry = true)).response.getString("status"))
        assertEquals(3, attempts)
    }

    @Test fun successfulRetryAcceptsOnlyMatchingPageAndCaptureFailuresDoNotCreateText() = runTest {
        var count = 0
        val reader = ReadToMeController({ if (++count == 1) throw BlurryTextFrame() else byteArrayOf(1) }, {}, {})
        reader.tool(read())
        assertEquals("error", reader.tool(read("summary", true)).response.getString("status"))
        val page = reader.tool(read(retry = true)).response.getInt("page_id")
        assertEquals("error", reader.tool(call("submit_reading", "page_id" to page + 1, "text" to "stale")).response.getString("status"))
        assertEquals("error", reader.tool(call("submit_reading", "page_id" to page, "text" to " ")).response.getString("status"))
        assertEquals("ok", reader.tool(call("submit_reading", "page_id" to page.toDouble(), "text" to "ok")).response.getString("text"))
        assertEquals("error", reader.tool(call("submit_reading", "page_id" to page, "text" to "duplicate")).response.getString("status"))
    }

    @Test fun longPagePreservesEveryCharacterAndRepeatStopAndNextPageKeepPosition() = runTest {
        var captures = 0
        var stopped = false
        val reader = ReadToMeController({ captures++; byteArrayOf(1) }, {}, { stopped = true })
        val page = reader.tool(read()).response.getInt("page_id")
        val text = "A paragraph with visible words.\n".repeat(75)
        val first = reader.tool(call("submit_reading", "page_id" to page, "text" to text)).response
        assertEquals(first.getString("text"), reader.tool(control("repeat")).response.getString("text"))
        reader.tool(control("stop")); assertTrue(stopped)
        assertEquals(first.getString("text"), reader.tool(control("continue")).response.getString("text"))
        val reconstructed = StringBuilder(first.getString("text"))
        while (true) {
            val next = reader.tool(control("continue")).response
            if (next.getString("status") == "page_complete") break
            reconstructed.append(next.getString("text"))
        }
        assertEquals(text, reconstructed.toString())
        assertEquals(1, captures)
        val next = reader.tool(control("next_page")).response
        assertEquals(2, next.getInt("page")); assertEquals(2, captures)
        assertEquals("error", reader.tool(call("submit_reading", "page_id" to page, "text" to "old")).response.getString("status"))
        reader.clear()
        assertEquals("error", reader.tool(control("continue")).response.getString("status"))
    }

    @Test fun stopCancelsSharedCaptureAndRejectsLatePageSubmission() = runTest {
        var stopCamera = false
        val vision = VisionController(this, { byteArrayOf(0) }, { true }, { stopCamera = true }, {}, {})
        val reader = ReadToMeController({ vision.sendFrame { awaitCancellation() } }, vision::stop, {})
        val pending = async { reader.tool(read()) }
        runCurrent()
        reader.tool(control("stop")); runCurrent()
        assertTrue(stopCamera)
        assertEquals("error", pending.await().response.getString("status"))
        assertEquals("error", reader.tool(call("submit_reading", "page_id" to 1, "text" to "late")).response.getString("status"))
    }

    @Test fun readingCaptureUsesVisionRateLimitAndImageBoundResponse() = runTest {
        val times = mutableListOf<Long>()
        val vision = VisionController(this, { byteArrayOf(0) }, { times += testScheduler.currentTime; true }, {}, {}, {})
        val reader = ReadToMeController({ vision.sendFrame { byteArrayOf(42) } }, vision::stop, {})
        vision.tool(call("look", "question" to "object?"))
        val result = reader.tool(read())
        assertEquals(listOf(0L, 1000L), times)
        val wire = JSONObject(LiveProtocol.toolResponse(read(), result.response, result.jpeg))
            .getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("read_text", wire.getString("name"))
        assertEquals("Kg==", wire.getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData").getString("data"))
        vision.stop()
    }

    @Test fun lifecyclePauseRetainsInterruptedChunkButFreshSessionClearsIt() = runTest {
        var captures = 0
        var stops = 0
        var flushes = 0
        val reader = ReadToMeController({ captures++; byteArrayOf(1) }, { stops++ }, { flushes++ })
        val page = reader.tool(read()).response.getInt("page_id")
        val text = "Visible text. ".repeat(100)
        val first = reader.tool(call("submit_reading", "page_id" to page, "text" to text)).response
        reader.pause() // Call/focus pause or network reconnect.
        assertEquals(1, stops)
        assertEquals(1, flushes)
        assertEquals(first.toString(), reader.tool(control("continue")).response.toString())
        val next = reader.tool(control("continue")).response
        assertEquals(first.getInt("end"), next.getInt("start"))
        assertEquals(1, captures)
        reader.clear() // Session Stop or a fresh persona context.
        assertEquals("error", reader.tool(control("continue")).response.getString("status"))
        val pending = reader.tool(read()).response.getInt("page_id")
        reader.pause()
        assertEquals("error", reader.tool(call("submit_reading", "page_id" to pending, "text" to "late")).response.getString("status"))
    }
}
