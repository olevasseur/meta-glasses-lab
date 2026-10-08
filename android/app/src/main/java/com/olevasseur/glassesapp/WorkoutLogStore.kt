package com.olevasseur.glassesapp

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Private, atomic outbox. An unfinished snapshot is recovered as interrupted on relaunch. */
class WorkoutLogStore(
    private val directory: File,
    private val upload: suspend (JSONObject) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val pending = linkedMapOf<String, JSONObject>()
    private val sending = Mutex()

    init {
        directory.mkdirs()
        directory.listFiles { file -> file.extension == "json" }?.forEach { file ->
            try {
                val item = JSONObject(file.readText())
                val log = item.getJSONObject("log")
                require(log.getString("workout_id") == file.nameWithoutExtension)
                if (!item.getBoolean("ready")) {
                    log.put("status", "interrupted")
                    item.put("ready", true)
                }
                pending[file.nameWithoutExtension] = item
                persist(file.nameWithoutExtension, item)
            } catch (_: Exception) { onError("A workout log could not be read; its file was kept.") }
        }
    }

    fun save(log: JSONObject, ready: Boolean) {
        val id = log.getString("workout_id")
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        val item = JSONObject().put("log", log).put("ready", ready)
        pending[id] = item
        try { persist(id, item) }
        catch (_: Exception) { onError("Workout log is in memory only; device storage write failed.") }
    }

    suspend fun flush() = sending.withLock {
        for ((id, item) in pending.toMap()) {
            if (!item.getBoolean("ready")) continue
            try {
                // Persist before POST so an unknown HTTP outcome can safely be replayed.
                persist(id, item)
                upload(item.getJSONObject("log"))
                check(File(directory, "$id.json").delete())
                pending.remove(id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { onError("Workout upload pending; retrying while the app is open or next launch.") }
        }
    }

    private fun persist(id: String, item: JSONObject) {
        val temporary = File(directory, "$id.tmp")
        temporary.writeText(item.toString())
        Files.move(temporary.toPath(), File(directory, "$id.json").toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
