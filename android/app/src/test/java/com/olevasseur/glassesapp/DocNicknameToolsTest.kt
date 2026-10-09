package com.olevasseur.glassesapp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DocNicknameToolsTest {
    @Test fun backendNicknameToolsReuseTierOneApprovalForEveryMethod() = runTest {
        for (name in listOf("create_doc", "set_doc_alias", "remove_doc_alias")) {
            for (method in ApprovalMethod.entries) {
                val id = "a".repeat(32)
                val summary = "Nickname action: $name"
                val pending = JSONObject().put("status", "needs_confirmation")
                    .put("approval_id", id).put("tier", 1).put("summary", summary)
                    .put("payload_hash", "b".repeat(64)).put("details", JSONObject().put("alias", "travel"))
                var forwarded = ""
                val result = routeTool(FunctionCall("call", name, JSONObject().put("alias", "travel")), emptySet(),
                    { error("Nickname tools must run on backend") }, { forwarded = it.name; pending })
                assertEquals(name, forwarded)
                val approved = mutableListOf<String>()
                val completed = mutableListOf<JSONObject>()
                val approvals = DeviceApprovals(this, { testScheduler.currentTime }, { pending },
                    { key, wire -> assertEquals(id, key); approved += wire; JSONObject().put("status", "ok") },
                    { error("Unexpected denial") }, { assertEquals(summary, it.summary); true }, {},
                    { _, response -> completed += response })
                approvals.offer(result.response)
                assertTrue(approved.isEmpty())
                assertEquals(1, approvals.state.value!!.tier)
                when (method) {
                    ApprovalMethod.NOD -> approvals.gesture(HeadGesture.NOD)
                    ApprovalMethod.BUTTON -> approvals.capture()
                    ApprovalMethod.SCREEN -> approvals.decide(id, method)
                }
                runCurrent()
                assertEquals(listOf(method.wire), approved)
                assertEquals("ok", completed.single().getString("status"))
                assertNull(approvals.state.value)
                approvals.close()
            }
        }
    }
}
