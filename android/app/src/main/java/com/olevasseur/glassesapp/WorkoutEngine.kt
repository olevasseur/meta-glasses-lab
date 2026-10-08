package com.olevasseur.glassesapp

data class WorkoutConfig(
    val exercise: String,
    val targetReps: Int? = null,
    val sets: Int = 1,
    val workSeconds: Int? = null,
    val restSeconds: Int = 30,
) {
    val isTimed: Boolean get() = exercise == "plank" || exercise == "intervals"
    val effectiveWorkSeconds: Int? get() = if (isTimed) workSeconds ?: 30 else null

    init {
        require(exercise in setOf("squats", "push_ups", "sit_ups", "crunches", "lunges", "jumping_jacks", "burpees", "generic", "plank", "intervals")) { "Unsupported exercise" }
        require(sets in 1..100) { "sets must be 1–100" }
        require(targetReps == null || targetReps in 1..1_000) { "target_reps must be 1–1000" }
        require(workSeconds == null || workSeconds in 1..3_600) { "work_seconds must be 1–3600" }
        require(restSeconds in 0..3_600) { "rest_seconds must be 0–3600" }
        require(!isTimed || targetReps == null) { "Timed workouts cannot have target_reps" }
        require(isTimed || workSeconds == null) { "work_seconds requires plank or intervals" }
        require(isTimed || targetReps != null || sets == 1) { "Multiple rep sets require target_reps" }
    }
}

/** Pure Kotlin clock-driven workout. Call tick before reading a timer's status. */
class WorkoutEngine(val config: WorkoutConfig, startMs: Long) {
    init { require(startMs >= 0) }

    var phase: String = "work"
        private set
    var completedSets: Int = 0
        private set
    private val reps = mutableListOf(0)
    val repsPerSet: List<Int> get() = reps.toList()
    val active: Boolean get() = phase == "work" || phase == "rest"
    val needsMotion: Boolean get() = phase == "work" && !config.isTimed

    private var phaseStartedMs = startMs
    private var lastMs = startMs
    private var accruedWorkMs = 0L
    private var deadlineMs: Long? = config.effectiveWorkSeconds?.let { startMs + it * 1_000L }
    private var countdownSent = false

    fun activeSeconds(nowMs: Long): Long {
        val elapsed = if (phase == "work") {
            (minOf(maxOf(nowMs, lastMs), deadlineMs ?: Long.MAX_VALUE) - phaseStartedMs).coerceAtLeast(0)
        } else 0
        return (accruedWorkMs + elapsed) / 1_000
    }

    fun remainingSeconds(nowMs: Long): Int? = deadlineMs?.let {
        ((it - maxOf(nowMs, lastMs)).coerceAtLeast(0).plus(999) / 1_000).toInt()
    }

    fun addRep(nowMs: Long): List<String> {
        if (nowMs < lastMs || !active) return emptyList()
        val transitions = tick(nowMs)
        if (!needsMotion) return transitions
        // A sample that caused rest to expire belongs to the old phase, not the new set.
        if (transitions.isNotEmpty()) return transitions
        val count = reps.last() + 1
        reps[reps.lastIndex] = count
        val target = config.targetReps
        val rep = "rep $count${target?.let { "/$it" }.orEmpty()} ${config.exercise}"
        if (count == target) return listOf("[$rep; ${completeSet(nowMs)}]")
        return if (count % 5 == 0 || (target != null && count >= target - 2)) listOf("[$rep]") else emptyList()
    }

    /** Late updates advance the schedule, but emit only the current transition, never a burst. */
    fun tick(nowMs: Long): List<String> {
        if (nowMs < lastMs || !active) return emptyList()
        lastMs = nowMs
        var latest: String? = null
        while (active && deadlineMs?.let { nowMs >= it } == true) {
            val boundary = deadlineMs!!
            latest = if (phase == "work") completeSet(boundary) else startSet(boundary)
        }
        if (latest != null) return listOf("[$latest]")
        val remainingMs = deadlineMs?.minus(nowMs)
        // Skip an already missed countdown instead of speaking "3" with one second left.
        if (!countdownSent && remainingMs != null && remainingMs in 2_001..3_000) {
            countdownSent = true
            return listOf("[$phase countdown: 3, 2, 1]")
        }
        return emptyList()
    }

    fun stop(nowMs: Long) {
        if (!active) return
        val time = maxOf(lastMs, nowMs)
        tick(time)
        if (!active) return
        if (phase == "work") accruedWorkMs += time - phaseStartedMs
        lastMs = time
        phase = "stopped"
        deadlineMs = null
    }

    private fun completeSet(atMs: Long): String {
        accruedWorkMs += atMs - phaseStartedMs
        completedSets++
        if (completedSets == config.sets) {
            phase = "completed"
            deadlineMs = null
            return "workout complete; $completedSets/${config.sets} sets ${config.exercise}"
        }
        if (config.restSeconds == 0) return "set $completedSets/${config.sets} complete; ${startSet(atMs)}"
        phase = "rest"
        phaseStartedMs = atMs
        deadlineMs = atMs + config.restSeconds * 1_000L
        countdownSent = false
        return "set $completedSets/${config.sets} complete; rest ${config.restSeconds} seconds"
    }

    private fun startSet(atMs: Long): String {
        phase = "work"
        phaseStartedMs = atMs
        deadlineMs = config.effectiveWorkSeconds?.let { atMs + it * 1_000L }
        countdownSent = false
        reps.add(0)
        return "set ${completedSets + 1}/${config.sets}; ${config.exercise}; start${config.effectiveWorkSeconds?.let { " $it seconds" }.orEmpty()}"
    }
}
