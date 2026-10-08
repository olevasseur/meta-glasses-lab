package com.olevasseur.glassesapp

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineOutboxTest {
    @Test fun grammarAllowsOnlyExactLocalPrefixesAndKeepsContentsAsData() {
        assertEquals("append_to_doc", OfflineGrammar.parse("Add a note buy eggs")!!.name)
        assertEquals("Inbox", OfflineGrammar.parse("add a note buy eggs")!!.args.getString("doc_name"))
        assertEquals("home city is Toronto", OfflineGrammar.parse("remember home city is Toronto")!!.args.getString("fact"))
        for (text in listOf("forget my name", "please add a note x", "remember", "delete notes", "run codex", "add a note"))
            assertNull(text, OfflineGrammar.parse(text))
    }
    @Test fun queueSurvivesRestartRetriesSameReceiptAndAcknowledgesOnlySummarizedItems() = runBlocking {
        val dir = Files.createTempDirectory("offline-test").toFile()
        try {
            val first = OfflineOutbox(dir)
            val id = first.capture("add a note buy eggs")
            first.replay { _, _ -> throw java.io.IOException() }
            assertNull(first.summary())
            val restarted = OfflineOutbox(dir)
            var calls = 0
            restarted.replay { receipt, call ->
                assertEquals(id, receipt); assertEquals("append_to_doc", call.name); calls++
                JSONObject().put("status", "ok")
            }
            restarted.replay { _, _ -> calls++; JSONObject() }
            assertEquals(1, calls)
            val question = restarted.capture("what is the weather?")
            restarted.replay { _, _ -> fail("Question must not execute tools"); JSONObject() }
            val summary = restarted.summary()!!
            assertEquals(setOf(id, question), summary.first.toSet())
            assertTrue(summary.second.contains("ask Gemini later"))
            assertTrue(summary.second.contains("saved"))
            restarted.acknowledge(listOf(id))
            assertEquals(listOf(question), restarted.summary()!!.first)
        } finally { dir.deleteRecursively() }
    }
    @Test fun uncertainWriteIsRetainedForReviewAndNeverAutomaticallyReplayed() = runBlocking {
        val dir = Files.createTempDirectory("offline-unknown").toFile()
        try {
            val outbox = OfflineOutbox(dir)
            outbox.capture("remember my name is Jo")
            outbox.replay { _, _ -> JSONObject().put("status", "unknown") }
            outbox.replay { _, _ -> fail("Uncertain write must not repeat"); JSONObject() }
            val summary = outbox.summary()!!
            assertTrue(summary.second.contains("needs review: unknown"))
            outbox.acknowledge(summary.first)
            assertNull(outbox.summary())
            assertTrue(outbox.review().contains("remember my name is Jo"))
        } finally { dir.deleteRecursively() }
    }
}
