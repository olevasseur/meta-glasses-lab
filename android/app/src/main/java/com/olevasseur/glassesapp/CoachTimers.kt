package com.olevasseur.glassesapp

import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import org.json.JSONArray
import org.json.JSONObject

data class CoachTimer(val id: String, val name: String, val deadline: Long, val elapsedDeadline: Long, val boot: Int) {
    fun remaining(now: Long, elapsed: Long, currentBoot: Int): Long =
        (if (boot == currentBoot) elapsedDeadline - elapsed else deadline - now).coerceAtLeast(0)
    fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("deadline", deadline)
        .put("elapsedDeadline", elapsedDeadline).put("boot", boot)
}

/** Platform-independent policy. The Android adapter persists after every change. */
class CoachTimers(saved: String = "[]") {
    private val timers = mutableListOf<CoachTimer>()
    init {
        val array = JSONArray(saved)
        repeat(array.length()) {
            val item = array.getJSONObject(it)
            timers += CoachTimer(item.getString("id"), item.getString("name"), item.getLong("deadline"),
                item.getLong("elapsedDeadline"), item.getInt("boot"))
        }
    }
    fun all(): List<CoachTimer> = timers.toList()
    fun rebase(now: Long, elapsed: Long, boot: Int) {
        val updated = timers.map {
            val remaining = it.remaining(now, elapsed, boot)
            it.copy(deadline = now + remaining, elapsedDeadline = elapsed + remaining, boot = boot)
        }
        timers.clear(); timers.addAll(updated)
    }
    fun serialized(): String = JSONArray(timers.map { it.json() }).toString()
    fun add(name: String, seconds: Double, now: Long, elapsed: Long, boot: Int): CoachTimer {
        require(name.trim().length in 1..60) { "Use a timer name of 1 to 60 characters" }
        require(seconds.isFinite() && seconds in 1.0..604800.0) { "Duration must be 1 to 604800 seconds" }
        require(timers.none { key(it.name) == key(name) }) { "That timer already exists; cancel it before replacing" }
        require(timers.size < 20) { "At most 20 timers can run at once" }
        val millis = ceil(seconds * 1000).toLong()
        return CoachTimer(UUID.randomUUID().toString(), name.trim(), Math.addExact(now, millis),
            Math.addExact(elapsed, millis), boot).also { timers += it }
    }
    fun cancel(name: String): CoachTimer? = timers.firstOrNull { key(it.name) == key(name) }?.also { timers.remove(it) }
    fun due(id: String, now: Long, elapsed: Long, boot: Int): CoachTimer? =
        timers.firstOrNull { it.id == id && it.remaining(now, elapsed, boot) == 0L }?.also { timers.remove(it) }
    private fun key(name: String) = name.trim().lowercase(Locale.ROOT)
}
