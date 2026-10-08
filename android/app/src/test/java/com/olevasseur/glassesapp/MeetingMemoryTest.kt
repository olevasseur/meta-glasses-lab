package com.olevasseur.glassesapp

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MeetingMemoryTest {
    private fun reply(body: String = """{"status":"ok"}""") = MockResponse().setBody(body)

    @Test fun meetingRegistrationUploadAndPollingSurviveRelaunch() = runBlocking {
        MockWebServer().use { server ->
            val dir = Files.createTempDirectory("meeting-test").toFile()
            try {
                val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test")
                val errors = mutableListOf<String>()
                val first = TranscriptMemory(backend, dir, errors::add)
                val id = first.begin(JSONObject().put("title", "Pilot").put("doc_name", "Work"))
                first.append("user", "Alex owns budget."); first.stop()
                server.enqueue(reply()); server.enqueue(reply()); server.enqueue(reply("""{"status":"processing"}"""))
                first.flush()
                assertEquals("/meetings/start", server.takeRequest().path)
                assertEquals("/transcripts", server.takeRequest().path)
                assertEquals("/meetings/$id/end", server.takeRequest().path)
                assertEquals(1, dir.listFiles()!!.size)
                val ready = mutableListOf<JSONObject>()
                val recovered = TranscriptMemory(backend, dir, errors::add).apply { onMeetingReady = ready::add }
                server.enqueue(reply("""{"status":"ok","minutes":{"recap":["Pilot Friday.","Alex owns budget."]},"doc_url":"https://docs.google.com/document/d/test/edit"}"""))
                recovered.flush()
                assertEquals("/meetings/$id/end", server.takeRequest().path)
                assertEquals(1, ready.size)
                assertTrue(dir.listFiles()!!.isEmpty())
                assertTrue(errors.isEmpty())
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun pendingApprovalKeepsOutboxUntilExportFinishes() = runBlocking {
        MockWebServer().use { server ->
            val dir = Files.createTempDirectory("approval-meeting-test").toFile()
            try {
                val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test")
                val pending = mutableListOf<JSONObject>()
                val ready = mutableListOf<JSONObject>()
                val memory = TranscriptMemory(backend, dir) { fail(it) }.apply {
                    onApproval = { pending += it }; onMeetingReady = ready::add
                }
                memory.begin(JSONObject().put("title", "Pilot")); memory.stop()
                server.enqueue(reply())
                server.enqueue(reply("""{"status":"needs_confirmation","approval_id":"test"}"""))
                memory.flush()
                assertEquals(1, pending.size)
                assertTrue(ready.isEmpty())
                assertEquals(1, dir.listFiles()!!.size)
                server.enqueue(reply("""{"status":"ok","minutes":{"recap":["Saved."]}}"""))
                memory.flush()
                assertEquals(1, ready.size)
                assertTrue(dir.listFiles()!!.isEmpty())
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun failedRegistrationKeepsTranscriptAndRetriesSameId() = runBlocking {
        MockWebServer().use { server ->
            val dir = Files.createTempDirectory("meeting-test").toFile()
            try {
                val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test")
                val memory = TranscriptMemory(backend, dir) { }
                val id = memory.begin(JSONObject().put("title", "Lecture"))
                memory.append("user", "First sentence.")
                server.enqueue(MockResponse().setResponseCode(502)); memory.flush()
                assertEquals(id, JSONObject(server.takeRequest().body.readUtf8()).getString("meeting_id"))
                server.enqueue(reply()); server.enqueue(reply()); memory.flush()
                assertEquals(id, JSONObject(server.takeRequest().body.readUtf8()).getString("meeting_id"))
                assertEquals("First sentence.", JSONObject(server.takeRequest().body.readUtf8())
                    .getJSONArray("turns").getJSONObject(0).getString("text"))
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun scribeTokenIsDistinctFromPtt() = runBlocking {
        MockWebServer().use { server ->
            val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test")
            server.enqueue(reply("""{"token":"test","model":"gemini-3.8-live","expires_at":"2026-10-07T12:00:00Z"}"""))
            backend.token(ptt = true, meeting = true)
            assertEquals("meeting", JSONObject(server.takeRequest().body.readUtf8()).getString("mode"))
        }
    }

    @Test fun meetingTokenOverridesAllTurnModesAndTranslatorWithoutLosingClientTools() = runBlocking {
        MockWebServer().use { server ->
            val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test")
            for (mode in ConversationMode.entries) {
                server.enqueue(reply("""{"token":"test","model":"gemini-3.8-live","expires_at":"2026-10-07T12:00:00Z","client_tools":["look","start_workout","read_text"]}"""))
                val token = backend.token(mode, 3250, translationLanguage = "es", meeting = true)
                val request = server.takeRequest()
                val body = JSONObject(request.body.readUtf8())
                assertEquals(setOf("mode", "silence_ms"), body.keys().asSequence().toSet())
                assertEquals("meeting", body.getString("mode"))
                assertEquals(3250, body.getInt("silence_ms"))
                assertEquals("test", request.getHeader("X-App-Secret"))
                assertEquals(setOf("look", "start_workout", "read_text"), token.clientTools)
            }
        }
    }

    @Test fun pendingMinutesDoNotBlockTheNextConversationsTranscript() = runBlocking {
        MockWebServer().use { server ->
            val dir = Files.createTempDirectory("meeting-mixed-test").toFile()
            try {
                val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test")
                val errors = mutableListOf<String>()
                val memory = TranscriptMemory(backend, dir, errors::add)
                val meeting = memory.begin(JSONObject().put("title", "Group"))
                memory.append("user", "Maya owns the budget.")
                memory.stop()
                val conversation = memory.begin()
                memory.append("user", "Remember my dog Maple.")
                server.enqueue(reply()); server.enqueue(reply())
                server.enqueue(reply("""{"status":"processing"}""")); server.enqueue(reply())
                memory.flush()
                assertEquals("/meetings/start", server.takeRequest().path)
                assertEquals(meeting, JSONObject(server.takeRequest().body.readUtf8()).getString("session_id"))
                assertEquals("/meetings/$meeting/end", server.takeRequest().path)
                val ordinary = JSONObject(server.takeRequest().body.readUtf8())
                assertEquals(conversation, ordinary.getString("session_id"))
                assertEquals("Remember my dog Maple.", ordinary.getJSONArray("turns").getJSONObject(0).getString("text"))
                assertEquals(2, dir.listFiles()!!.size)
                assertTrue(errors.isEmpty())
            } finally { dir.deleteRecursively() }
        }
    }
}
