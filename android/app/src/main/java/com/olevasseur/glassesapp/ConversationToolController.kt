package com.olevasseur.glassesapp

import org.json.JSONObject

/** One policy for feature entry points, independent of model tool selection. */
data class FeatureAccess(val paused: Boolean, val meeting: Boolean, val translating: Boolean) {
    fun denial(call: FunctionCall): String? = when {
        paused -> "Conversation is paused"
        translating -> "Leave translation mode to use assistant tools"
        meeting && (call.name == "start_workout" || call.name == "read_text" || call.name == "submit_reading" ||
            (call.name == "reading_control" && call.args.opt("action") != "stop") ||
            (call.name in setOf("set_watch_mode", "set_nod_navigation", "set_visual_memory") && call.args.opt("on") != false) ||
            call.name == "remember_this") -> "Stop the meeting before starting this feature"
        else -> null
    }
}

class ConversationToolController(
    private val access: () -> FeatureAccess,
    private val visual: suspend (FunctionCall) -> ToolResult,
    private val coach: suspend (FunctionCall) -> ToolResult,
    private val reading: suspend (FunctionCall) -> ToolResult,
    private val workout: (FunctionCall) -> JSONObject?,
    private val gestures: (FunctionCall) -> JSONObject?,
    private val vision: suspend (FunctionCall) -> ToolResult,
) {
    suspend fun tool(call: FunctionCall): ToolResult {
        val current = access()
        current.denial(call)?.let { return ToolResult(toolError(it)) }
        return when {
            current.meeting && call.name == GestureConfirmation.TOOL -> ToolResult(JSONObject()
                .put("status", "unavailable").put("message", "Use spoken confirmation during meeting capture"))
            call.name in VisualMemoryController.TOOL_NAMES -> visual(call)
            call.name in COACH_TOOL_NAMES -> coach(call)
            call.name in ReadToMeController.TOOL_NAMES -> reading(call)
            else -> (workout(call) ?: gestures(call))?.let { ToolResult(it) } ?: vision(call)
        }
    }
}
