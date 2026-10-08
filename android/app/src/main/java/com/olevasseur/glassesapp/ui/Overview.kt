package com.olevasseur.glassesapp.ui

import org.json.JSONObject

data class ReviewItem(val title: String, val detail: String)
data class Overview(
    val facts: List<ReviewItem> = emptyList(), val notes: List<ReviewItem> = emptyList(),
    val jobs: List<ReviewItem> = emptyList(), val meetings: List<ReviewItem> = emptyList(),
    val project: String? = null,
) {
    companion object {
        fun parse(json: JSONObject): Overview {
            fun items(key: String, title: (JSONObject) -> String, detail: (JSONObject) -> String): List<ReviewItem> {
                val array = json.optJSONArray(key) ?: return emptyList()
                return List(minOf(5, array.length())) { i ->
                    val row = array.getJSONObject(i)
                    ReviewItem(title(row), detail(row))
                }
            }
            return Overview(
                items("facts", { it.optString("text") }, { it.optString("updated_at") }),
                items("notes", { it.optString("title") }, { it.optString("text") }),
                items("jobs", { "${it.optString("kind")} · ${it.optString("status")}" }, { it.optString("summary") }),
                items("meetings", { it.optString("title").ifBlank { "Untitled meeting" } }, { it.optString("phase") }),
                json.optJSONObject("project")?.let {
                    "${it.getString("title")} · step ${it.getInt("step_number")}/${it.getInt("step_count")}\n${it.getString("instruction")}"
                },
            )
        }
    }
}
