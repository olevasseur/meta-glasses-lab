package com.olevasseur.glassesapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PhotoToMacController(
    private val outbox: PhotoOutbox,
    private val capture: suspend () -> ByteArray,
    private val shutter: () -> Unit,
    private val upload: suspend (PendingPhoto) -> JSONObject,
    private val onNotice: (String) -> Unit = {},
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    companion object {
        const val TOOL = "save_photo"
        const val FAILED_MESSAGE = "One photo couldn't be saved on your Mac"
    }
    private val lock = Mutex()
    private var retryAt = 0L
    private var retryDelay = 60_000L
    private var permissionNoticeId: String? = null

    suspend fun tool(call: FunctionCall): ToolResult {
        if (!lock.tryLock()) return ToolResult(toolError("Photo delivery is busy; try again shortly"))
        var saved = false
        try {
            require(call.name == TOOL && call.args.keys().asSequence().all { it == "label" }) { "Invalid photo arguments" }
            val label = if (call.args.has("label")) {
                val raw = call.args.opt("label") as? String ?: error("Invalid photo label")
                raw.take(80).replace(Regex("[^a-zA-Z0-9_-]+"), "-").trim('-', '_').take(60).ifEmpty { null }
            } else null
            withContext(Dispatchers.IO) { outbox.checkRoom() }
            val jpeg = capture()
            // Once captured, finish the atomic local write even if the Live call is cancelled.
            val photo = withContext(Dispatchers.IO + NonCancellable) { outbox.add(jpeg, label) }
            saved = true
            shutter()
            return ToolResult(deliver(photo))
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            return ToolResult(if (saved) queued() else toolError("Photo capture stopped; nothing saved"))
        } catch (e: Exception) {
            return ToolResult(if (saved) queued(e) else toolError(e.message ?: "Photo capture failed; nothing saved"))
        } finally { lock.unlock() }
    }

    /** Skip permanent failures; transient failures retain their ID/bytes and back off up to 15 minutes. */
    suspend fun retry(): JSONObject? {
        if (!lock.tryLock()) return null
        try {
            if (now() < retryAt) return null
            while (true) {
                val photo = try { withContext(Dispatchers.IO) { outbox.first() } }
                catch (_: UnreadablePhotoException) {
                    onNotice(FAILED_MESSAGE)
                    continue
                } ?: return null
                val result = deliver(photo)
                if (result.optString("status") == "error") {
                    onNotice(FAILED_MESSAGE)
                    continue
                }
                if (result.optString("code") == "save_dir_permission" && permissionNoticeId != photo.id) {
                    permissionNoticeId = photo.id
                    onNotice(result.getString("message"))
                }
                return result
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return null }
        finally { lock.unlock() }
    }

    private suspend fun deliver(photo: PendingPhoto): JSONObject {
        try {
            val result = upload(photo)
            check(result.optString("status") == "ok" && result.optString("filename").matches(Regex("glasses-[A-Za-z0-9_-]+\\.jpg"))) {
                "Mac did not confirm the photo save"
            }
            withContext(Dispatchers.IO + NonCancellable) { outbox.delivered(photo) }
            retryAt = 0
            retryDelay = 60_000
            permissionNoticeId = null
            return result
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (e is BackendHttpException && e.code in 400..499 && e.code !in setOf(401, 408, 429)) {
                withContext(Dispatchers.IO + NonCancellable) { outbox.failed(photo, e.code) }
                return toolError(FAILED_MESSAGE)
            }
            retryAt = now() + retryDelay
            retryDelay = (retryDelay * 2).coerceAtMost(15 * 60_000)
            return queued(e)
        }
    }

    private fun queued(error: Exception? = null): JSONObject {
        if (error is BackendHttpException && error.detail == "save_dir_permission") {
            return JSONObject().put("status", "queued").put("code", "save_dir_permission")
                .put("message", "Your Mac couldn't write to Downloads (or the configured photo folder). Check backend permissions; the photo is saved on the phone for retry.")
        }
        val message = if (error is BackendHttpException && error.code in 400..499 && error.code != 429)
            "Saved on the phone; your Mac rejected delivery (HTTP ${error.code}). Check the backend; the photo is kept for retry."
        else "Saved on the phone; I'll send it when your Mac is reachable"
        return JSONObject().put("status", "queued").put("message", message)
    }
}
