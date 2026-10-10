package com.olevasseur.glassesapp

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GestureConfirmationTest {
    private fun request(id: String = "confirm") = FunctionCall(id, GestureConfirmation.TOOL, JSONObject().put("question", "Use Work Log?"))

    @Test fun unclaimedNodGivesFeedbackWithoutInventingApprovalOrBlockingNavigation() {
        val gate = GestureConfirmation()
        var feedback = 0
        for (gesture in HeadGesture.entries) gate.route(gesture, 0, { fail("No question"); true }, { false }, { feedback++ })
        assertEquals(1, feedback)
        assertTrue(gate.route(HeadGesture.NOD, 0, { false }, { true }, { fail("Navigation owns nod") }))
        gate.request(request(), true, 0)
        gate.onEvent(ServerEvent(turnComplete = true))
        assertTrue(gate.route(HeadGesture.NOD, 1, { true }, { fail("Question owns nod"); false }, { fail("Question pending") }))
    }

    @Test fun confirmationAlwaysPrecedesCoachEvenBeforeReadyOnExpiryOrFailedSend() {
        for (gesture in HeadGesture.entries) {
            val gate = GestureConfirmation()
            gate.request(request(), true, 0)
            val neverNavigate: (HeadGesture) -> Boolean = { error("Confirmation must own the gesture") }
            assertFalse(gate.route(gesture, 100, { error("Not ready") }, neverNavigate))
            gate.onEvent(ServerEvent(turnComplete = true))
            assertFalse(gate.route(gesture, 200, { false }, neverNavigate))
            gate.request(request(), true, 0)
            gate.onEvent(ServerEvent(turnComplete = true))
            assertFalse(gate.route(gesture, 30_000, { error("Expired") }, neverNavigate))
            gate.request(request(), true, 31_000)
            gate.onEvent(ServerEvent(turnComplete = true))
            assertTrue(gate.route(gesture, 31_100, { it == gesture.text }, neverNavigate))
            assertTrue(gate.route(gesture, 31_200, { error("No pending question") }, { it == gesture }))
        }
    }

    @Test fun oneExplicitQuestionOneAnswerAfterModelTurn() {
        val gate = GestureConfirmation()
        val sent = mutableListOf<String>()
        assertEquals("ready", gate.request(request(), true, 100)!!.getString("status"))
        assertFalse(gate.answer(HeadGesture.NOD, 200) { sent.add(it) })
        gate.onEvent(ServerEvent(turnComplete = true))
        assertTrue(gate.answer(HeadGesture.NOD, 500) { sent.add(it) })
        assertFalse(gate.answer(HeadGesture.SHAKE, 600) { sent.add(it) })
        assertEquals(listOf("[gesture: nod = yes]"), sent)
    }

    @Test fun disabledUnavailableExpiredAndFailedSendNeverReplay() {
        val gate = GestureConfirmation()
        assertEquals("unavailable", gate.request(request(), false, 0)!!.getString("status"))
        gate.onEvent(ServerEvent(turnComplete = true))
        assertFalse(gate.answer(HeadGesture.NOD, 1) { fail("unavailable"); true })
        gate.request(request(), true, 100)
        gate.onEvent(ServerEvent(turnComplete = true))
        assertFalse(gate.answer(HeadGesture.NOD, 30_100) { fail("expired"); true })
        gate.request(request(), true, 31_000)
        gate.onEvent(ServerEvent(turnComplete = true))
        assertFalse(gate.answer(HeadGesture.SHAKE, 31_100) { false })
        assertFalse(gate.answer(HeadGesture.SHAKE, 31_200) { fail("replayed"); true })
    }

    @Test fun speechInterruptionCancellationNewToolAndDisconnectClearQuestion() {
        val events = listOf(ServerEvent(inputText = "actually"), ServerEvent(interrupted = true),
            ServerEvent(cancelledIds = listOf("confirm")), ServerEvent(outputText = "Another topic"),
            ServerEvent(calls = listOf(FunctionCall("write", "append_to_doc", JSONObject()))))
        for (event in events) {
            val gate = GestureConfirmation()
            gate.request(request(), true, 0); gate.onEvent(ServerEvent(turnComplete = true)); gate.onEvent(event)
            assertFalse(gate.answer(HeadGesture.NOD, 100) { fail("stale question"); true })
        }
        val gate = GestureConfirmation()
        gate.request(request(), true, 0); gate.onEvent(ServerEvent(turnComplete = true)); gate.clear()
        assertNull(gate.question)
    }

    @Test fun unrelatedCancellationDoesNotDiscardPendingQuestion() {
        val gate = GestureConfirmation()
        gate.request(request(), true, 0)
        gate.onEvent(ServerEvent(cancelledIds = listOf("unrelated"), turnComplete = true))
        assertTrue(gate.answer(HeadGesture.SHAKE, 100) { it == "[gesture: shake = no]" })
    }

    @Test fun invalidQuestionsFailClosedAndBackendCallsPassThrough() {
        val gate = GestureConfirmation()
        assertNull(gate.request(FunctionCall("1", "append_to_doc", JSONObject()), true, 0))
        for (question in listOf("", " ", "x".repeat(501), 123)) {
            assertEquals("error", gate.request(request().copy(args = JSONObject().put("question", question)), true, 0)!!.getString("status"))
            assertNull(gate.question)
        }
    }

    @Test fun completeTextTurnHasTheVerifiedClientContentShape() {
        val json = JSONObject(gestureTextTurn(HeadGesture.NOD.text))
        assertEquals(setOf("clientContent"), json.keys().asSequence().toSet())
        val content = json.getJSONObject("clientContent")
        assertTrue(content.getBoolean("turnComplete"))
        val turn = content.getJSONArray("turns").getJSONObject(0)
        assertEquals("user", turn.getString("role"))
        assertEquals(HeadGesture.NOD.text, turn.getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test fun overGestureClosesActivityBeforeSendingTheConfirmedAnswer() {
        val gate = GestureConfirmation()
        gate.request(request(), true, 0)
        gate.onEvent(ServerEvent(turnComplete = true))
        val sent = mutableListOf<String>()
        var talking = true
        assertTrue(gate.answer(HeadGesture.NOD, 100) { text ->
            sendGestureTurn(text, ConversationMode.OVER, talking, { message ->
                if (sent.isNotEmpty()) assertFalse(talking)
                sent.add(message)
            }) { talking = false }
        })
        assertFalse(talking)
        assertEquals(2, sent.size)
        assertTrue(JSONObject(sent[0]).getJSONObject("realtimeInput").has("activityEnd"))
        assertEquals(gestureTextTurn(HeadGesture.NOD.text), sent[1])
        assertFalse(gate.answer(HeadGesture.NOD, 200) { fail("Duplicate answer"); true })
    }

    @Test fun gesturesPreservePttOwnershipAndSendDirectlyWhenNoActivityIsOpen() {
        assertFalse(sendGestureTurn(HeadGesture.NOD.text, ConversationMode.PTT, true,
            { fail("Held PTT cannot send a gesture"); true }, { fail("Held PTT cannot end") }))
        for (mode in ConversationMode.entries) {
            val sent = mutableListOf<String>()
            assertTrue(sendGestureTurn(HeadGesture.SHAKE.text, mode, false, { sent.add(it) },
                { fail("No activity to close") }))
            assertEquals(listOf(gestureTextTurn(HeadGesture.SHAKE.text)), sent)
        }
    }

    @Test fun failedActivityEndDoesNotSendGestureAndFailedTextDoesNotReopenActivity() {
        var talking = true
        val sent = mutableListOf<String>()
        assertFalse(sendGestureTurn(HeadGesture.NOD.text, ConversationMode.OVER, talking,
            { sent.add(it); false }, { talking = false }))
        assertTrue(talking)
        assertEquals(listOf(LiveProtocol.activity(false)), sent)
        sent.clear()
        assertFalse(sendGestureTurn(HeadGesture.NOD.text, ConversationMode.OVER, talking,
            { sent.add(it); sent.size == 1 }, { talking = false }))
        assertFalse(talking)
        assertEquals(listOf(LiveProtocol.activity(false), gestureTextTurn(HeadGesture.NOD.text)), sent)
    }
}
