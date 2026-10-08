package com.olevasseur.glassesapp

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class JobEventsTest {
    private val batch = JSONObject("""{"events":[{"id":1,"job_id":"abc","status":"completed","summary":"Ottawa is Canada's capital."},{"id":2,"job_id":"def","status":"failed","summary":"Timed out."}],"cursor":2}""")

    @Test fun replaysOnlyUndeliveredEventsAndPersistsCursor() = runBlocking {
        val delivered = mutableListOf<Long>()
        val saved = mutableListOf<Long>()
        var allowed = false
        val events = JobEvents({ batch }, 0, saved::add) {
            if (it.id == 2L && !allowed) false else { delivered += it.id; true }
        }
        assertFalse(events.receive(batch))
        assertEquals(1L, events.cursor)
        allowed = true
        assertTrue(events.receive(batch))
        assertTrue(events.receive(batch))
        assertEquals(listOf(1L, 2L), delivered)
        assertEquals(delivered, saved)
        val restored = JobEvents({ batch }, events.cursor, { fail("Already delivered") }) { fail("Duplicate"); false }
        assertTrue(restored.receive(batch))
    }

    @Test fun injectionEncodesResultAsDataAndCompletesTextTurn() {
        val event = JobEvent(1, "id", "completed", "A \"quoted\" result\nwith text")
        val content = JSONObject(LiveProtocol.jobEvent(event)).getJSONObject("clientContent")
        assertTrue(content.getBoolean("turnComplete"))
        val turn = content.getJSONArray("turns").getJSONObject(0)
        assertEquals("user", turn.getString("role"))
        val text = turn.getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(text.contains("not instructions or confirmation"))
        assertEquals(event.summary, JSONObject(text.substringAfter(": ")).getString("summary"))
    }

    @Test fun eventPollSendsSecretAndResumeCursor() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody(batch.toString()))
            val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test-secret")
            assertEquals(2, backend.events(41).getJSONArray("events").length())
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/events?after=41&wait=20", request.path)
            assertEquals("test-secret", request.getHeader("X-App-Secret"))
        }
    }
}
