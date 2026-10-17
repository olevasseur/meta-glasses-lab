package com.olevasseur.glassesapp

import java.io.IOException
import java.io.File
import okhttp3.RequestBody.Companion.asRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

class BackendClient(
    private val client: OkHttpClient,
    baseUrl: String,
    private val secret: String,
    private val onUnavailable: (String) -> Unit = {},
) {
    private val base = baseUrl.trimEnd('/').toHttpUrl()

    suspend fun token(ptt: Boolean, meeting: Boolean = false): LiveToken =
        token(if (ptt) ConversationMode.PTT else ConversationMode.AUTO, meeting = meeting)

    suspend fun token(mode: ConversationMode, silenceMs: Int = 2000, translationLanguage: String? = null, meeting: Boolean = false, avoidModels: Set<String> = emptySet(), reconnectContext: String? = null): LiveToken {
        val body = if (meeting) JSONObject().put("mode", "meeting").put("silence_ms", silenceMs.coerceIn(500, 5000))
            else Translation.tokenRequest(mode, silenceMs, translationLanguage)
        if (avoidModels.isNotEmpty()) body.put("avoid_models", JSONArray(avoidModels.toList()))
        if (reconnectContext != null) body.put("reconnect_context", reconnectContext)
        val json = post(listOf("token"), body)
        val local = json.optJSONArray("client_tools")
        return LiveToken(json.getString("token"), json.getString("model"), json.getString("expires_at"),
            List(local?.length() ?: 0) { local!!.getString(it) }.toSet())
    }

    suspend fun tool(call: FunctionCall): JSONObject {
        require(call.name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}"))) { "Invalid tool name" }
        return post(listOf("tools", call.name), call.args)
    }

    suspend fun transcripts(sessionId: String, turns: JSONArray): JSONObject = post(
        listOf("transcripts"), JSONObject().put("session_id", sessionId).put("turns", turns),
    )

    suspend fun endSession(sessionId: String): JSONObject = post(
        listOf("sessions", sessionId, "end"), JSONObject(),
    )

    suspend fun events(after: Long): JSONObject {
        val url = base.newBuilder().addPathSegment("events")
            .addQueryParameter("after", after.toString()).addQueryParameter("wait", "20").build()
        return execute(Request.Builder().url(url).header("X-App-Secret", secret).get().build())
    }

    suspend fun usage(): JSONObject = execute(Request.Builder()
        .url(base.newBuilder().addPathSegment("usage").build())
        .header("X-App-Secret", secret).get().build())

    suspend fun reportUsage(report: JSONObject): JSONObject = post(listOf("usage", "live"), report)

    suspend fun geminiHealth(): JSONObject = execute(Request.Builder()
        .url(base.newBuilder().addPathSegments("health/gemini").build())
        .header("X-App-Secret", secret).get().build())

    suspend fun offlineTool(id: String, call: FunctionCall): JSONObject {
        val url = base.newBuilder().addPathSegment("tools").addPathSegment(call.name).build()
        return execute(Request.Builder().url(url).header("X-App-Secret", secret).header("X-Offline-Id", id)
            .post(call.args.toString().toRequestBody("application/json".toMediaType())).build())
    }

    suspend fun diagnosticHealth(): JSONObject = execute(Request.Builder()
        .url(base.newBuilder().addPathSegments("diagnostics/health").build())
        .header("X-App-Secret", secret).get().build())

    suspend fun overview(): JSONObject = execute(Request.Builder()
        .url(base.newBuilder().addPathSegments("ui/overview").build())
        .header("X-App-Secret", secret).get().build())

    suspend fun nicknames(): List<String> = execute(Request.Builder()
        .url(base.newBuilder().addPathSegments("ui/nicknames").build())
        .header("X-App-Secret", secret).get().build(), notifyUnavailable = false).getJSONArray("nicknames")
        .let { names -> List(names.length()) { names.getString(it) } }

    suspend fun uploadLogs(entries: JSONArray): JSONObject = post(listOf("logs"), JSONObject().put("entries", entries))

    suspend fun approvalStatus(id: String): JSONObject = execute(Request.Builder()
        .url(base.newBuilder().addPathSegment("approvals").addPathSegment(id).build())
        .header("X-App-Secret", secret).get().build())

    suspend fun approveDevice(id: String, method: String): JSONObject =
        post(listOf("approvals", id, "device"), JSONObject().put("method", method))

    suspend fun denyApproval(id: String): JSONObject = post(listOf("approvals", id, "deny"), JSONObject())

    suspend fun workout(log: JSONObject): JSONObject = post(listOf("workouts"), log)
    suspend fun startMeeting(id: String, metadata: JSONObject): JSONObject = post(
        listOf("meetings", "start"), JSONObject(metadata.toString()).put("meeting_id", id),
    )

    suspend fun meetingChunk(id: String, index: Int, startMs: Long, file: File) {
        val url = base.newBuilder().addPathSegment("meetings").addPathSegment(id)
            .addPathSegment("chunks").addPathSegment(index.toString()).addQueryParameter("start_ms", startMs.toString()).build()
        val result = execute(Request.Builder().url(url).header("X-App-Secret", secret)
            .post(file.asRequestBody("audio/wav".toMediaType())).build())
        check(result.optString("status") == "ok" && result.getInt("index") == index)
    }
    suspend fun processMeeting(id: String) { post(listOf("meetings", id, "process"), JSONObject()) }
    suspend fun endChunkedMeeting(id: String, count: Int): JSONObject = execute(Request.Builder()
        .url(base.newBuilder().addPathSegment("meetings").addPathSegment(id).addPathSegment("end")
            .addQueryParameter("chunk_count", count.toString()).build())
        .header("X-App-Secret", secret).post("{}".toRequestBody("application/json".toMediaType())).build())

    suspend fun endMeeting(id: String): JSONObject = post(listOf("meetings", id, "end"), JSONObject())
    suspend fun visualFrame(jpeg: ByteArray, label: String?, pinned: Boolean, location: String?): JSONObject = post(
        listOf("visual", "frames"), JSONObject()
            .put("jpeg", java.util.Base64.getEncoder().encodeToString(jpeg))
            .put("captured_at", java.time.OffsetDateTime.now().toString())
            .put("label", label).put("pinned", pinned).put("location_label", location),
    )

    suspend fun savePhoto(photo: PendingPhoto): JSONObject {
        val url = base.newBuilder().addPathSegment("photos")
            .addQueryParameter("upload_id", photo.id).addQueryParameter("captured_at", photo.capturedAt)
            .apply { photo.label?.let { addQueryParameter("label", it) } }.build()
        return execute(Request.Builder().url(url).header("X-App-Secret", secret)
            .post(photo.jpeg.toRequestBody("image/jpeg".toMediaType())).build(), notifyUnavailable = false)
    }

    suspend fun savePage(page: PendingPhoto): JSONObject {
        val url = base.newBuilder().addPathSegment("pages")
            .addQueryParameter("upload_id", page.id).addQueryParameter("captured_at", page.capturedAt)
            .addQueryParameter("doc", page.label ?: "reading notes").build()
        return execute(Request.Builder().url(url).header("X-App-Secret", secret)
            .post(page.jpeg.toRequestBody("image/jpeg".toMediaType())).build(), notifyUnavailable = false, timeoutSeconds = 60)
    }

    suspend fun deleteVisual(minutes: Int): JSONObject = post(
        listOf("visual", "delete"), JSONObject().put("minutes", minutes),
    )

    suspend fun visualStatus(on: Boolean): JSONObject = post(
        listOf("visual", "status"), JSONObject().put("on", on),
    )

    private suspend fun post(path: List<String>, body: JSONObject): JSONObject {
        val url = base.newBuilder().apply { path.forEach { addPathSegment(it) } }.build()
        val request = Request.Builder().url(url).header("X-App-Secret", secret)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return execute(request)
    }

    private suspend fun execute(request: Request, notifyUnavailable: Boolean = true, timeoutSeconds: Long = 20): JSONObject {
        return suspendCancellableCoroutine { continuation ->
            val transport = if (timeoutSeconds > 20) client.newBuilder()
                .readTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS).build() else client
            val call = transport.newCall(request)
            if (!notifyUnavailable) call.timeout().timeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        val failure = BackendConnectionException(e)
                        if (notifyUnavailable) onUnavailable(failure.message!!)
                        continuation.resumeWithException(failure)
                    }
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!it.isSuccessful) throw BackendHttpException(it.code, runCatching { JSONObject(it.body?.string().orEmpty()).optString("detail") }.getOrDefault(""))
                            val result = JSONObject(it.body?.string() ?: error("Empty backend response"))
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: Exception) {
                            if (continuation.isActive) {
                                val failure = if (e is IOException && e !is BackendHttpException) BackendConnectionException(e) else e
                                if (notifyUnavailable && failure is BackendConnectionException) onUnavailable(failure.message!!)
                                continuation.resumeWithException(failure)
                            }
                        }
                    }
                }
            })
        }
    }
}

data class LiveToken(val token: String, val model: String, val expiresAt: String, val clientTools: Set<String> = emptySet())

class BackendHttpException(val code: Int, val detail: String = "") : IOException("Backend HTTP $code")

class BackendConnectionException(cause: IOException) : IOException("Can't reach your Mac", cause)
