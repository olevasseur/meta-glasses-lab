package com.olevasseur.glassesapp

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WorkoutLogStoreTest {
    @Test fun partialSnapshotSurvivesRelaunchAndOnlyFinalLogsUpload() = runBlocking {
        val directory = Files.createTempDirectory("workout-test").toFile()
        try {
            val sent = mutableListOf<JSONObject>()
            val store = WorkoutLogStore(directory, { sent.add(it) }, { fail(it) })
            store.save(JSONObject().put("workout_id", "id1").put("status", "interrupted").put("reps", 7), false)
            store.flush()
            assertTrue(sent.isEmpty())
            val recovered = WorkoutLogStore(directory, { sent.add(it) }, { fail(it) })
            recovered.flush()
            assertEquals("interrupted", sent.single().getString("status"))
            assertEquals(7, sent.single().getInt("reps"))
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedHttpUploadRetriesExactPayloadAndUsesAuthenticatedWorkoutRoute() = runBlocking {
        val directory = Files.createTempDirectory("workout-test").toFile()
        try {
            MockWebServer().use { server ->
                val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test-secret")
                val errors = mutableListOf<String>()
                val store = WorkoutLogStore(directory, { backend.workout(it) }, { errors.add(it) })
                store.save(JSONObject().put("workout_id", "id2").put("status", "completed"), true)
                server.enqueue(MockResponse().setResponseCode(503))
                store.flush()
                val first = server.takeRequest()
                assertEquals("/workouts", first.path)
                assertEquals("test-secret", first.getHeader("X-App-Secret"))
                assertEquals(1, errors.size)
                server.enqueue(MockResponse().setBody("""{"status":"ok","stored":false}"""))
                WorkoutLogStore(directory, { backend.workout(it) }, { fail(it) }).flush()
                assertEquals(first.body.readUtf8(), server.takeRequest().body.readUtf8())
                assertTrue(directory.listFiles()!!.isEmpty())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun corruptLogIsKeptAndReported() = runBlocking {
        val directory = Files.createTempDirectory("workout-test").toFile()
        try {
            val file = java.io.File(directory, "id.json").apply { writeText("bad json") }
            val errors = mutableListOf<String>()
            WorkoutLogStore(directory, { fail("No upload expected") }, { errors.add(it) }).flush()
            assertEquals(1, errors.size)
            assertTrue(file.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test fun localToolArgumentsAreStrictAndMatchTimerDefaults() {
        assertEquals(30, workoutConfig(JSONObject("""{"exercise":"plank"}""")).effectiveWorkSeconds)
        assertEquals(8, workoutConfig(JSONObject("""{"exercise":"intervals","work_seconds":30,"rest_seconds":15,"sets":8}""")).sets)
        for (json in listOf(
            """{"exercise":"squats","sets":2}""",
            """{"exercise":"squats","target_reps":"12"}""",
            """{"exercise":"squats","target_reps":12.5}""",
            """{"exercise":"squats","sets":4294967297}""",
            """{"exercise":"plank","target_reps":12}""",
            """{"exercise":"squats","extra":true}""",
            """{"target_reps":12}""",
        )) {
            try { workoutConfig(JSONObject(json)); fail("Accepted $json") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
