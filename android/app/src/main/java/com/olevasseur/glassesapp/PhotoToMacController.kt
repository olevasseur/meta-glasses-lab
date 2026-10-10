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
) {
    companion object { const val TOOL = "save_photo" }
    private val lock = Mutex()

    suspend fun tool(call: FunctionCall): ToolResult {
        if (!lock.tryLock()) return ToolResult(toolError("Photo delivery is busy; try again shortly"))
        var saved = false
        try {
            require(call.name == TOOL && call.args.keys().asSequence().all { it == "label" }) { "Invalid photo arguments" }
            val label = if (call.args.has("label")) {
                (call.args.opt("label") as? String)?.trim()?.also {
                    require(it.isNotEmpty() && it.length <= 80 && !it.contains("..") && it.none { c -> c == '/' || c == '\\' || c == '\u0000' }) { "Invalid photo label" }
                } ?: error("Invalid photo label")
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

    /** One item per retry tick, also on app restart. A failed upload keeps the original ID and bytes. */
    suspend fun retry(): JSONObject? {
        if (!lock.tryLock()) return null
        try {
            val photo = withContext(Dispatchers.IO) { outbox.first() } ?: return null
            return deliver(photo)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return null }
        finally { lock.unlock() }
    }

    private suspend fun deliver(photo: PendingPhoto): JSONObject {
        val result = upload(photo)
        check(result.optString("status") == "ok" && result.optString("filename").matches(Regex("glasses-[A-Za-z0-9_-]+\\.jpg"))) {
            "Mac did not confirm the photo save"
        }
        withContext(Dispatchers.IO + NonCancellable) { outbox.delivered(photo) }
        return result
    }

    private fun queued(error: Exception? = null): JSONObject {
        val message = if (error is BackendHttpException && error.code in 400..499 && error.code != 429)
            "Saved on the phone; your Mac rejected delivery (HTTP ${error.code}). Check the backend; the photo is kept for retry."
        else "Saved on the phone; I'll send it when your Mac is reachable"
        return JSONObject().put("status", "queued").put("message", message)
    }
}
