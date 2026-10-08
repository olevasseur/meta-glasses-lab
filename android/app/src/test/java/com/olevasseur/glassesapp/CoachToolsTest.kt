package com.olevasseur.glassesapp

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CoachToolsTest {
    @Test fun progressRetrievesProjectThenLooksAndBindsImage() = runTest {
        val order = mutableListOf<String>()
        val jpeg = byteArrayOf(1, 2)
        val result = coachTool(FunctionCall("id", "check_progress", JSONObject().put("question", "Done?")),
            backend = { order += it.name; JSONObject().put("status", "ok").put("title", "Pasta") },
            vision = { order += it.name; assertEquals("Done?", it.args.getString("question")); ToolResult(JSONObject().put("status", "frame_sent"), jpeg) },
            timer = { error("Not a timer") })
        assertEquals(listOf("current_step", "look"), order)
        assertSame(jpeg, result.jpeg)
        assertEquals("Pasta", result.response.getJSONObject("project").getString("title"))
    }
    @Test fun noProjectOrInvalidQuestionDoesNotCapture() = runTest {
        for (question in listOf("", "Done?")) {
            val result = coachTool(FunctionCall("id", "check_progress", JSONObject().put("question", question)),
                backend = { JSONObject().put("status", "no_project") }, vision = { error("Should not look") }, timer = { error("Not a timer") })
            assertNull(result.jpeg)
            assertNotEquals("frame_sent", result.response.getString("status"))
        }
    }
    @Test fun cameraFailureIsNotProgressSuccess() = runTest {
        val result = coachTool(FunctionCall("id", "check_progress", JSONObject().put("question", "Done?")),
            backend = { JSONObject().put("status", "ok") }, vision = { ToolResult(toolError("Camera denied")) }, timer = { error("Not a timer") })
        assertEquals("error", result.response.getString("status"))
        assertNull(result.jpeg)
    }
    @Test fun coachClientToolsNeverGoToBackendEvenWithOldToken() = runTest {
        for (name in COACH_TOOL_NAMES) {
            var called = false
            routeTool(FunctionCall("id", name, JSONObject()), emptySet(),
                local = { called = true; ToolResult(JSONObject()) }, backend = { error("Client tool sent to server") })
            assertTrue(called)
        }
    }
    @Test fun unsupportedNodCannotClaimEnabled() = runTest {
        val result = coachTool(FunctionCall("id", "set_nod_navigation", JSONObject().put("on", true)),
            backend = { error("No backend") }, vision = { error("No vision") }, timer = { error("No timer") })
        assertEquals("error", result.response.getString("status"))
    }
}
