package com.olevasseur.glassesapp

import org.json.JSONObject

val COACH_TOOL_NAMES = setOf("check_progress", "set_timer", "list_timers", "cancel_timer", "set_nod_navigation")

/** Reuses look's image-bound response and camera lifecycle; never sends images to the backend. */
suspend fun coachTool(
    call: FunctionCall,
    backend: suspend (FunctionCall) -> JSONObject,
    vision: suspend (FunctionCall) -> ToolResult,
    timer: (FunctionCall) -> ToolResult,
): ToolResult = when (call.name) {
    "check_progress" -> {
        val question = call.args.opt("question") as? String
        if (question.isNullOrBlank()) ToolResult(toolError("check_progress requires a question"))
        else {
            val project = backend(FunctionCall(call.id, "current_step", JSONObject()))
            if (project.optString("status") !in setOf("ok", "complete")) ToolResult(project)
            else {
                val frame = vision(FunctionCall(call.id, "look", JSONObject().put("question", question)))
                if (frame.jpeg == null) frame else ToolResult(frame.response.put("project", project).put("question", question), frame.jpeg)
            }
        }
    }
    "set_nod_navigation" -> {
        val on = call.args.opt("on") as? Boolean
        when (on) {
            false -> ToolResult(JSONObject().put("status", "ok").put("on", false))
            true -> ToolResult(toolError("Nod navigation is unavailable: DAT 1.0.0 has no head-motion or nod events. Say next step instead."))
            null -> ToolResult(toolError("set_nod_navigation requires a boolean on"))
        }
    }
    else -> timer(call)
}
