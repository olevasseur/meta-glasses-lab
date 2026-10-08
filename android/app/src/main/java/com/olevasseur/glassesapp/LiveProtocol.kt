package com.olevasseur.glassesapp

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Wire JSON, independent of Android so protocol fixtures run on the JVM. */
object LiveProtocol {
    const val ENDPOINT = "wss://generativelanguage.googleapis.com/ws/" +
        "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"

    fun setup(model: String, handle: String? = null): String {
        val resumption = JSONObject()
        if (handle != null) resumption.put("handle", handle)
        val setup = JSONObject().put("model", "models/${model.removePrefix("models/")}")
            .put("sessionResumption", resumption)
        // Voice, VAD, transcription, compression and tools are locked by the backend token.
        return JSONObject().put("setup", setup).toString()
    }

    fun audio(pcm: ByteArray): String = JSONObject().put("realtimeInput", JSONObject().put(
        "audio", JSONObject().put("mimeType", "audio/pcm;rate=16000")
            .put("data", Base64.getEncoder().encodeToString(pcm)),
    )).toString()

    fun activity(start: Boolean): String = JSONObject().put("realtimeInput",
        JSONObject().put(if (start) "activityStart" else "activityEnd", JSONObject()),
    ).toString()

    fun image(jpeg: ByteArray): String = JSONObject().put("realtimeInput", JSONObject().put(
        "video", JSONObject().put("mimeType", "image/jpeg")
            .put("data", Base64.getEncoder().encodeToString(jpeg)),
    )).toString()

    fun toolResponse(call: FunctionCall, response: JSONObject, jpeg: ByteArray? = null): String = JSONObject().put(
        "toolResponse", JSONObject().put("functionResponses", JSONArray().put(
            JSONObject().put("id", call.id).put("name", call.name).put("response", response).apply {
                if (jpeg != null) put("parts", JSONArray().put(JSONObject().put("inlineData",
                    JSONObject().put("mimeType", "image/jpeg").put("data", Base64.getEncoder().encodeToString(jpeg)))))
            },
        )),
    ).toString()

    fun parse(text: String): ServerEvent {
        val root = JSONObject(text)
        val content = root.optJSONObject("serverContent")
        val parts = content?.optJSONObject("modelTurn")?.optJSONArray("parts")
        val audio = buildList {
            for (i in 0 until (parts?.length() ?: 0)) {
                val blob = parts!!.getJSONObject(i).optJSONObject("inlineData") ?: continue
                if (blob.optString("mimeType").startsWith("audio/pcm")) {
                    add(Base64.getDecoder().decode(blob.getString("data")))
                }
            }
        }
        val calls = root.optJSONObject("toolCall")?.optJSONArray("functionCalls")
        val cancelled = root.optJSONObject("toolCallCancellation")?.optJSONArray("ids")
        val resumption = root.optJSONObject("sessionResumptionUpdate")
        return ServerEvent(
            setupComplete = root.has("setupComplete"),
            audio = audio,
            interrupted = content?.optBoolean("interrupted") == true,
            turnComplete = content?.optBoolean("turnComplete") == true,
            inputText = content?.optJSONObject("inputTranscription")?.optString("text"),
            outputText = content?.optJSONObject("outputTranscription")?.optString("text"),
            calls = List(calls?.length() ?: 0) { index ->
                val call = calls!!.getJSONObject(index)
                FunctionCall(call.getString("id"), call.getString("name"),
                    call.optJSONObject("args") ?: JSONObject())
            },
            cancelledIds = List(cancelled?.length() ?: 0) { cancelled!!.getString(it) },
            goAway = root.has("goAway"),
            resumable = resumption?.optBoolean("resumable"),
            handle = resumption?.optString("newHandle")?.takeIf { it.isNotBlank() },
            error = root.optJSONObject("error")?.optString("message"),
        )
    }
}

data class FunctionCall(val id: String, val name: String, val args: JSONObject)
data class ServerEvent(
    val setupComplete: Boolean = false,
    val audio: List<ByteArray> = emptyList(),
    val interrupted: Boolean = false,
    val turnComplete: Boolean = false,
    val inputText: String? = null,
    val outputText: String? = null,
    val calls: List<FunctionCall> = emptyList(),
    val cancelledIds: List<String> = emptyList(),
    val goAway: Boolean = false,
    val resumable: Boolean? = null,
    val handle: String? = null,
    val error: String? = null,
)
