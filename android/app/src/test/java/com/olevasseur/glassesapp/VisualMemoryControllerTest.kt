package com.olevasseur.glassesapp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisualMemoryControllerTest {
    private fun call(name: String, args: JSONObject = JSONObject()) = FunctionCall("v", name, args)

    @Test fun visualCaptureUsesAssistantLifecycleGatesInEveryTurnMode() {
        for (mode in ConversationMode.entries) {
            val ready = ConversationState(running = true, connected = true,
                settings = ConversationSettings(mode = mode),
                handsFree = HandsFreeState().start(0))
            assertTrue(ready.canCaptureVisual)
            assertFalse(ready.copy(connected = false).canCaptureVisual)
            assertFalse(ready.copy(stopping = true).canCaptureVisual)
            assertFalse(ready.copy(running = false).canCaptureVisual)
            assertFalse(ready.copy(meeting = true).canCaptureVisual)
            assertFalse(ready.copy(settings = ready.settings.copy(translationLanguage = "es")).canCaptureVisual)
            assertFalse(ready.copy(handsFree = ready.handsFree.stop()).canCaptureVisual)
            for (reason in PauseReason.entries) {
                assertFalse(ready.copy(handsFree = ready.handsFree.pause(reason, true)).canCaptureVisual)
            }
        }
    }

    @Test fun optInChimeDuplicatesPinAndTimeout() = runTest {
        var captures = 0; var uploads = 0; var chimes = 0; var indicator = false
        val policy = VisualMemoryController(this, { captures++; byteArrayOf(1) }, { 1L },
            { _, _, _ -> uploads++; JSONObject().put("status", "ok") }, { JSONObject() },
            { testScheduler.currentTime }, { indicator = it }, { chimes++ }, {})
        runCurrent(); assertEquals(0, captures)
        policy.setEnabled(true, 1); runCurrent()
        assertTrue(indicator); assertEquals(1, chimes); assertEquals(1, uploads)
        advanceTimeBy(20_000); runCurrent(); assertEquals(2, captures); assertEquals(1, uploads)
        val pinned = policy.tool(call("remember_this"))
        assertEquals("ok", pinned.response.getString("status")); assertEquals(2, uploads)
        policy.setEnabled(true, 120) // Repeat enable cannot extend timer.
        advanceTimeBy(40_000); runCurrent(); assertFalse(indicator); assertFalse(policy.active)
        policy.tool(call("remember_this")); assertEquals(2, chimes); assertFalse(indicator)
    }

    @Test fun freshStillMotionSkipsCaptureButStaleMotionFallsBackToHash() = runTest {
        var captures = 0
        val policy = VisualMemoryController(this, { captures++; byteArrayOf(1) }, { captures.toLong() },
            { _, _, _ -> JSONObject().put("status", "ok") }, { JSONObject() },
            { testScheduler.currentTime }, {}, {}, {})
        policy.setEnabled(true); runCurrent()
        advanceTimeBy(19_999)
        policy.motion(HeadMotionSample(1, 0.0, 0.0, 0.0, 9.8))
        advanceTimeBy(1); runCurrent(); assertEquals(1, captures)
        advanceTimeBy(20_000); runCurrent(); assertEquals(2, captures)
        policy.stop()
    }

    @Test fun stopCancelsPendingAndDeletePausesBeforeCallingBackend() = runTest {
        var uploads = 0; var deletes = 0
        lateinit var policy: VisualMemoryController
        policy = VisualMemoryController(this, { awaitCancellation() }, { 0L },
            { _, _, _ -> uploads++; JSONObject() }, {
                assertFalse(policy.active); deletes++; JSONObject().put("status", "ok")
            }, { testScheduler.currentTime }, {}, {}, {})
        policy.setEnabled(true); runCurrent()
        assertEquals("ok", policy.tool(call("delete_visual_memory")).response.getString("status"))
        runCurrent(); assertEquals(0, uploads); assertEquals(1, deletes)
        val snapshot = async { policy.tool(call("remember_this")) }
        runCurrent(); policy.stop(); runCurrent()
        assertEquals("error", snapshot.await().response.getString("status"))
        assertEquals(0, uploads)
    }

    @Test fun failureAndBadArgumentsNeverLeaveCaptureOn() = runTest {
        var indicator = false; var errors = 0
        val policy = VisualMemoryController(this, { error("camera") }, { 0L },
            { _, _, _ -> JSONObject() }, { JSONObject() }, { testScheduler.currentTime },
            { indicator = it }, {}, { errors++ })
        policy.setEnabled(true); runCurrent()
        assertFalse(indicator); assertFalse(policy.active); assertEquals(1, errors)
        for (args in listOf(JSONObject().put("on", "true"), JSONObject().put("on", true).put("minutes", 0),
            JSONObject().put("on", true).put("minutes", 1.5))) {
            assertEquals("error", policy.tool(call("set_visual_memory", args)).response.getString("status"))
        }
        assertFalse(indicator)
    }

    @Test fun differenceHashAndLocalRouting() = runTest {
        assertEquals(0L, visualDifferenceHash(IntArray(72) { 10 }))
        assertEquals(-1L, visualDifferenceHash(IntArray(72) { 9 - it % 9 }))
        for (name in VisualMemoryController.TOOL_NAMES) {
            var local = false
            routeTool(call(name), emptySet(), { local = true; ToolResult(JSONObject()) }, { fail("remote"); JSONObject() })
            assertTrue(local)
        }
    }
    @Test fun overlappingSnapshotsKeepIndicatorUntilLastCaptureFinishes() = runTest {
        var indicator = false
        val policy = VisualMemoryController(this, { kotlinx.coroutines.delay(100); byteArrayOf(1) }, { 0L },
            { _, _, _ -> JSONObject().put("status", "ok") }, { JSONObject() },
            { testScheduler.currentTime }, { indicator = it }, {}, {})
        val first = async { policy.tool(call("remember_this")) }
        val second = async { policy.tool(call("remember_this")) }
        runCurrent(); assertTrue(indicator)
        advanceTimeBy(100); runCurrent(); first.await(); assertTrue(indicator)
        advanceTimeBy(100); runCurrent(); second.await(); assertFalse(indicator)
        policy.stop()
    }

}
