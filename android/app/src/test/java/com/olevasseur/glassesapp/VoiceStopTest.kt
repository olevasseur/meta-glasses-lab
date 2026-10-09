package com.olevasseur.glassesapp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceStopTest {
    @Test fun exactPhrasesOnly() {
        for (text in listOf("goodbye", " Good-bye! ", "STOP LISTENING.", "end conversation",
            "that's all, thanks", "That’s all, thanks!", "goodbye over", "stop listening, over.")) {
            assertTrue(text, VoiceStop.matches(text))
        }
        for (text in listOf("stop", "don't stop listening", "I said goodbye to my mom", "goodbye to my mom",
            "say goodbye", "that's all thanks for the recipe now read step two", "stop the meeting",
            "over", "goodbye over and over", "remember the phrase goodbye", "")) {
            assertFalse(text, VoiceStop.matches(text))
        }
    }

    @Test fun toolIsLocalAndWaitsForGoodbyeGenerationAndDevicePlaybackInEveryMode() = runTest {
        for (mode in ConversationMode.entries) {
            var controls = HandsFreeState(mode = mode).start(0).inputsAvailable(false)
            var idle = true
            var stops = 0
            val stop = VoiceStop(this, { controls.shouldRun }, { idle }, {
                stops++; controls = controls.captureHold(testScheduler.currentTime)
            })
            val reply = routeTool(FunctionCall("1", VoiceStop.TOOL, JSONObject()), setOf(VoiceStop.TOOL),
                stop::tool, { error("Must never call backend") })
            assertEquals("ok", reply.response.getString("status"))
            assertFalse(reply.response.has("approval_id"))
            stop.event(ServerEvent(turnComplete = true)) // Tool-call turn can finish before Bye.
            advanceTimeBy(500); runCurrent()
            assertEquals(0, stops)
            idle = false
            stop.event(ServerEvent(audio = listOf(byteArrayOf(1, 2)), outputText = "Bye!", turnComplete = true))
            advanceTimeBy(500); runCurrent()
            assertEquals(0, stops)
            idle = true
            advanceTimeBy(20); runCurrent()
            assertEquals(1, stops)
            assertTrue(controls.armed)
            assertFalse(controls.conversation)
            assertFalse(controls.shouldRun)
            assertTrue(controls.inputsAvailable(true).inputsAvailable) // Recovery can attach DAT again.
            stop.reset()
        }
    }

    @Test fun fallbackAccumulatesWholeTurnAndNeverActsOnPartialOrReportedSpeech() = runTest {
        var stops = 0
        val stop = VoiceStop(this, { true }, { true }, { stops++ })
        stop.event(ServerEvent(inputText = "Goodbye"))
        advanceTimeBy(5_000)
        assertEquals(0, stops)
        stop.event(ServerEvent(inputText = " to my mom.", turnComplete = true))
        advanceTimeBy(5_000)
        assertEquals(0, stops)
        stop.event(ServerEvent(inputText = "don't stop listening", turnComplete = true))
        stop.event(ServerEvent(inputText = "good"))
        stop.event(ServerEvent(inputText = "bye over", turnComplete = true))
        advanceTimeBy(20); runCurrent()
        assertEquals(1, stops)
        stop.reset()
    }

    @Test fun timeoutIsFourSecondsEvenWithDuplicatesMissingAudioOrStuckPlayback() = runTest {
        for (idle in listOf(true, false)) {
            var stops = 0
            val stop = VoiceStop(this, { true }, { idle }, { stops++ })
            val call = FunctionCall("1", VoiceStop.TOOL, JSONObject())
            stop.tool(call)
            advanceTimeBy(3_000)
            stop.tool(call)
            advanceTimeBy(999); runCurrent()
            assertEquals(0, stops)
            advanceTimeBy(1); runCurrent()
            assertEquals(1, stops)
            stop.reset()
        }
    }

    @Test fun meetingPauseCancellationAndSessionResetPreventExit() = runTest {
        var allowed = false
        var stops = 0
        val stop = VoiceStop(this, { allowed }, { true }, { stops++ })
        val call = FunctionCall("1", VoiceStop.TOOL, JSONObject())
        assertEquals("error", stop.tool(call).response.getString("status"))
        stop.event(ServerEvent(inputText = "goodbye", turnComplete = true))
        advanceTimeBy(4_000)
        assertEquals(0, stops)
        allowed = true
        assertEquals("error", stop.tool(call.copy(args = JSONObject().put("confirm", true))).response.getString("status"))
        stop.tool(call)
        stop.event(ServerEvent(cancelledIds = listOf("1")))
        advanceTimeBy(4_000)
        assertEquals(0, stops)
        stop.tool(call)
        stop.reset()
        advanceTimeBy(4_000)
        assertEquals(0, stops)
        stop.tool(call)
        allowed = false
        advanceTimeBy(4_000)
        assertEquals(0, stops)
        stop.reset()
    }
}
