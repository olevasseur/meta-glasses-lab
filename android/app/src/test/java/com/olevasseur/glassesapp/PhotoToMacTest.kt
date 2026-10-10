package com.olevasseur.glassesapp

import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhotoToMacTest {
    @get:Rule val temporary = TemporaryFolder()
    private val jpeg = byteArrayOf(1, 2, 3)
    private fun outbox() = PhotoOutbox(File(temporary.root, "photos"))
    private fun call(args: JSONObject = JSONObject()) = FunctionCall("p", "save_photo", args)
    private fun ok() = JSONObject().put("status", "ok").put("filename", "glasses-2026-10-10_13-14-15.jpg")
        .put("message", "Saved to your Mac's Downloads as glasses-2026-10-10_13-14-15.jpg")

    @Test fun successfulCaptureStaysOutOfGeminiAndRemovesOutboxOnlyAfterAck() = runBlocking {
        val box = outbox()
        val steps = mutableListOf<String>()
        val controller = PhotoToMacController(box, { steps += "capture"; jpeg }, { steps += "shutter" }, {
            steps += "upload"
            assertArrayEquals(jpeg, box.first()!!.jpeg)
            assertEquals("beach", it.label)
            ok()
        })
        val result = controller.tool(call(JSONObject().put("label", "beach")))
        assertEquals("ok", result.response.getString("status"))
        assertNull(result.jpeg)
        assertNull(box.first())
        assertEquals(listOf("capture", "shutter", "upload"), steps)
    }

    @Test fun unreachableMacKeepsDurablePhotoAndRestartRetriesSameIdAndBytes() = runBlocking {
        var initial: PendingPhoto? = null
        val controller = PhotoToMacController(outbox(), { jpeg }, {}, {
            initial = it
            throw BackendConnectionException(IOException("offline"))
        })
        val result = controller.tool(call())
        assertEquals("queued", result.response.getString("status"))
        assertEquals("Saved on the phone; I'll send it when your Mac is reachable", result.response.getString("message"))
        assertNull(result.jpeg)
        val restarted = PhotoToMacController(outbox(), { error("must not recapture") }, { error("no shutter on retry") }, {
            assertEquals(initial!!.id, it.id)
            assertEquals(initial!!.capturedAt, it.capturedAt)
            assertArrayEquals(initial!!.jpeg, it.jpeg)
            ok()
        })
        assertEquals("ok", restarted.retry()!!.getString("status"))
        assertNull(outbox().first())
    }

    @Test fun failedAndMalformedAcknowledgementsNeverLosePhoto() = runBlocking {
        for (result in listOf(JSONObject().put("status", "error"), ok().put("filename", "../escape"))) {
            val box = outbox()
            val controller = PhotoToMacController(box, { jpeg }, {}, { result })
            assertEquals("queued", controller.tool(call()).response.getString("status"))
            assertNotNull(box.first())
            assertNull(controller.retry())
            assertNotNull(box.first())
            temporary.root.listFiles()!!.forEach { it.deleteRecursively() }
        }
    }

    @Test fun httpRejectionIsHonestAndRetained() = runBlocking {
        val controller = PhotoToMacController(outbox(), { jpeg }, {}, { throw BackendHttpException(401) })
        val result = controller.tool(call()).response
        assertEquals("queued", result.getString("status"))
        assertTrue(result.getString("message").contains("rejected delivery (HTTP 401)"))
        assertNotNull(outbox().first())
    }

    @Test fun fullOutboxRefusesCaptureWithoutEvicting() = runBlocking {
        val box = outbox()
        repeat(PhotoOutbox.MAX_COUNT) { box.add(jpeg, null) }
        val first = box.first()!!.id
        val controller = PhotoToMacController(box, { error("must check space first") }, {}, { error("no upload") })
        val result = controller.tool(call()).response
        assertEquals("error", result.getString("status"))
        assertTrue(result.getString("message").contains("outbox is full"))
        assertEquals(first, box.first()!!.id)
        assertEquals(PhotoOutbox.MAX_COUNT, File(temporary.root, "photos").listFiles()!!.size)
    }

    @Test fun outboxBoundsBytesAndRejectsOversizedPhoto() {
        val box = outbox()
        try { box.add(ByteArray(PhotoOutbox.MAX_PHOTO_BYTES + 1), null); fail() } catch (_: IllegalArgumentException) {}
        repeat(5) { box.add(ByteArray(PhotoOutbox.MAX_PHOTO_BYTES), null) }
        try { box.checkRoom(); fail() } catch (_: IllegalStateException) {}
        assertNotNull(box.first())
    }

    @Test fun invalidLabelsAndPathsNeverCapture() = runBlocking {
        val controller = PhotoToMacController(outbox(), { error("no capture") }, {}, { error("no upload") })
        for (args in listOf(JSONObject().put("label", "../file"), JSONObject().put("label", 3),
            JSONObject().put("label", "x".repeat(81)), JSONObject().put("path", "/tmp/file"))) {
            assertEquals("error", controller.tool(call(args)).response.getString("status"))
        }
        assertNull(outbox().first())
    }

    @Test fun captureFailureDoesNotClaimSaved() = runBlocking {
        val controller = PhotoToMacController(outbox(), { error("Camera unavailable") }, { error("no shutter") }, { error("no upload") })
        val result = controller.tool(call()).response
        assertEquals("error", result.getString("status"))
        assertEquals("Camera unavailable", result.getString("message"))
        assertNull(outbox().first())
    }

    @Test fun cancelledUploadIsRetainedForLaterRetry() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val controller = PhotoToMacController(outbox(), { jpeg }, {}, {
            started.complete(Unit)
            CompletableDeferred<JSONObject>().await()
        })
        val job = async { controller.tool(call()) }
        started.await()
        job.cancel()
        job.join()
        assertNotNull(outbox().first())
        val retry = PhotoToMacController(outbox(), { error("no capture") }, {}, { ok() })
        assertEquals("ok", retry.retry()!!.getString("status"))
    }

    @Test fun routingIsLocalEvenWithOldTokenAndRespectsFeatureAccess() = runBlocking {
        for (metadata in listOf(emptySet(), setOf("save_photo"))) {
            val result = routeTool(call(), metadata, { ToolResult(ok()) }, { error("must never use /tools") })
            assertEquals("ok", result.response.getString("status"))
            assertNull(result.jpeg)
        }
        for (access in listOf(FeatureAccess(true, false, false), FeatureAccess(false, true, false), FeatureAccess(false, false, true))) {
            assertNotNull(access.denial(call()))
        }
        val router = ConversationToolController({ FeatureAccess(false, false, false) }, { error("visual") },
            { error("coach") }, { error("reading") }, { error("workout") }, { error("gesture") }, { error("vision") }, { ToolResult(ok()) })
        assertEquals("ok", router.tool(call()).response.getString("status"))
    }

    @Test fun serializedStillCaptureDoesNotSendImageToLive() = runBlocking {
        val controller = VisionController(this, { error("must use full-resolution still") }, { error("must not send to Gemini") }, {}, {}, {})
        assertArrayEquals(jpeg, controller.capturePhoto { jpeg })
        controller.stop()
    }

    @Test fun uploadUsesConfiguredUrlRawJpegSecretAndStableMetadata() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(ok().toString()))
            val client = BackendClient(OkHttpClient(), server.url("/prefix/").toString(), "test-secret")
            val photo = PendingPhoto(UUID.randomUUID().toString(), "2026-10-10T13:14:15-04:00", "beach & sun", jpeg)
            client.savePhoto(photo)
            val request = server.takeRequest()
            assertEquals("/prefix/photos", request.requestUrl!!.encodedPath)
            assertEquals(photo.id, request.requestUrl!!.queryParameter("upload_id"))
            assertEquals(photo.capturedAt, request.requestUrl!!.queryParameter("captured_at"))
            assertEquals(photo.label, request.requestUrl!!.queryParameter("label"))
            assertEquals("test-secret", request.getHeader("X-App-Secret"))
            assertEquals("image/jpeg", request.getHeader("Content-Type"))
            assertArrayEquals(jpeg, request.body.readByteArray())
        }
    }

    @Test fun photoNetworkFailureDoesNotTriggerGlobalOfflineTransition() = runBlocking {
        val http = OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build()
        try {
            val client = BackendClient(http, "https://example.test", "test", { error("would cancel the tool reply") })
            try {
                client.savePhoto(PendingPhoto(UUID.randomUUID().toString(), "2026-10-10T13:14:15Z", null, jpeg))
                fail()
            } catch (_: BackendConnectionException) {}
        } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }
}
