package com.olevasseur.glassesapp

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineOutboxTest {
    @Test fun stallFallbackBypassesGrammarAndKeepsReceiptAndSavedStateAcrossRestart() = runBlocking {
        val dir = Files.createTempDirectory("stall-outbox").toFile()
        try {
            val id = java.util.UUID.randomUUID().toString()
            val text = "remember this belongs in Inbox, not memory"
            OfflineOutbox(dir).captureStallFallback(id, text)
            val outbox = OfflineOutbox(dir)
            outbox.replay { receipt, call ->
                assertEquals(id, receipt)
                assertEquals("append_to_doc", call.name)
                assertEquals("Inbox", call.args.getString("doc_name"))
                assertEquals("[Gemini didn't answer] $text", call.args.getString("text"))
                throw java.io.IOException("lost response")
            }
            outbox.captureStallFallback(id, text)
            outbox.replay { receipt, _ -> assertEquals(id, receipt); JSONObject().put("status", "ok") }
            OfflineOutbox(dir).captureStallFallback(id, text)
            outbox.replay { _, _ -> fail("Saved fallback must not repeat"); JSONObject() }
            assertEquals(listOf(id), outbox.summary()!!.first)
            assertTrue(outbox.review().contains("saved: [Gemini didn't answer]"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun longFallbackPreservesEveryCharacterWithStablePerAppendReceipts() = runBlocking {
        val dir = Files.createTempDirectory("long-stall-outbox").toFile()
        try {
            val outbox = OfflineOutbox(dir)
            val id = java.util.UUID.randomUUID().toString()
            val text = "x".repeat(100_001)
            outbox.captureStallFallback(id, text)
            outbox.captureStallFallback(id, text)
            val receipts = mutableSetOf<String>()
            var restored = ""
            outbox.replay { receipt, call ->
                assertTrue(receipts.add(receipt))
                val body = call.args.getString("text")
                assertTrue(body.length <= 100_000)
                restored += body.removePrefix("[Gemini didn't answer] ")
                JSONObject().put("status", "ok")
            }
            assertEquals(2, receipts.size)
            assertTrue(id in receipts)
            assertEquals(text, restored)
        } finally { dir.deleteRecursively() }
    }

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
