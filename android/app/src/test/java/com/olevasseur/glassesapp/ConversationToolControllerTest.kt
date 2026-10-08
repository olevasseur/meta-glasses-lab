package com.olevasseur.glassesapp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConversationToolControllerTest {
    private fun call(name: String, key: String = "on", value: Any = true) =
        FunctionCall("test", name, JSONObject().put(key, value))

    @Test fun meetingRejectsContinuousFeaturesButAllowsAddressedLookAndCleanup() = runBlocking {
        val invoked = mutableListOf<String>()
        suspend fun route(call: FunctionCall): ToolResult {
            invoked += call.name
            return ToolResult(JSONObject().put("status", "ok"))
        }
        val tools = ConversationToolController({ FeatureAccess(false, true, false) }, ::route, ::route, ::route,
            { null }, { null }, ::route)
        for (name in listOf("start_workout", "read_text", "submit_reading", "reading_control",
            "set_watch_mode", "set_nod_navigation", "set_visual_memory", "remember_this")) {
            assertEquals(name, "error", tools.tool(call(name)).response.getString("status"))
        }
        assertTrue(invoked.isEmpty())
        assertEquals("unavailable", tools.tool(call("await_gesture_confirmation")).response.getString("status"))
        for (request in listOf(call("look"), call("check_progress"), call("delete_visual_memory"),
            call("set_watch_mode", value = false), call("set_visual_memory", value = false),
            call("set_nod_navigation", value = false), call("reading_control", "action", "stop"), call("set_timer"))) {
            assertEquals("ok", tools.tool(request).response.getString("status"))
        }
        assertEquals(8, invoked.size)
    }

    @Test fun pausedOrTranslatingNeverInvokesLocalFeatures() = runBlocking {
        for (access in listOf(FeatureAccess(true, false, false), FeatureAccess(true, true, false), FeatureAccess(false, false, true))) {
            val tools = ConversationToolController({ access }, { error("visual") }, { error("coach") },
                { error("reading") }, { error("workout") }, { error("gesture") }, { error("vision") })
            for (name in listOf("look", "set_watch_mode", "remember_this", "delete_visual_memory", "set_timer",
                "read_text", "start_workout", "await_gesture_confirmation")) {
                assertEquals("error", tools.tool(call(name)).response.getString("status"))
            }
        }
    }

    @Test fun assistantRoutesEachFeatureOnceWithoutChangingItsResult() = runBlocking {
        val invoked = mutableListOf<String>()
        fun result(name: String): ToolResult { invoked += name; return ToolResult(JSONObject().put("route", name)) }
        val tools = ConversationToolController({ FeatureAccess(false, false, false) }, { result("visual") },
            { result("coach") }, { result("reading") }, { if (it.name == "start_workout") result("workout").response else null },
            { if (it.name == "await_gesture_confirmation") result("gesture").response else null }, { result("vision") })
        for ((name, route) in listOf("remember_this" to "visual", "set_timer" to "coach", "read_text" to "reading",
            "start_workout" to "workout", "await_gesture_confirmation" to "gesture", "look" to "vision")) {
            assertEquals(route, tools.tool(call(name)).response.getString("route"))
        }
        assertEquals(listOf("visual", "coach", "reading", "workout", "gesture", "vision"), invoked)
    }

    @Test fun motionStopsForEveryPauseAndExclusivePersonaButSurvivesNetworkReconnect() {
        val active = ConversationState(running = true, connected = true, handsFree = HandsFreeState().start(0))
        assertTrue(active.motionAllowed)
        assertTrue(active.copy(connected = false).motionAllowed) // Local workout continues through network loss.
        assertFalse(active.copy(running = false).motionAllowed)
        assertFalse(active.copy(stopping = true).motionAllowed)
        assertFalse(active.copy(meeting = true).motionAllowed)
        assertFalse(active.copy(settings = active.settings.copy(translationLanguage = "es")).motionAllowed)
        assertFalse(active.copy(handsFree = active.handsFree.captureHold(1)).motionAllowed)
        for (reason in PauseReason.entries) {
            assertFalse(active.copy(handsFree = active.handsFree.pause(reason, true)).motionAllowed)
        }
    }
}
