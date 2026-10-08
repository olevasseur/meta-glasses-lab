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
}
