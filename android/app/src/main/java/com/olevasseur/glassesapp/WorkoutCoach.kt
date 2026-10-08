package com.olevasseur.glassesapp

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class WorkoutState(
    val active: Boolean = false,
    val needsMotion: Boolean = false,
    val summary: String = "Say ‘start 12 squats for 3 sets’ or ‘30 on 15 off for 8 rounds’.",
    val motion: String = "",
    val sensitivity: Float = 0.5f,
)

/** Service-owned coordinator; the existing HeadGestures owner supplies the single DAT stream. */
class WorkoutCoach(context: Context, private val scope: CoroutineScope, backend: BackendClient, onError: (String) -> Unit) {
    private val mutableState = MutableStateFlow(WorkoutState())
    val state = mutableState.asStateFlow()
    private val logs = WorkoutLogStore(File(context.noBackupFilesDir, "workout-outbox"),
        { backend.workout(it) }, onError)
    private var engine: WorkoutEngine? = null
    private var counter: WorkoutRepCounter? = null
    private var id = ""
    private var startedAt = ""
    private var lastSavedAt = 0L
    private var lastSampleAt: Long? = null
    private var send: (String) -> Boolean = { false }

    init {
        scope.launch {
            while (isActive) { logs.flush(); delay(5_000) }
        }
        scope.launch {
            while (isActive) {
                delay(100)
                val now = SystemClock.elapsedRealtime()
                val current = engine ?: continue
                if (!current.active) continue
                advance(now)
                if (lastSampleAt?.let { now - it > 1_000 } == true) motionUnavailable()
                if (now - lastSavedAt >= 5_000) snapshot(now)
                publish(now)
            }
        }
    }

    companion object { val TOOL_NAMES = setOf("start_workout", "stop_workout", "workout_status") }

    fun bind(sendText: (String) -> Boolean) { send = sendText }

    fun setSensitivity(value: Float) {
        if (!value.isFinite()) return
        val sensitivity = value.coerceIn(0f, 1f)
        mutableState.value = state.value.copy(sensitivity = sensitivity)
        engine?.let { counter = if (it.config.isTimed) null else WorkoutRepCounter(it.config.exercise, sensitivity) }
    }

    fun localTool(call: FunctionCall): JSONObject? {
        if (call.name !in TOOL_NAMES) return null
        return try {
            val now = SystemClock.elapsedRealtime()
            when (call.name) {
                "start_workout" -> {
                    val config = workoutConfig(call.args)
                    if (engine?.active == true) return JSONObject().put("status", "already_active")
                        .put("message", "Stop the current workout before starting another.")
                    engine = WorkoutEngine(config, now)
                    counter = if (config.isTimed) null else WorkoutRepCounter(config.exercise, state.value.sensitivity)
                    id = UUID.randomUUID().toString()
                    startedAt = Instant.now().toString()
                    lastSampleAt = null
                    snapshot(now); publish(now)
                }
                "stop_workout" -> { require(call.args.length() == 0); stop() }
                else -> { require(call.args.length() == 0); advance(now); publish(now) }
            }
            status(now)
        } catch (_: IllegalArgumentException) {
            JSONObject().put("status", "invalid_arguments").put("message", "Use a supported exercise and valid positive reps/sets/duration; timed workouts cannot have target_reps. Multiple rep sets need a target.")
        }
    }

    fun onSample(sample: HeadMotionSample) {
        val now = SystemClock.elapsedRealtime()
        lastSampleAt = now
        val current = engine ?: return
        if (!current.active) return
        advance(now)
        if (current.needsMotion && counter?.add(sample) == true) {
            val phase = current.phase
            val set = current.completedSets
            current.addRep(now).forEach { send(it) }
            if (phase != current.phase || set != current.completedSets) counter?.reset()
            snapshot(now)
        }
        publish(now)
    }

    fun motionUnavailable() {
        lastSampleAt = null
        counter?.reset()
        publish(SystemClock.elapsedRealtime())
    }

    fun stop(interrupted: Boolean = false) {
        val current = engine ?: return
        if (!current.active) return
        val now = SystemClock.elapsedRealtime()
        current.stop(now)
        counter?.reset()
        snapshot(now, if (current.phase == "completed") "completed" else if (interrupted) "interrupted" else "stopped")
        publish(now)
        scope.launch { logs.flush() }
    }

    suspend fun flush() = logs.flush()

    private fun advance(now: Long) {
        val current = engine ?: return
        if (!current.active) return
        val phase = current.phase
        val set = current.completedSets
        current.tick(now).forEach { send(it) }
        if (phase != current.phase || set != current.completedSets) {
            counter?.reset()
            snapshot(now)
        }
    }

    private fun snapshot(now: Long, outcome: String? = null) {
        val current = engine ?: return
        val config = current.config
        val log = JSONObject().put("workout_id", id).put("exercise", config.exercise)
            .put("started_at", startedAt).put("ended_at", Instant.now().toString())
            .put("reps_per_set", JSONArray(current.repsPerSet)).put("completed_sets", current.completedSets)
            .put("planned_sets", config.sets).put("target_reps", config.targetReps ?: JSONObject.NULL)
            .put("work_seconds", if (config.isTimed) config.effectiveWorkSeconds else JSONObject.NULL)
            .put("rest_seconds", config.restSeconds).put("active_seconds", current.activeSeconds(now))
            .put("status", outcome ?: if (current.phase == "completed") "completed" else "interrupted")
        logs.save(log, !current.active)
        lastSavedAt = now
    }

    private fun status(now: Long): JSONObject {
        val current = engine ?: return JSONObject().put("status", "idle")
        return JSONObject().put("status", current.phase).put("exercise", current.config.exercise)
            .put("reps_per_set", JSONArray(current.repsPerSet)).put("completed_sets", current.completedSets)
            .put("sets", current.config.sets).put("target_reps", current.config.targetReps ?: JSONObject.NULL)
            .put("remaining_seconds", current.remainingSeconds(now) ?: JSONObject.NULL)
            .put("motion", state.value.motion).put("active_seconds", current.activeSeconds(now))
    }

    private fun publish(now: Long) {
        val current = engine ?: return
        val remaining = current.remainingSeconds(now)?.let { " · ${it}s" } ?: ""
        mutableState.value = state.value.copy(active = current.active,
            // Keep the capability attached through rest; only the engine accepts working reps.
            needsMotion = current.active && !current.config.isTimed,
            summary = "${current.config.exercise.replace('_', ' ')} · ${current.phase} · " +
                "${current.repsPerSet.sum()} reps · ${current.completedSets}/${current.config.sets} sets$remaining",
            motion = if (current.config.isTimed || !current.active) "" else if (lastSampleAt == null)
                "Waiting for glasses IMU; reps cannot count until motion is live" else "IMU live")
    }
}

/** Validate model arguments locally too; tools arrive directly from Gemini, not our HTTP route. */
internal fun workoutConfig(args: JSONObject): WorkoutConfig {
    require(args.keys().asSequence().all { it in setOf("exercise", "target_reps", "sets", "work_seconds", "rest_seconds") })
    fun integer(name: String, default: Int? = null): Int? {
        if (!args.has(name) || args.isNull(name)) return default
        val value = args.get(name)
        require(value is Int || value is Long)
        val number = (value as Number).toLong()
        require(number in Int.MIN_VALUE..Int.MAX_VALUE)
        return number.toInt()
    }
    val exercise = args.opt("exercise")
    require(exercise is String)
    return WorkoutConfig(exercise, integer("target_reps"), integer("sets", 1)!!,
        integer("work_seconds"), integer("rest_seconds", 30)!!)
}
