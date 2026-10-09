package com.olevasseur.glassesapp

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackendClientTest {
    @Test fun allBackendRoutesReportConnectionFailuresWithoutLeakingHostnames() = runBlocking {
        for (failure in listOf(java.net.UnknownHostException("private host"), java.net.ConnectException("private host"),
            java.net.SocketTimeoutException("private host"), javax.net.ssl.SSLHandshakeException("private host"))) {
            val notices = mutableListOf<String>()
            val http = OkHttpClient.Builder().addInterceptor { throw failure }.build()
            try {
                val backend = BackendClient(http, "https://example.test/", "test", notices::add)
                val requests: List<suspend () -> Any> = listOf(
                    { backend.token(false) }, { backend.tool(FunctionCall("1", "append_to_doc", JSONObject())) }, { backend.overview() })
                for (request in requests) {
                    try { request(); fail("Expected connection failure") }
                    catch (e: BackendConnectionException) {
                        assertEquals("Can't reach your Mac", e.message)
                        assertSame(failure, e.cause)
                    }
                }
                assertEquals(List(3) { "Can't reach your Mac" }, notices)
            } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
        }
    }

    @Test fun httpProviderErrorsRemainDistinctFromConnectionFailures() = runBlocking {
        MockWebServer().use { server ->
            val notices = mutableListOf<String>()
            val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test", notices::add)
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"detail":"Gemini temporarily unavailable"}"""))
            try { backend.token(false); fail("Expected HTTP failure") }
            catch (e: BackendHttpException) {
                assertEquals("Gemini is busy, retrying", GeminiRecovery.backendFailure(e).message)
            }
            assertTrue(notices.isEmpty())
        }
    }
    @Test fun deviceApprovalUsesDedicatedAuthenticatedRoutes() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test-secret")
            val id = "a".repeat(32)
            server.enqueue(MockResponse().setBody("""{"status":"needs_confirmation"}"""))
            backend.approvalStatus(id)
            val get = server.takeRequest()
            assertEquals("GET", get.method)
            assertEquals("/approvals/$id", get.path)
            assertEquals("test-secret", get.getHeader("X-App-Secret"))
            for (method in listOf("nod", "button", "screen")) {
                server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
                backend.approveDevice(id, method)
                val request = server.takeRequest()
                assertEquals("/approvals/$id/device", request.path)
                assertEquals("test-secret", request.getHeader("X-App-Secret"))
                assertEquals(method, JSONObject(request.body.readUtf8()).getString("method"))
            }
            server.enqueue(MockResponse().setBody("""{"status":"rejected"}"""))
            backend.denyApproval(id)
            assertEquals("/approvals/$id/deny", server.takeRequest().path)
        }
    }

    @Test fun visualUploadsAndStatusUseAuthenticatedBackendOnly() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test-secret")
            server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
            backend.visualFrame(byteArrayOf(1, 2), "parking", true, "home")
            val upload = server.takeRequest()
            assertEquals("/visual/frames", upload.path)
            assertEquals("test-secret", upload.getHeader("X-App-Secret"))
            val body = JSONObject(upload.body.readUtf8())
            assertEquals("AQI=", body.getString("jpeg"))
            assertEquals("parking", body.getString("label"))
            assertEquals("home", body.getString("location_label"))
            assertTrue(body.getBoolean("pinned"))
            java.time.OffsetDateTime.parse(body.getString("captured_at"))
            for (on in listOf(true, false)) {
                server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
                backend.visualStatus(on)
                val report = server.takeRequest()
                assertEquals("POST", report.method)
                assertEquals("/visual/status", report.path)
                assertEquals("test-secret", report.getHeader("X-App-Secret"))
                assertEquals(on, JSONObject(report.body.readUtf8()).getBoolean("on"))
            }
            server.enqueue(MockResponse().setBody("""{"status":"ok","deleted":1}"""))
            assertEquals(1, backend.deleteVisual(60).getInt("deleted"))
            val deletion = server.takeRequest()
            assertEquals("/visual/delete", deletion.path)
            assertEquals("test-secret", deletion.getHeader("X-App-Secret"))
            assertEquals(60, JSONObject(deletion.body.readUtf8()).getInt("minutes"))
        }
    }
    @Test fun overAndAutoPatienceUseTheTokenContract() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test")
            for ((mode, silence) in listOf(ConversationMode.OVER to 2000, ConversationMode.AUTO to 3250, ConversationMode.AUTO to 8000)) {
                server.enqueue(MockResponse().setBody("""{"token":"test","model":"gemini-3.8-live","expires_at":"2026-10-07T21:00:00Z"}"""))
                assertTrue(backend.token(mode, silence).clientTools.isEmpty())
                val body = JSONObject(server.takeRequest().body.readUtf8())
                assertEquals(mode.wireName, body.getString("mode"))
                if (mode == ConversationMode.AUTO) assertEquals(silence.coerceIn(500, 5000), body.getInt("silence_ms"))
                else assertFalse(body.has("silence_ms"))
            }
        }
    }
    @Test fun tokenAdvertisesClientExecutedTools() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test")
            for (mode in ConversationMode.entries) {
                server.enqueue(MockResponse().setBody("""{"token":"auth_tokens/test","model":"gemini-3.8-live","expires_at":"2026-10-07T21:00:00Z","client_tools":["await_gesture_confirmation","look","set_watch_mode","start_workout","stop_workout","workout_status"]}"""))
                assertEquals(setOf("await_gesture_confirmation", "look", "set_watch_mode", "start_workout", "stop_workout", "workout_status"), backend.token(mode).clientTools)
                assertEquals(mode.wireName, JSONObject(server.takeRequest().body.readUtf8()).getString("mode"))
            }
        }
    }
    @Test fun tokenModesAndToolRelayFollowBackendContract() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test-only-secret")
            val tokenResponse = """{"token":"auth_tokens/example","model":"gemini-3.8-live","expires_at":"2026-10-06T21:00:00Z"}"""
            for (ptt in listOf(false, true)) {
                server.enqueue(MockResponse().setBody(tokenResponse))
                assertEquals("auth_tokens/example", backend.token(ptt).token)
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                assertEquals("/token", request.path)
                assertEquals("test-only-secret", request.getHeader("X-App-Secret"))
                val body = JSONObject(request.body.readUtf8())
                assertEquals(if (ptt) "ptt" else "auto", body.getString("mode"))
                if (!ptt) assertEquals(2000, body.getInt("silence_ms"))
            }
            server.enqueue(MockResponse().setBody("""{"status":"needs_confirmation","candidates":[{"id":"1","title":"Notes"}]}"""))
            val result = backend.tool(FunctionCall("id", "append_to_doc", JSONObject().put("text", "hello")))
            assertEquals("needs_confirmation", result.getString("status"))
            val request = server.takeRequest()
            assertEquals("/tools/append_to_doc", request.path)
            assertEquals("hello", JSONObject(request.body.readUtf8()).getString("text"))
            assertEquals("test-only-secret", request.getHeader("X-App-Secret"))
        }
    }
    @Test fun rejectsToolNamesThatCouldEscapeTheEndpoint() = runBlocking {
        val backend = BackendClient(OkHttpClient(), "http://127.0.0.1:1", "test")
        try {
            backend.tool(FunctionCall("id", "../token", JSONObject()))
            fail("Path traversal accepted")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun backendFailureIsNotInterpretedAsSuccess() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(401).setBody("private diagnostic"))
            val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test")
            try { backend.token(false); fail("HTTP failure accepted") }
            catch (e: java.io.IOException) { assertEquals("Backend HTTP 401", e.message) }
        }
    }
}
