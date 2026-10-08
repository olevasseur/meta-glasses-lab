package com.olevasseur.glassesapp

import org.json.JSONArray
import org.json.JSONObject

/** One explicit question, one answer, no queued/replayed gestures. Clock supplied by caller. */
class GestureConfirmation {
    companion object { const val TOOL = "await_gesture_confirmation" }

    var question: String? = null
        private set
    private var callId: String? = null
    private var deadline = 0L
    private var waiting = false

    fun request(call: FunctionCall, available: Boolean, nowMs: Long): JSONObject? {
        if (call.name != TOOL) return null
        clear()
        val text = call.args.opt("question") as? String
        if (text.isNullOrBlank() || text.length > 500) return JSONObject().put("status", "error")
            .put("message", "Supply one explicit yes/no question, at most 500 characters.")
        if (!available) return JSONObject().put("status", "unavailable")
            .put("message", "Head gestures are off or IMU data is unavailable. Ask for a spoken answer.")
        question = text
        callId = call.id
        deadline = nowMs + 30_000
        return JSONObject().put("status", "ready").put("question", text).put("expires_in_seconds", 30)
            .put("message", "Ask this question aloud now, then wait. Nod means yes; shake means no.")
    }

    fun onEvent(event: ServerEvent) {
        if (event.interrupted || !event.inputText.isNullOrBlank() || callId in event.cancelledIds ||
            event.calls.any { it.name != TOOL } || (waiting && !event.outputText.isNullOrBlank())) {
            clear()
        }
        if (event.turnComplete && question != null) waiting = true
    }

    fun expire(nowMs: Long) { if (question != null && nowMs >= deadline) clear() }

    fun answer(gesture: HeadGesture, nowMs: Long, send: (String) -> Boolean): Boolean {
        expire(nowMs)
        if (!waiting || question == null) return false
        // Consume even on send failure: never let an old movement answer a later question.
        clear()
        return send(gesture.text)
    }

    fun clear() { question = null; callId = null; waiting = false; deadline = 0 }
}

/** A complete text turn also works when automatic activity detection is disabled (PTT). */
fun gestureTextTurn(text: String): String = JSONObject().put("clientContent", JSONObject()
    .put("turns", JSONArray().put(JSONObject().put("role", "user")
        .put("parts", JSONArray().put(JSONObject().put("text", text)))))
    .put("turnComplete", true)).toString()
