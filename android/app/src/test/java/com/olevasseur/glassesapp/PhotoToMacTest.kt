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

    @Test fun invalidArgumentTypesAndPathsNeverCapture() = runBlocking {
        val controller = PhotoToMacController(outbox(), { error("no capture") }, {}, { error("no upload") })
        for (args in listOf(JSONObject().put("label", 3), JSONObject().put("path", "/tmp/file"))) {
            assertEquals("error", controller.tool(call(args)).response.getString("status"))
        }
        assertNull(outbox().first())
    }

    @Test fun labelsAreSanitizedWithoutPreventingCapture() = runBlocking {
        for ((raw, safe) in mapOf("../file" to "file", "a/b" to "a-b", ".." to null, "" to null,
            "a\\b\u0000c" to "a-b-c", "x".repeat(81) to "x".repeat(60))) {
            val controller = PhotoToMacController(outbox(), { jpeg }, {}, { assertEquals(safe, it.label); ok() })
            assertEquals("ok", controller.tool(call(JSONObject().put("label", raw))).response.getString("status"))
        }
    }

    @Test fun permanentRejectionIsArchivedAndReportedByTool() = runBlocking {
        for (code in listOf(400, 403, 409, 413, 415, 422)) {
            val controller = PhotoToMacController(outbox(), { jpeg }, {}, { throw BackendHttpException(code) })
            val result = controller.tool(call()).response
            assertEquals("error", result.getString("status"))
            assertEquals(PhotoToMacController.FAILED_MESSAGE, result.getString("message"))
            assertNull(outbox().first())
            assertNull(controller.retry())
        }
        assertEquals(6, File(temporary.root, "photos/failed").listFiles()!!.size)
    }

    @Test fun retrySkipsCorruptAndRejectedEntriesAndNotifiesOnceEach() = runBlocking {
        val box = outbox()
        val corrupt = box.add(jpeg, null)
        File(temporary.root, "photos/${corrupt.id}.photo").apply { writeBytes(byteArrayOf(0)); setLastModified(1) }
        val rejected = box.add(jpeg, null)
        File(temporary.root, "photos/${rejected.id}.photo").setLastModified(2)
        val good = box.add(jpeg, null)
        val uploaded = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val controller = PhotoToMacController(box, { error("no recapture") }, {}, {
            uploaded += it.id
            if (it.id == rejected.id) throw BackendHttpException(409)
            ok()
        }, onNotice = { notices += it })
        assertEquals("ok", controller.retry()!!.getString("status"))
        assertEquals(listOf(rejected.id, good.id), uploaded)
        assertEquals(List(2) { PhotoToMacController.FAILED_MESSAGE }, notices)
        assertNull(controller.retry())
        val restarted = PhotoToMacController(outbox(), { error("no capture") }, {}, { error("no upload") }, onNotice = { notices += it })
        assertNull(restarted.retry())
        assertEquals(2, notices.size)
        assertEquals(2, File(temporary.root, "photos/failed").listFiles()!!.size)
    }

    @Test fun transientErrorsRetainOriginalPhotoAndBackOff() = runBlocking {
        for (code in listOf(401, 408, 429, 500, 503, 507)) {
            var time = 0L
            var uploads = 0
            val photo = outbox().add(jpeg, null)
            val controller = PhotoToMacController(outbox(), { error("no capture") }, {}, {
                assertEquals(photo.id, it.id)
                assertArrayEquals(jpeg, it.jpeg)
                uploads++
                throw BackendHttpException(code)
            }, now = { time })
            for (delay in listOf(60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L)) {
                assertEquals("queued", controller.retry()!!.getString("status"))
                time += delay - 1
                assertNull(controller.retry())
                time++
            }
            assertEquals(6, uploads)
            assertEquals(photo.id, outbox().first()!!.id)
            outbox().delivered(photo)
        }
    }

    @Test fun failedArchivePrunesOldestByCountAndBytes() {
        val box = outbox()
        val oldest = box.add(jpeg, null)
        box.failed(oldest, 422)
        File(temporary.root, "photos/failed/${oldest.id}.photo").setLastModified(1)
        repeat(PhotoOutbox.MAX_COUNT) { box.failed(box.add(jpeg, null), 422) }
        val archive = File(temporary.root, "photos/failed")
        assertEquals(PhotoOutbox.MAX_COUNT, archive.listFiles()!!.size)
        assertFalse(File(archive, "${oldest.id}.photo").exists())
        val largeOldest = box.add(ByteArray(PhotoOutbox.MAX_PHOTO_BYTES), null)
        box.failed(largeOldest, 422)
        File(archive, "${largeOldest.id}.photo").setLastModified(1)
        repeat(5) { box.failed(box.add(ByteArray(PhotoOutbox.MAX_PHOTO_BYTES), null), 422) }
        assertTrue(archive.listFiles()!!.sumOf { it.length() } <= PhotoOutbox.MAX_TOTAL_BYTES)
        assertFalse(File(archive, "${largeOldest.id}.photo").exists())
        assertNull(box.first())
    }

    @Test fun permissionDenialExplainsDownloadsAndKeepsPhotoUntilRecovery() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(507).setBody("{\"detail\":\"save_dir_permission\"}"))
            server.enqueue(MockResponse().setResponseCode(507).setBody("{\"detail\":\"save_dir_permission\"}"))
            server.enqueue(MockResponse().setBody(ok().toString()))
            var time = 0L
            val notices = mutableListOf<String>()
            val client = BackendClient(OkHttpClient(), server.url("/").toString(), "test")
            val controller = PhotoToMacController(outbox(), { jpeg }, {}, client::savePhoto,
                onNotice = { notices += it }, now = { time })
            val result = controller.tool(call()).response
            assertEquals("queued", result.getString("status"))
            assertEquals("save_dir_permission", result.getString("code"))
            assertTrue(result.getString("message").contains("couldn't write to Downloads"))
            assertFalse(result.getString("message").contains("reachable"))
            val original = outbox().first()!!.id
            time = 60_000
            assertEquals("queued", controller.retry()!!.getString("status"))
            assertEquals(1, notices.size)
            assertEquals(original, outbox().first()!!.id)
            time = 180_000
            assertEquals("ok", controller.retry()!!.getString("status"))
            assertNull(outbox().first())
            repeat(3) { assertEquals(original, server.takeRequest().requestUrl!!.queryParameter("upload_id")) }
        }
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
