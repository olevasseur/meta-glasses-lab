package com.olevasseur.glassesapp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import java.util.Random

class WorkoutRepCounterTest {
    private val exercises = listOf("squats", "push_ups", "sit_ups", "crunches", "lunges", "jumping_jacks", "burpees", "generic")

    private class Signal(exercise: String, sensitivity: Float = 0.5f, val hz: Int = 30) {
        val counter = WorkoutRepCounter(exercise, sensitivity)
        var ns = 0L
        var count = 0
        val random = Random(42)
        fun sample(acceleration: Double = 0.0, pitch: Double = 0.0, yaw: Double = 0.0, roll: Double = 0.0) {
            if (counter.add(HeadMotionSample(ns, Math.toRadians(pitch), Math.toRadians(yaw), Math.toRadians(roll), 9.80665 + acceleration))) count++
            ns += 1_000_000_000 / hz
        }
        fun still(seconds: Double = 0.5) { repeat((seconds * hz).toInt()) { sample() } }
        fun cycles(number: Int, duration: Double = 2.0, acceleration: Double = 1.8,
                   pitch: Double = 55.0, noise: Double = 0.0, sign: Double = 1.0) {
            repeat((number * duration * hz).toInt()) { i ->
                val wave = sign * sin(2 * PI * i / hz / duration)
                sample(acceleration * wave + random.nextGaussian() * noise,
                    pitch * wave + random.nextGaussian() * noise * 3)
            }
            still()
        }
    }

    @Test fun allExerciseProfilesCountCompleteRepsWithNoiseAtBothSamplingRatesAndPolarities() {
        for (exercise in exercises) for (hz in listOf(30, 60)) for (sign in listOf(-1.0, 1.0)) {
            val s = Signal(exercise, hz = hz)
            s.still()
            val jacks = exercise == "jumping_jacks"
            s.cycles(if (jacks) 12 else 6, duration = if (jacks) 0.65 else if (exercise == "burpees") 3.0 else 2.0,
                acceleration = if (jacks) 3.5 else 1.8,
                pitch = if (exercise in listOf("sit_ups", "crunches", "burpees")) 55.0 else 0.0,
                noise = 0.08, sign = sign)
            assertEquals("$exercise at $hz Hz sign $sign", 6, s.count)
        }
    }

    @Test fun quietNoiseAndSlowBiasNeverCount() {
        for (exercise in exercises) {
            val s = Signal(exercise)
            repeat(1200) { s.sample(0.1 + s.random.nextGaussian() * 0.12, 1.5 + s.random.nextGaussian() * 2) }
            assertEquals(exercise, 0, s.count)
        }
    }

    @Test fun walkingCadenceAndHeadBobbingDoNotCountForAnyProfile() {
        for (exercise in exercises) {
            val s = Signal(exercise)
            s.still()
            // Walking: 2 Hz steps with substantial vertical acceleration and small pitch bob.
            s.cycles(30, duration = 0.5, acceleration = 2.6, pitch = 12.0, noise = 0.03)
            assertEquals(exercise, 0, s.count)
            val swaying = Signal(exercise)
            swaying.still()
            repeat(1200) { i ->
                val wave = sin(2 * PI * i / 30 / 0.65)
                swaying.sample(2.5 * wave, 14 * wave, 45 * wave, 30 * wave)
            }
            assertEquals("swaying $exercise", 0, swaying.count)
        }
    }

    @Test fun isolatedImpactAndOneWayMovementAreNotReps() {
        for (exercise in exercises) {
            val s = Signal(exercise)
            s.still()
            repeat(15) { s.sample(4.0, 50.0) }
            s.still(2.0)
            assertEquals(exercise, 0, s.count)
        }
    }

    @Test fun jumpingJacksRequireBothJumps() {
        val s = Signal("jumping_jacks")
        s.still(); s.cycles(1, 0.65, acceleration = 3.5)
        assertEquals(0, s.count)
        s.cycles(1, 0.65, acceleration = 3.5)
        assertEquals(1, s.count)
    }

    @Test fun pitchProfilesNeedRealExcursionRatherThanSmallRapidNods() {
        for (exercise in listOf("sit_ups", "crunches", "burpees")) {
            val s = Signal(exercise)
            s.still(); s.cycles(10, duration = 0.5, pitch = 45.0)
            assertEquals(exercise, 0, s.count)
        }
    }

    @Test fun adaptiveGateRejectsSmallOscillationsAfterLargeReps() {
        val s = Signal("generic")
        s.still(); s.cycles(4, acceleration = 3.0)
        assertEquals(4, s.count)
        s.cycles(4, acceleration = 0.7)
        assertEquals(4, s.count)
        s.cycles(4, acceleration = 2.0)
        assertEquals(8, s.count)
    }

    @Test fun sensitivityAllowsSmallerControlledMovement() {
        val low = Signal("squats", 0f)
        low.still(); low.cycles(4, acceleration = 0.6)
        assertEquals(0, low.count)
        val high = Signal("squats", 1f)
        high.still(); high.cycles(4, acceleration = 0.6)
        assertEquals(4, high.count)
    }

    @Test fun invalidSamplesAndTimestampDiscontinuitiesDiscardPartialCycles() {
        for (jump in listOf(-200_000_000L, -33_333_333L, 500_000_000L)) {
            val s = Signal("squats")
            s.still(); repeat(20) { s.sample(2.0) }
            s.ns += jump
            repeat(20) { s.sample(-2.0) }
            s.still()
            assertEquals("jump=$jump", 0, s.count)
            s.counter.reset(); s.still(); s.cycles(3)
            assertEquals(3, s.count)
        }
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, -20.0)) {
            val s = Signal("squats")
            s.still(); repeat(20) { s.sample(2.0) }; s.sample(bad)
            repeat(20) { s.sample(-2.0) }; s.still()
            assertEquals(0, s.count)
        }
    }

    @Test fun resetDropsHalfOfJumpingJackAndLearnedThreshold() {
        val s = Signal("jumping_jacks")
        s.still(); s.cycles(1, 0.65, acceleration = 3.5)
        s.counter.reset()
        s.still(); s.cycles(1, 0.65, acceleration = 3.5)
        assertEquals(0, s.count)
    }

    @Test fun rejectsUnsupportedExercisesAndInvalidSensitivity() {
        for (value in listOf(-0.1f, 1.1f, Float.NaN)) {
            assertThrows(IllegalArgumentException::class.java) { WorkoutRepCounter("squats", value) }
        }
        assertThrows(IllegalArgumentException::class.java) { WorkoutRepCounter("plank") }
    }
}
