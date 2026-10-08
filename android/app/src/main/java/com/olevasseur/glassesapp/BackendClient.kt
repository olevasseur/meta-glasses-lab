package com.olevasseur.glassesapp

import java.io.IOException
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
) {
    private val base = baseUrl.trimEnd('/').toHttpUrl()

    suspend fun token(ptt: Boolean): LiveToken {
        val json = post(listOf("token"), if (ptt) JSONObject().put("mode", "ptt") else JSONObject())
        return LiveToken(json.getString("token"), json.getString("model"), json.getString("expires_at"))
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

    private suspend fun post(path: List<String>, body: JSONObject): JSONObject {
        val url = base.newBuilder().apply { path.forEach { addPathSegment(it) } }.build()
        val request = Request.Builder().url(url).header("X-App-Secret", secret)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("Backend unavailable", e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!it.isSuccessful) throw IOException("Backend HTTP ${it.code}")
                            val result = JSONObject(it.body?.string() ?: error("Empty backend response"))
                            if (continuation.isActive) continuation.resume(result)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }
    }
}

data class LiveToken(val token: String, val model: String, val expiresAt: String)
