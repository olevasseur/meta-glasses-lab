package com.olevasseur.glassesapp

import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputSource
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PageCaptureTest {
    @get:Rule val temporary = TemporaryFolder()
    private val jpeg = byteArrayOf(1, 2, 3)
    private fun box() = PhotoOutbox(File(temporary.root, "pages"), retainFailures = false)
    private fun ok() = JSONObject().put("status", "ok").put("highlights", 2).put("handwritten", 1)
        .put("doc_title", "Reading Notes").put("message", "Saved 2 highlights, 1 handwritten notes to Reading Notes")
        .put("preview", "A highlight")

    @Test fun capturePrecedenceApprovalThenEnabledSingleShortThenUnclaimedAndHoldUnaffected() {
        for (approval in listOf(true, false)) for (enabled in listOf(true, false)) for (press in CapturePressType.entries) {
            val calls = mutableListOf<String>()
            val event = InputEvent.Capture(press, InputSource.CAPTURE_BUTTON, 1)
            val consumed = approvalCapture(event, unclaimed = { calls += "unclaimed" }, pageCapture = {
                if (enabled) { calls += "page"; true } else false
            }, confirm = { if (approval) calls += "approval"; approval })
            val expected = when {
                approval -> "approval"
                press == CapturePressType.HOLD -> null
                enabled && press == CapturePressType.SHORT_PRESS -> "page"
                else -> "unclaimed"
            }
            assertEquals(listOfNotNull(expected), calls)
            assertEquals(expected != null, consumed)
            assertEquals(if (press == CapturePressType.HOLD) GlassesTrigger.CONVERSATION else null, glassesTrigger(event))
        }
        assertFalse(approvalCapture(InputEvent.Select(InputSource.CAPTOUCH, 1),
            pageCapture = { error("touchpad is not capture") }, confirm = { error("not approval") }))
        assertFalse(approvalCapture(InputEvent.Capture(CapturePressType.SHORT_PRESS, InputSource.UNKNOWN, 1),
            pageCapture = { error("unknown source") }, confirm = { error("not approval") }))
    }

    @Test fun successHasShutterThenConfirmedCueAndNotificationWithPreview() = runBlocking {
        val steps = mutableListOf<String>()
        val notices = mutableListOf<Pair<String, String>>()
        val controller = PageCaptureController(box(), { steps += "capture"; jpeg }, {
            steps += "upload"
            assertEquals("reading notes", it.label)
            assertArrayEquals(jpeg, box().first()!!.jpeg)
            ok()
        }, { steps += it.name }, { text, preview -> notices += text to preview })
        controller.capture("reading notes")
        assertEquals(listOf("capture", "SHUTTER", "upload", "SUCCESS"), steps)
        assertEquals(listOf(ok().getString("message") to "A highlight"), notices)
        assertNull(box().first())
        assertTrue(File(temporary.root, "pages").listFiles()!!.isEmpty())
    }

    @Test fun blurRejectsBeforeOutboxAndUploadWithDistinctRetryCue() = runBlocking {
        val cues = mutableListOf<PageCue>()
        val notices = mutableListOf<String>()
        val controller = PageCaptureController(box(), { throw BlurryTextFrame() }, { error("no upload") },
            { cues += it }, { text, _ -> notices += text })
        controller.capture("reading notes")
        assertEquals(listOf(PageCue.RETRY), cues)
        assertTrue(notices.single().contains("Blurry"))
        assertNull(box().first())
    }

    @Test fun restartRetriesStableIdBytesTimestampAndFrozenNicknameWithoutRecapture() = runBlocking {
        var initial: PendingPhoto? = null
        val cues = mutableListOf<PageCue>()
        PageCaptureController(box(), { jpeg }, { initial = it; throw IOException("offline") }, { cues += it }, { _, _ -> })
            .capture("original nickname")
        assertNotNull(box().first())
        val restart = PageCaptureController(box(), { error("no recapture") }, {
            assertEquals(initial!!.id, it.id)
            assertEquals(initial!!.capturedAt, it.capturedAt)
            assertEquals("original nickname", it.label)
            assertArrayEquals(jpeg, it.jpeg)
            ok()
        }, { cues += it }, { _, _ -> })
        restart.retry()
        assertNull(box().first())
        assertEquals(listOf(PageCue.SHUTTER, PageCue.ERROR, PageCue.SUCCESS), cues)
    }

    @Test fun transientFailuresBackOffAndMalformedReceiptRetainsPage() = runBlocking {
        for (code in listOf(401, 408, 429, 500, 503)) {
            var time = 0L
            var attempts = 0
            val original = box().add(jpeg, "reading notes")
            val controller = PageCaptureController(box(), { error("no capture") }, {
                attempts++
                assertEquals(original.id, it.id)
                throw BackendHttpException(code)
            }, {}, { _, _ -> }, now = { time })
            for (delay in listOf(60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L)) {
                val before = attempts
                controller.retry()
                assertEquals(before + 1, attempts)
                time += delay - 1
                controller.retry()
                assertEquals(before + 1, attempts)
                time++
            }
            box().delivered(original)
        }
        val malformed = PageCaptureController(box(), { jpeg }, { JSONObject().put("status", "ok") }, {}, { _, _ -> })
        malformed.capture("reading notes")
        assertNotNull(box().first())
    }

    @Test fun processedAndPermanentFailuresRemoveImagesWithoutArchiveAndShowReason() = runBlocking {
        val notices = mutableListOf<String>()
        val cues = mutableListOf<PageCue>()
        for (status in listOf("nothing_found", "already_saved", "error")) {
            val controller = PageCaptureController(box(), { jpeg }, {
                JSONObject().put("status", status).put("message", "Result $status")
            }, { cues += it }, { text, _ -> notices += text })
            controller.capture("reading notes")
            assertNull(box().first())
            assertEquals("Result $status", notices.last())
        }
        assertEquals(listOf(PageCue.SHUTTER, PageCue.NOTHING, PageCue.SHUTTER, PageCue.SUCCESS, PageCue.SHUTTER, PageCue.ERROR), cues)
        val controller = PageCaptureController(box(), { jpeg }, {
            throw BackendHttpException(422, "Create a doc nicknamed 'reading notes' first")
        }, {}, { text, _ -> notices += text })
        controller.capture("reading notes")
        assertEquals("Create a doc nicknamed 'reading notes' first", notices.last())
        assertTrue(File(temporary.root, "pages").listFiles()!!.isEmpty())
    }

    @Test fun retrySkipsCorruptAndRejectedImagesAndContinuesToNextPage() = runBlocking {
        val bad = box().add(jpeg, "first")
        File(temporary.root, "pages/${bad.id}.photo").apply { writeBytes(byteArrayOf(0)); setLastModified(1) }
        val rejected = box().add(jpeg, "missing")
        File(temporary.root, "pages/${rejected.id}.photo").setLastModified(2)
        val good = box().add(jpeg, "good")
        val uploaded = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val controller = PageCaptureController(box(), { error("no capture") }, {
            uploaded += it.id
            if (it.id == rejected.id) throw BackendHttpException(422, "Missing doc")
            ok()
        }, {}, { text, _ -> notices += text })
        controller.retry()
        assertEquals(listOf(rejected.id, good.id), uploaded)
        assertEquals(3, notices.size)
        assertTrue(File(temporary.root, "pages").listFiles()!!.isEmpty())
        controller.retry()
        assertEquals(3, notices.size)
    }

    @Test fun fullOutboxRejectsBeforeCameraAndNeverEvictsPendingPages() = runBlocking {
        repeat(PhotoOutbox.MAX_COUNT) { box().add(jpeg, "reading notes") }
        val cues = mutableListOf<PageCue>()
        val controller = PageCaptureController(box(), { error("must not capture") }, { error("must not upload") },
            { cues += it }, { _, _ -> })
        controller.capture("reading notes")
        assertEquals(listOf(PageCue.ERROR), cues)
        assertEquals(PhotoOutbox.MAX_COUNT, File(temporary.root, "pages").listFiles()!!.size)
    }

    @Test fun cancellationKeepsDurablePageAndBusyPressDoesNotCaptureAgain() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var captures = 0
        val cues = mutableListOf<PageCue>()
        val controller = PageCaptureController(box(), { captures++; jpeg }, {
            started.complete(Unit)
            CompletableDeferred<JSONObject>().await()
        }, { cues += it }, { _, _ -> })
        val job = async { controller.capture("reading notes") }
        started.await()
        controller.capture("other")
        assertEquals(1, captures)
        assertEquals(listOf(PageCue.SHUTTER, PageCue.ERROR), cues)
        job.cancel(); job.join()
        assertEquals("reading notes", box().first()!!.label)
        PageCaptureController(box(), { error("no recapture") }, { ok() }, {}, { _, _ -> }).retry()
        assertNull(box().first())
    }

    @Test fun rawUploadUsesAuthMetadataAndNeverTriggersLiveOfflineFallback() = runBlocking {
        val requests = mutableListOf<Request>()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            assertEquals(60_000, chain.readTimeoutMillis())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ok().toString().toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val client = BackendClient(transport, "https://example.test/prefix/", "test-secret", { error("no global fallback") })
            val page = PendingPhoto(UUID.randomUUID().toString(), "2026-10-16T13:14:15-04:00", "notes & books", jpeg)
            client.savePage(page)
            val request = requests.single()
            assertEquals("/prefix/pages", request.url.encodedPath)
            assertEquals(page.id, request.url.queryParameter("upload_id"))
            assertEquals(page.capturedAt, request.url.queryParameter("captured_at"))
            assertEquals(page.label, request.url.queryParameter("doc"))
            assertEquals("test-secret", request.header("X-App-Secret"))
            assertEquals("image/jpeg", request.body!!.contentType().toString())
            val body = Buffer()
            request.body!!.writeTo(body)
            assertArrayEquals(jpeg, body.readByteArray())
        } finally { transport.dispatcher.executorService.shutdown(); transport.connectionPool.evictAll() }
        val http = OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build()
        try {
            val client = BackendClient(http, "https://example.test", "test", { error("no global fallback") })
            try {
                client.savePage(PendingPhoto(UUID.randomUUID().toString(), "2026-10-16T13:14:15Z", "reading notes", jpeg))
                fail()
            } catch (_: BackendConnectionException) {}
        } finally { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }
}
