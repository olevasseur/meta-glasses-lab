package com.olevasseur.glassesapp

import com.olevasseur.glassesapp.ui.Overview
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppOverviewTest {
    @Test fun emptyAndCompletedProjectsHaveNoActiveBanner() {
        assertNull(Overview.parse(JSONObject("""{"project":null}""")).project)
        assertTrue(Overview.parse(JSONObject()).facts.isEmpty())
    }

    @Test fun mapsRecentRecordsAndCurrentStepWithoutGuessingActivity() {
        val data = Overview.parse(JSONObject("""{
          "facts":[{"text":"My dog is Maple","updated_at":"2026-10-08"}],
          "notes":[{"title":"Inbox","text":"Buy pasta"}],
          "jobs":[{"kind":"research","status":"running","summary":"Recipe research"}],
          "meetings":[{"title":"Design","phase":"complete"}],
          "project":{"title":"Pasta","step_number":2,"step_count":3,"instruction":"Drain it"}
        }"""))
        assertEquals("My dog is Maple", data.facts.single().title)
        assertEquals("Buy pasta", data.notes.single().detail)
        assertEquals("research · running", data.jobs.single().title)
        assertEquals("complete", data.meetings.single().detail)
        assertEquals("Pasta · step 2/3\nDrain it", data.project)
    }

    @Test fun overviewUsesAuthenticatedReadAndPreservesBasePath() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"facts":[]}"""))
            val backend = BackendClient(OkHttpClient(), server.url("/personal").toString(), "ui-test-secret")
            assertTrue(Overview.parse(backend.overview()).facts.isEmpty())
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/personal/ui/overview", request.path)
            assertEquals("ui-test-secret", request.getHeader("X-App-Secret"))
            assertEquals(0L, request.bodySize)
        }
    }
}
