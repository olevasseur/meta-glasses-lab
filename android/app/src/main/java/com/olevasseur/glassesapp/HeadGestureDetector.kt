package com.olevasseur.glassesapp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

enum class HeadGesture(val text: String) {
    NOD("[gesture: nod = yes]"), SHAKE("[gesture: shake = no]"),
}

/** Device monotonic time; angular rates in rad/s, acceleration magnitude in m/s². */
data class HeadMotionSample(
    val timestampNs: Long,
    val pitchRate: Double,
    val yawRate: Double,
    val rollRate: Double,
    val acceleration: Double,
)

/** Pure Kotlin, bounded state. Integrates short gyro excursions, so yaw wrap is irrelevant. */
class HeadGestureDetector(sensitivity: Float = 0.5f) {
    var sensitivity = sensitivity
        set(value) { require(value.isFinite() && value in 0f..1f); field = value; reset() }

    init { require(sensitivity.isFinite() && sensitivity in 0f..1f) }

    private var previousNs: Long? = null
    private var filtered = DoubleArray(3)
    private var quietSince: Long? = null
    private var lastQuiet = 0L
    private var cooldownUntil = 0L
    private var candidate: Candidate? = null

    private class Candidate(val axis: Int, val start: Long) {
        val angle = DoubleArray(3)
        val travel = DoubleArray(3)
        var minimum = 0.0
        var maximum = 0.0
        var direction = 0
        var reversals = 0
        var quietSince: Long? = null
        var accelerationEnergy = 0.0
        var elapsed = 0.0
    }

    fun reset() {
        previousNs = null; filtered.fill(0.0); quietSince = null
        candidate = null; cooldownUntil = 0; lastQuiet = 0
    }

    fun add(sample: HeadMotionSample): HeadGesture? {
        val rates = doubleArrayOf(sample.pitchRate, sample.yawRate, sample.rollRate)
        if (sample.timestampNs < 0 || rates.any { !it.isFinite() } ||
            !sample.acceleration.isFinite() || sample.acceleration <= 0) {
            reset(); return null
        }
        val previous = previousNs
        previousNs = sample.timestampNs
        if (previous == null) return null
        val delta = sample.timestampNs - previous
        if (delta <= 0 || delta > 120_000_000) {
            reset(); previousNs = sample.timestampNs; return null
        }
        val dt = delta / 1e9
        val time = sample.timestampNs / 1_000_000
        val accelerationError = abs(sample.acceleration - 9.80665)
        val old = filtered.copyOf()
        val blend = 1 - exp(-dt / 0.04)
        rates.indices.forEach { filtered[it] += blend * (Math.toDegrees(rates[it]) - filtered[it]) }
        val speed = filtered.maxOf { abs(it) }
        val quiet = speed < 8.0 && accelerationError < 0.65
        if (quiet) { if (quietSince == null) quietSince = time; lastQuiet = time } else {
            // Retain the pre-motion quiet interval until the candidate has been considered.
            if (candidate == null && time >= cooldownUntil && accelerationError < 1.0 &&
                quietSince?.let { lastQuiet - it >= 250 } == true && time - lastQuiet <= 150 && speed >= 20) {
                val axis = if (abs(filtered[0]) >= abs(filtered[1])) 0 else 1
                if (abs(filtered[axis]) > abs(filtered[2]) * 2) candidate = Candidate(axis, previous / 1_000_000)
            }
            // A slow onset can cross the quiet threshold before reaching 20 deg/s.
            if (speed >= 20 || accelerationError >= 0.65 || time - lastQuiet > 150) quietSince = null
        }
        val c = candidate ?: return null
        c.elapsed += dt
        c.accelerationEnergy += accelerationError * accelerationError * dt
        filtered.indices.forEach {
            val step = (old[it] + filtered[it]) * 0.5 * dt
            c.angle[it] += step
            c.travel[it] += abs(step)
        }
        c.minimum = minOf(c.minimum, c.angle[c.axis])
        c.maximum = max(c.maximum, c.angle[c.axis])
        val dominant = filtered[c.axis]
        if (abs(dominant) >= 15) {
            val sign = if (dominant > 0) 1 else -1
            if (c.direction != 0 && c.direction != sign) c.reversals++
            c.direction = sign
        }
        val otherTravel = c.travel.indices.filter { it != c.axis }.sumOf { c.travel[it] }
        if (time - c.start > 1_600 || accelerationError > 2.0 || speed > 300 ||
            c.maximum - c.minimum > 65 || c.reversals > 3 ||
            otherTravel > max(6.0, c.travel[c.axis] * 0.4)) {
            reject(time); return null
        }
        if (quiet) { if (c.quietSince == null) c.quietSince = time } else c.quietSince = null
        val end = c.quietSince ?: return null
        if (time - end < 160) return null
        val duration = end - c.start
        val threshold = 22.0 - 12.0 * sensitivity
        val excursion = max(abs(c.minimum), abs(c.maximum))
        val returned = abs(c.angle[c.axis]) <= max(3.0, excursion * 0.3)
        val deliberate = if (c.axis == 0) {
            excursion >= threshold && c.travel[0] >= threshold * 1.8 && c.reversals in 1..3
        } else {
            // Both sides of neutral distinguish a shake from looking aside and back.
            c.minimum <= -threshold * 0.5 && c.maximum >= threshold * 0.5 &&
                c.travel[1] >= threshold * 2.5 && c.reversals in 2..3
        }
        val gesture = if (duration in 280..1_400 && returned && deliberate &&
            sqrt(c.accelerationEnergy / c.elapsed) < 0.85) {
            if (c.axis == 0) HeadGesture.NOD else HeadGesture.SHAKE
        } else null
        reject(time, if (gesture != null) 1_200 else 250)
        return gesture
    }

    private fun reject(time: Long, cooldownMs: Long = 250) {
        candidate = null; quietSince = null; cooldownUntil = time + cooldownMs
    }
}
