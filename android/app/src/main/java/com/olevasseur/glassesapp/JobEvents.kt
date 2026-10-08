package com.olevasseur.glassesapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONObject

data class JobEvent(val id: Long, val jobId: String, val status: String, val summary: String)

/** Cursor advances only after delivery; reconnects replay anything still pending. */
class JobEvents(
    private val fetch: suspend (Long) -> JSONObject,
    initialCursor: Long,
    private val saveCursor: suspend (Long) -> Unit,
    private val deliver: (JobEvent) -> Boolean,
) {
    var cursor = initialCursor
        private set

    suspend fun receive(response: JSONObject): Boolean {
        val events = response.getJSONArray("events")
        for (i in 0 until events.length()) {
            val value = events.getJSONObject(i)
            val event = JobEvent(value.getLong("id"), value.getString("job_id"),
                value.getString("status"), value.getString("summary").take(1500))
            if (event.id <= cursor) continue
            if (!deliver(event)) return false
            saveCursor(event.id)
            cursor = event.id
        }
        return true
    }

    suspend fun listen() {
        while (currentCoroutineContext().isActive) {
            try {
                if (!receive(fetch(cursor))) delay(5_000)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { delay(5_000) }
        }
    }
}
