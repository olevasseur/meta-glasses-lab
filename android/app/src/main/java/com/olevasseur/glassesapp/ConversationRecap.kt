package com.olevasseur.glassesapp

import org.json.JSONArray
import org.json.JSONObject

/** Session-local, bounded text only. History never grants approval or replays a tool. */
internal class ConversationRecap {
    private val turns = ArrayDeque<Pair<String, String>>()
    private val outcomes = linkedMapOf<String, JSONObject>()
    private var boundary = true

    fun text(role: String, text: String) {
        if (text.isBlank()) return
        if (!boundary && turns.lastOrNull()?.first == role) {
            val previous = turns.removeLast()
            turns.addLast(role to (previous.second + text).take(1200))
        } else turns.addLast(role to text.take(1200))
        boundary = false
        while (turns.size > 10) turns.removeFirst()
    }

    fun endTurn() { boundary = true }

    fun tool(call: FunctionCall, result: JSONObject) {
        val summary = JSONObject().put("tool", call.name)
        // Keep small outcome fields, never tool payloads, images or approval credentials.
        for (key in listOf("status", "alias", "doc_title", "removed", "message", "error")) {
            if (result.has(key)) summary.put(key, result.opt(key)?.toString().orEmpty().take(400))
        }
        outcomes[call.id] = summary
        while (outcomes.size > 20) outcomes.remove(outcomes.keys.first())
    }

    fun message(): String? {
        if (turns.isEmpty() && outcomes.isEmpty()) return null
        fun data() = JSONObject().put("recent_turns", JSONArray(turns.map {
            JSONObject().put("speaker", it.first).put("text", it.second)
        })).put("tool_outcomes", JSONArray(outcomes.values.toList())).toString()
        var json = data()
        // Bound the actual serialized request, including escaping of untrusted text.
        while (json.length > 24_000) {
            if (outcomes.isNotEmpty()) outcomes.remove(outcomes.keys.first()) else turns.removeFirst()
            json = data()
        }
        return json
    }

    fun clear() { turns.clear(); outcomes.clear(); boundary = true }
}
