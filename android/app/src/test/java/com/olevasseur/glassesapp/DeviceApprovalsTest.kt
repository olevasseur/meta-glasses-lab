package com.olevasseur.glassesapp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceApprovalsTest {
    private val id = "a".repeat(32)
    private fun pending(tier: Int = 1, key: String = id) = JSONObject()
        .put("status", "needs_confirmation").put("approval_id", key).put("tier", tier)
        .put("summary", "Exact action").put("payload_hash", "b".repeat(64))
        .put("details", JSONObject().put("path", "/exact/repo").put("instruction", "Fix exactly this.\nDo not truncate."))

    private inner class Harness(scope: TestScope, tier: Int = 1, notifications: Boolean = true) {
        val approved = mutableListOf<Pair<String, String>>()
        val denied = mutableListOf<String>()
        val results = mutableListOf<JSONObject>()
        var reply = pending(tier)
        var shown: DeviceApproval? = null
        var motionResets = 0
        val controller = DeviceApprovals(scope, { scope.testScheduler.currentTime }, { reply },
            { key, method -> approved += key to method; JSONObject().put("status", "ok") },
            { key -> denied += key; JSONObject().put("status", "rejected") },
            { shown = it; notifications }, {}, { _, result -> results += result }, { motionResets++ })
    }

    @Test fun tierOneNodIsLocalSingleUseAndDuplicateRepliesDoNotRearm() = runTest {
        val h = Harness(this)
        h.controller.offer(pending())
        assertEquals(1, h.motionResets)
        assertTrue(h.controller.gesture(HeadGesture.NOD))
        h.controller.capture()
        runCurrent()
        assertEquals(listOf(id to "nod"), h.approved)
        assertEquals("ok", h.results.single().getString("status"))
        h.controller.offer(pending())
        assertNull(h.controller.state.value)
        assertFalse(h.controller.capture())
    }

    @Test fun tierTwoNodIsSwallowedAndOnlyButtonOrScreenApproves() = runTest {
        for (method in listOf(ApprovalMethod.BUTTON, ApprovalMethod.SCREEN)) {
            val h = Harness(this, 2)
            h.controller.offer(pending(1)) // Never trust a model-provided tier; fetch server snapshot.
            assertTrue(h.controller.gesture(HeadGesture.NOD))
            runCurrent()
            assertTrue(h.approved.isEmpty())
            assertTrue(h.shown!!.details.contains("/exact/repo"))
            assertTrue(h.shown!!.details.contains("Do not truncate."))
            h.controller.decide(id, method)
            runCurrent()
            assertEquals(listOf(id to method.wire), h.approved)
        }
    }

    @Test fun shakeAndSixtySecondTimeoutDeny() = runTest {
        val shake = Harness(this)
        shake.controller.offer(pending())
        shake.controller.gesture(HeadGesture.SHAKE)
        runCurrent()
        assertEquals(listOf(id), shake.denied)
        assertTrue(shake.approved.isEmpty())
        val timeout = Harness(this)
        timeout.controller.offer(pending())
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(listOf(id), timeout.denied)
        assertNull(timeout.controller.state.value)
        assertTrue(timeout.approved.isEmpty())
    }

    @Test fun deadlineIsCheckedEvenBeforeTimerRuns() = runTest {
        val h = Harness(this)
        h.controller.offer(pending())
        advanceTimeBy(60_000)
        h.controller.decide(id, ApprovalMethod.SCREEN)
        runCurrent()
        assertEquals(listOf(id), h.denied)
        assertTrue(h.approved.isEmpty())
    }

    @Test fun concurrentProposalIsDeniedAndStaleTapCannotApproveCurrentCard() = runTest {
        val h = Harness(this)
        h.controller.offer(pending())
        val other = "c".repeat(32)
        h.controller.offer(pending(key = other))
        assertEquals(listOf(other), h.denied)
        assertEquals("rejected", h.results.single().getString("status"))
        assertEquals(id, h.controller.state.value!!.id)
        h.controller.decide(other, ApprovalMethod.SCREEN)
        runCurrent()
        assertTrue(h.approved.isEmpty())
        h.controller.reject(id)
        runCurrent()
    }

    @Test fun notificationUnavailableOrOversizeRequiresScreenReview() = runTest {
        for (oversize in listOf(false, true)) {
            val h = Harness(this, 2, notifications = oversize)
            if (oversize) h.reply.getJSONObject("details").put("instruction", "x".repeat(5000))
            h.controller.offer(pending())
            h.controller.capture()
            runCurrent()
            assertTrue(h.approved.isEmpty())
            assertFalse(h.controller.state.value!!.hardware)
            h.controller.decide(id, ApprovalMethod.SCREEN)
            runCurrent()
            assertEquals(listOf(id to "screen"), h.approved)
        }
    }

    @Test fun browserOutcomeIsForwardedWithoutSecondExecution() = runTest {
        val h = Harness(this)
        h.controller.offer(pending())
        h.reply = JSONObject().put("status", "queued")
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals("queued", h.results.single().getString("status"))
        assertTrue(h.approved.isEmpty())
        assertNull(h.controller.state.value)
    }

    @Test fun arbitraryTextAndCandidateResponsesCannotArm() = runTest {
        val h = Harness(this)
        for (response in listOf(JSONObject().put("confirm", true).put("approval_id", id),
            JSONObject().put("status", "needs_confirmation").put("candidates", "data"),
            pending(key = "../device"))) h.controller.offer(response)
        assertNull(h.controller.state.value)
        assertFalse(h.controller.gesture(HeadGesture.NOD))
    }

    @Test fun closingDuringSnapshotFetchCannotRearm() = runTest {
        val wait = CompletableDeferred<JSONObject>()
        var displays = 0
        val controller = DeviceApprovals(this, { testScheduler.currentTime }, { wait.await() },
            { _, _ -> fail("Closed approval executed"); JSONObject() }, { JSONObject() },
            { displays++; true }, {}, { _, _ -> })
        val load = launch { controller.offer(pending()) }
        runCurrent()
        controller.close()
        wait.complete(pending())
        load.join()
        assertEquals(0, displays)
        assertNull(controller.state.value)
    }

    @Test fun loadingCardReservesSlotAndCannotBeApproved() = runTest {
        val wait = CompletableDeferred<JSONObject>()
        var calls = 0
        val denied = mutableListOf<String>()
        val controller = DeviceApprovals(this, { testScheduler.currentTime }, { wait.await() },
            { _, _ -> calls++; JSONObject() }, { denied += it; JSONObject() }, { true }, {}, { _, _ -> })
        val load = launch { controller.offer(pending()) }
        runCurrent()
        controller.capture()
        controller.offer(pending(key = "c".repeat(32)))
        assertEquals(0, calls)
        assertEquals(listOf("c".repeat(32)), denied)
        wait.complete(pending())
        load.join()
        controller.reject(id)
        runCurrent()
    }
}
