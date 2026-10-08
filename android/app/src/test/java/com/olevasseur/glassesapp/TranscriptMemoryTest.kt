package com.olevasseur.glassesapp

import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TranscriptMemoryTest {
    private fun ok() = MockResponse().setBody("""{"status":"ok"}""")

    @Test fun batchesAndClosesOnlyAfterAllChunksAreAcknowledged() = runBlocking {
        MockWebServer().use { server ->
            val directory = Files.createTempDirectory("transcripts-test").toFile()
            try {
                val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test")
                val errors = mutableListOf<String>()
                val memory = TranscriptMemory(backend, directory, errors::add)
                val id = memory.begin()
                repeat(51) { memory.append(if (it % 2 == 0) "user" else "model", "chunk $it") }
                memory.stop()
                repeat(3) { server.enqueue(ok()) }
                memory.flush()
                val first = server.takeRequest()
                assertEquals("/transcripts", first.path)
                assertEquals("test", first.getHeader("X-App-Secret"))
                val body = JSONObject(first.body.readUtf8())
                assertEquals(id, body.getString("session_id"))
                assertEquals(50, body.getJSONArray("turns").length())
                assertEquals("user", body.getJSONArray("turns").getJSONObject(0).getString("speaker"))
                java.time.Instant.parse(body.getJSONArray("turns").getJSONObject(0).getString("timestamp"))
                val second = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("turns")
                assertEquals(1, second.length())
                assertEquals(50, second.getJSONObject(0).getInt("seq"))
                assertEquals("/sessions/$id/end", server.takeRequest().path)
                assertTrue(directory.listFiles()!!.isEmpty())
                assertTrue(errors.isEmpty())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun failedUploadSurvivesRelaunchAndKeepsIdsForSafeReplay() = runBlocking {
        MockWebServer().use { server ->
            val directory = Files.createTempDirectory("transcripts-test").toFile()
            try {
                val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test")
                val errors = mutableListOf<String>()
                val memory = TranscriptMemory(backend, directory, errors::add)
                val id = memory.begin()
                memory.append("user", "I like kayaking.")
                server.enqueue(MockResponse().setResponseCode(502))
                memory.flush()
                val failed = JSONObject(server.takeRequest().body.readUtf8())
                assertEquals(1, errors.size)
                assertEquals(1, server.requestCount) // Never end before successful transcript delivery.
                val relaunched = TranscriptMemory(backend, directory, errors::add)
                server.enqueue(ok()); server.enqueue(ok())
                relaunched.flush()
                assertEquals(failed.toString(), JSONObject(server.takeRequest().body.readUtf8()).toString())
                assertEquals("/sessions/$id/end", server.takeRequest().path)
                assertTrue(directory.listFiles()!!.isEmpty())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun failedSummaryRetriesWithoutUploadingAcknowledgedChunksAgain() = runBlocking {
        MockWebServer().use { server ->
            val directory = Files.createTempDirectory("transcripts-test").toFile()
            try {
                val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test")
                val memory = TranscriptMemory(backend, directory) { }
                val id = memory.begin()
                memory.append("model", "Hello."); memory.stop()
                server.enqueue(ok()); server.enqueue(MockResponse().setResponseCode(502))
                memory.flush()
                assertEquals("/transcripts", server.takeRequest().path)
                assertEquals("/sessions/$id/end", server.takeRequest().path)
                server.enqueue(ok())
                TranscriptMemory(backend, directory) { }.flush()
                assertEquals("/sessions/$id/end", server.takeRequest().path)
                assertTrue(directory.listFiles()!!.isEmpty())
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun newSessionHasNewIdAndSequenceAndActiveSessionIsNotEnded() = runBlocking {
        MockWebServer().use { server ->
            val directory = Files.createTempDirectory("transcripts-test").toFile()
            try {
                val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test")
                val memory = TranscriptMemory(backend, directory) { }
                val first = memory.begin()
                memory.append("user", "First")
                server.enqueue(ok()); memory.flush()
                assertEquals("/transcripts", server.takeRequest().path)
                assertEquals(1, server.requestCount)
                memory.append("user", "Second")
                memory.stop()
                val second = memory.begin()
                assertNotEquals(first, second)
                memory.append("user", "New")
                repeat(3) { server.enqueue(ok()) }
                memory.flush()
                assertEquals(1, JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("turns").getJSONObject(0).getInt("seq"))
                assertEquals("/sessions/$first/end", server.takeRequest().path)
                assertEquals(0, JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("turns").getJSONObject(0).getInt("seq"))
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun chunksArrivingDuringUploadAreNotDiscarded() = runBlocking {
        MockWebServer().use { server ->
            val directory = Files.createTempDirectory("transcripts-test").toFile()
            try {
                val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test")
                val memory = TranscriptMemory(backend, directory) { }
                memory.begin(); memory.append("user", "First")
                server.enqueue(ok().setBodyDelay(200, java.util.concurrent.TimeUnit.MILLISECONDS))
                server.enqueue(ok())
                val upload = async { memory.flush() }
                withContext(Dispatchers.IO) { server.takeRequest() }
                memory.append("user", "Second")
                upload.await()
                val second = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("turns")
                assertEquals(1, second.length())
                assertEquals("Second", second.getJSONObject(0).getString("text"))
                assertEquals(1, second.getJSONObject(0).getInt("seq"))
            } finally { directory.deleteRecursively() }
        }
    }

    @Test fun memoryToolsUseExistingGenericRelayAndProtocol() = runBlocking {
        MockWebServer().use { server ->
            val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test")
            for (name in listOf("remember", "recall", "forget")) {
                server.enqueue(MockResponse().setBody("""{"status":"needs_confirmation","confirmation_token":"test-only"}"""))
                val event = LiveProtocol.parse("""{"toolCall":{"functionCalls":[{"id":"1","name":"$name","args":{"query":"kayaking"}}]}}""")
                val call = event.calls.single()
                val response = backend.tool(call)
                assertEquals("/tools/$name", server.takeRequest().path)
                val wire = JSONObject(LiveProtocol.toolResponse(call, response)).getJSONObject("toolResponse")
                    .getJSONArray("functionResponses").getJSONObject(0)
                assertEquals(name, wire.getString("name"))
                assertEquals("test-only", wire.getJSONObject("response").getString("confirmation_token"))
            }
        }
    }
}
