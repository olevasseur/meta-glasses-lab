package com.olevasseur.glassesapp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoachNavigationTest {
    private fun project(kind: String = "cooking", status: String = "ok") =
        JSONObject().put("status", status).put("kind", kind).put("step_number", 2)

    @Test fun nodAdvancesAndShakeRepeatsPersistedCookingAndDiyProjects() = runTest {
        for (kind in listOf("cooking", "diy")) {
            val calls = mutableListOf<String>()
            val announcements = mutableListOf<String>()
            val results = mutableListOf<String>()
            val navigation = CoachNavigation(this, { true }, {
                assertEquals(0, it.args.length())
                calls += it.name
                project(kind)
            }, { announcements.add(it) }, { results.add(it) })
            for ((gesture, tool) in listOf(HeadGesture.NOD to "next_step", HeadGesture.SHAKE to "repeat_step")) {
                calls.clear()
                assertTrue(navigation.gesture(gesture))
                runCurrent()
                assertEquals(listOf("current_step", tool), calls)
                assertTrue(announcements.last().contains("$tool already executed; do not call it again"))
                assertTrue(announcements.last().contains("\"step_number\":2"))
                assertTrue(results.last().startsWith("$tool:"))
            }
        }
    }

    @Test fun disabledOrAbsentOrCompletedProjectCannotNavigate() = runTest {
        val disabled = CoachNavigation(this, { false }, { error("Disabled") }, { error("Disabled") }, { error("Disabled") })
        assertFalse(disabled.gesture(HeadGesture.NOD))
        for ((kind, status) in listOf("cooking" to "no_project", "diy" to "complete", "other" to "ok")) {
            val calls = mutableListOf<String>()
            val navigation = CoachNavigation(this, { true }, { calls += it.name; project(kind, status) },
                { error("No active project") }, { error("No navigation result") })
            assertTrue(navigation.gesture(HeadGesture.SHAKE))
            runCurrent()
            assertEquals(listOf("current_step"), calls)
        }
    }

    @Test fun newConfirmationOrPauseDuringLookupPreventsMutation() = runTest {
        var allowed = true
        val lookup = CompletableDeferred<JSONObject>()
        val calls = mutableListOf<String>()
        val navigation = CoachNavigation(this, { allowed }, { calls += it.name; lookup.await() },
            { error("Blocked") }, { error("Blocked") })
        assertTrue(navigation.gesture(HeadGesture.NOD))
        runCurrent()
        assertFalse(navigation.gesture(HeadGesture.NOD)) // No queued/double advancement.
        allowed = false
        lookup.complete(project())
        runCurrent()
        assertEquals(listOf("current_step"), calls)
    }

    @Test fun disconnectCancelsAndNeverReplaysOldMovement() = runTest {
        val lookup = CompletableDeferred<JSONObject>()
        val calls = mutableListOf<String>()
        val navigation = CoachNavigation(this, { true }, { calls += it.name; lookup.await() },
            { error("Cancelled") }, { error("Cancelled") })
        navigation.gesture(HeadGesture.NOD)
        runCurrent()
        navigation.cancel()
        lookup.complete(project())
        runCurrent()
        assertEquals(listOf("current_step"), calls)
    }

    @Test fun uncertainBackendFailureIsReportedWithoutRetryOrSuccessAnnouncement() = runTest {
        val calls = mutableListOf<String>()
        val results = mutableListOf<String>()
        val navigation = CoachNavigation(this, { true }, {
            calls += it.name
            if (it.name == "next_step") error("private backend detail")
            project()
        }, { error("Failed action") }, { results.add(it) })
        navigation.gesture(HeadGesture.NOD)
        runCurrent()
        assertEquals(listOf("current_step", "next_step"), calls)
        assertEquals(1, results.size)
        assertTrue(results.single().contains("outcome may be unknown"))
        assertFalse(results.single().contains("private backend detail"))
    }

    @Test fun failedAnnouncementNeverRepeatsBackendAction() = runTest {
        val calls = mutableListOf<String>()
        val results = mutableListOf<String>()
        val navigation = CoachNavigation(this, { true }, { calls += it.name; project() }, { false }, { results.add(it) })
        navigation.gesture(HeadGesture.NOD)
        runCurrent()
        assertEquals(listOf("current_step", "next_step"), calls)
        assertEquals(1, results.size) // Result remains visible even if Live disconnects.
    }

    @Test fun coachResultsRespectAllTurnModesIncludingOverActivityEnd() {
        for (mode in ConversationMode.entries) {
            val messages = mutableListOf<String>()
            var talking = mode == ConversationMode.OVER
            assertTrue(sendGestureTurn("[coach gesture result: repeat_step already executed]", mode, talking,
                { messages.add(it) }, { talking = false }))
            assertFalse(talking)
            if (mode == ConversationMode.OVER) assertEquals(LiveProtocol.activity(false), messages.first())
            assertEquals(if (mode == ConversationMode.OVER) 2 else 1, messages.size)
            assertEquals(gestureTextTurn("[coach gesture result: repeat_step already executed]"), messages.last())
        }
    }
}
