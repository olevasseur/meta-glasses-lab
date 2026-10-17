package com.olevasseur.glassesapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class PageCue { SHUTTER, SUCCESS, RETRY, NOTHING, ERROR }

/** Uses the photo outbox format; label holds the nickname frozen at capture time. No Live or TTS. */
class PageCaptureController(
    private val outbox: PhotoOutbox,
    private val capture: suspend () -> ByteArray,
    private val upload: suspend (PendingPhoto) -> JSONObject,
    private val cue: (PageCue) -> Unit,
    private val notice: (String, String) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Mutex()
    private var retryAt = 0L
    private var retryDelay = 60_000L

    suspend fun capture(doc: String) {
        if (!lock.tryLock()) { report(toolError("Page capture is busy; try again shortly")); return }
        try {
            withContext(Dispatchers.IO) { outbox.checkRoom() }
            val jpeg = capture()
            sound(PageCue.SHUTTER)
            val page = withContext(Dispatchers.IO + NonCancellable) { outbox.add(jpeg, doc) }
            report(deliver(page))
        } catch (e: BlurryTextFrame) {
            sound(PageCue.RETRY)
            notice("Blurry page; hold still and try again", "Nothing uploaded")
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            report(toolError("Page capture stopped; try again"))
        } catch (e: Exception) {
            report(toolError(e.message ?: "Page capture failed"))
        } finally { lock.unlock() }
    }

    suspend fun retry() {
        if (!lock.tryLock()) return
        try {
            if (now() < retryAt) return
            while (true) {
                val page = try { withContext(Dispatchers.IO) { outbox.first() } }
                catch (_: UnreadablePhotoException) {
                    report(toolError("Unreadable queued page removed; capture it again"))
                    continue
                } ?: return
                val result = deliver(page)
                report(result)
                if (result.optString("status") == "queued") return
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { report(toolError("Page outbox unavailable; reopen the app to retry")) }
        finally { lock.unlock() }
    }

    private suspend fun deliver(page: PendingPhoto): JSONObject {
        try {
            val result = upload(page)
            val status = result.optString("status")
            check(status in setOf("ok", "nothing_found", "already_saved", "error") && result.optString("message").isNotBlank()) {
                "Backend did not confirm page processing"
            }
            if (status == "ok") check(result.optInt("highlights", -1) >= 0 && result.optInt("handwritten", -1) >= 0 &&
                result.optString("doc_title").isNotBlank()) { "Invalid page receipt" }
            withContext(Dispatchers.IO + NonCancellable) { outbox.delivered(page) }
            retryAt = 0
            retryDelay = 60_000
            return result
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is BackendHttpException && e.code in 400..499 && e.code !in setOf(401, 408, 429)) {
                withContext(Dispatchers.IO + NonCancellable) { outbox.failed(page, e.code) }
                return toolError(e.detail.ifBlank { "Page rejected (HTTP ${e.code}); capture it again" })
            }
            retryAt = now() + retryDelay
            retryDelay = (retryDelay * 2).coerceAtMost(900_000)
            val reason = if (e is BackendHttpException) e.detail.ifBlank { "Backend HTTP ${e.code}" } else "Can't reach page processing"
            return JSONObject().put("status", "queued").put("message", "$reason; page saved privately on phone for retry")
        }
    }

    private fun sound(value: PageCue) { runCatching { cue(value) } }
    private fun report(result: JSONObject) {
        sound(when (result.optString("status")) {
            "ok", "already_saved" -> PageCue.SUCCESS
            "nothing_found" -> PageCue.NOTHING
            else -> PageCue.ERROR
        })
        notice(result.getString("message"), result.optString("preview").take(240))
    }
}
