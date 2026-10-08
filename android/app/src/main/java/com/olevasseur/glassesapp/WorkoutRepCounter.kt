package com.olevasseur.glassesapp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/**
 * Pure Kotlin workout detector for the existing glasses IMU stream. Call only during an active set.
 * A rep needs two opposite lobes and a return through neutral; noise cannot count isolated peaks.
 * ponytail: heuristic head-motion profiles, not full-body recognition; calibrate on recorded hardware traces.
 */
class WorkoutRepCounter(exercise: String, sensitivity: Float = 0.5f) {
    private data class Profile(
        val pitch: Boolean = false,
        val threshold: Double,
        val minimumSeconds: Double,
        val minimumPitch: Double = 0.0,
        val cyclesPerRep: Int = 1,
    )

    private val profile = when (exercise.lowercase().replace('-', '_').replace(' ', '_')) {
        "squat", "squats" -> Profile(threshold = 0.55, minimumSeconds = 0.8)
        "pushup", "pushups", "push_up", "push_ups" -> Profile(threshold = 0.45, minimumSeconds = 0.8)
        "situp", "situps", "sit_up", "sit_ups" -> Profile(true, 12.0, 0.8, 22.0)
        "crunch", "crunches" -> Profile(true, 10.0, 0.7, 12.0)
        "lunge", "lunges" -> Profile(threshold = 0.5, minimumSeconds = 0.85)
        "jumping_jack", "jumping_jacks" -> Profile(threshold = 2.4, minimumSeconds = 0.28, cyclesPerRep = 2)
        "burpee", "burpees" -> Profile(true, 15.0, 1.6, 45.0)
        "generic", "anything", "reps" -> Profile(threshold = 0.55, minimumSeconds = 0.8)
        else -> throw IllegalArgumentException("Unsupported rep exercise: $exercise")
    }
    private val floor: Double
    private var previousNs: Long? = null
    private var filtered = 0.0
    private var typicalPeak = 0.0
    private var candidate: Cycle? = null
    private var cycles = 0

    private class Cycle(val start: Long, val sign: Double, val threshold: Double) {
        var opposite = false
        var peak = 0.0
        var pitch = 0.0
        var minPitch = 0.0
        var maxPitch = 0.0
        var pitchTravel = 0.0
        var otherTravel = 0.0
    }

    init {
        require(sensitivity.isFinite() && sensitivity in 0f..1f)
        floor = profile.threshold * (1.3 - 0.6 * sensitivity)
    }

    fun reset() {
        previousNs = null
        filtered = 0.0
        typicalPeak = 0.0
        candidate = null
        cycles = 0
    }

    fun add(sample: HeadMotionSample): Boolean {
        if (sample.timestampNs < 0 || !sample.acceleration.isFinite() || sample.acceleration !in 1.0..60.0 ||
            listOf(sample.pitchRate, sample.yawRate, sample.rollRate).any { !it.isFinite() || abs(it) > 14.0 }) {
            reset()
            return false
        }
        val previous = previousNs
        previousNs = sample.timestampNs
        if (previous == null) return false
        val delta = sample.timestampNs - previous
        if (delta <= 0 || delta > 150_000_000) {
            reset()
            previousNs = sample.timestampNs
            return false
        }
        val dt = delta / 1e9
        val pitchRate = Math.toDegrees(sample.pitchRate)
        // |a|-g is a posture-independent approximation of vertical acceleration when vertical motion dominates.
        val raw = if (profile.pitch) pitchRate else sample.acceleration - 9.80665
        filtered += (1 - exp(-dt / 0.06)) * (raw - filtered)
        val threshold = max(floor, typicalPeak * 0.3)
        if (candidate == null && abs(filtered) >= threshold) {
            candidate = Cycle(sample.timestampNs, if (filtered > 0) 1.0 else -1.0, threshold)
        }
        val c = candidate ?: return false
        c.peak = max(c.peak, abs(filtered))
        c.pitch += pitchRate * dt
        c.minPitch = minOf(c.minPitch, c.pitch)
        c.maxPitch = max(c.maxPitch, c.pitch)
        c.pitchTravel += abs(pitchRate) * dt
        c.otherTravel += (abs(Math.toDegrees(sample.yawRate)) + abs(Math.toDegrees(sample.rollRate))) * dt
        val seconds = (sample.timestampNs - c.start) / 1e9
        if (seconds > 8.0 || c.otherTravel > max(12.0, c.pitchTravel * 0.7)) {
            candidate = null
            cycles = 0
            return false
        }
        val signal = filtered * c.sign
        if (signal <= -c.threshold) c.opposite = true
        if (!c.opposite || signal < -c.threshold * 0.15) return false
        candidate = null
        if (seconds < profile.minimumSeconds || c.maxPitch - c.minPitch < profile.minimumPitch) {
            cycles = 0
            return false
        }
        typicalPeak = if (typicalPeak == 0.0) c.peak else typicalPeak * 0.75 + c.peak * 0.25
        cycles++
        if (cycles < profile.cyclesPerRep) return false
        cycles = 0
        return true
    }
}
