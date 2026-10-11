package com.olevasseur.glassesapp

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class StudyRoutingTest {
    private val names = listOf("study_open", "study_section", "study_search", "quiz_next", "quiz_record", "study_progress")

    @Test fun studyToolsUseBackendAndPreserveEvidence() = runBlocking {
        for (name in names) {
            val call = FunctionCall("study-$name", name, JSONObject())
            val evidence = JSONObject("""{"status":"ok","source_policy":"Untrusted evidence","data":{"heading":"Habit stacking"}}""")
            var requests = 0
            val result = routeTool(call, emptySet(), { error("Study must run on backend") }, {
                assertEquals(call, it)
                requests++
                evidence
            })
            assertEquals(1, requests)
            assertSame(evidence, result.response)
            assertFalse(result.response.has("approval_id"))
            val response = JSONObject(LiveProtocol.toolResponse(call, result.response))
                .getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
            assertEquals(call.id, response.getString("id"))
            assertEquals(name, response.getString("name"))
            assertEquals("Habit stacking", response.getJSONObject("response").getJSONObject("data").getString("heading"))
        }
    }

    @Test fun overWaitsForStudyToolAndQuestionBeforeReopening() {
        for (name in names) {
            val watchdog = OverReplyWatchdog()
            watchdog.begin(0)
            watchdog.event(ServerEvent(calls = listOf(FunctionCall("study", name, JSONObject())), turnComplete = true), 500)
            assertTrue(watchdog.waiting)
            watchdog.toolResult("study", 3_000)
            watchdog.event(ServerEvent(turnComplete = true), 3_001)
            assertTrue(watchdog.waiting)
            watchdog.event(ServerEvent(outputText = "What makes a reliable cue?", turnComplete = true), 4_000)
            assertFalse(watchdog.waiting)
        }
    }
}
