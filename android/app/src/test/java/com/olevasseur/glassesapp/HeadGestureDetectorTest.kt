package com.olevasseur.glassesapp

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class HeadGestureDetectorTest {
    private class Signal(val detector: HeadGestureDetector = HeadGestureDetector(), val hz: Int = 60) {
        var ns = 0L
        val results = mutableListOf<HeadGesture>()
        fun sample(pitch: Double = 0.0, yaw: Double = 0.0, roll: Double = 0.0, acceleration: Double = 9.80665) {
            detector.add(HeadMotionSample(ns, Math.toRadians(pitch), Math.toRadians(yaw), Math.toRadians(roll), acceleration))?.let(results::add)
            ns += 1_000_000_000 / hz
        }
        fun still(seconds: Double = 0.5) { repeat((seconds * hz).toInt()) { sample() } }
        fun gesture(axis: Int = 0, amplitude: Double = 25.0, duration: Double = 0.8,
                    shake: Boolean = axis == 1, direction: Double = 1.0,
                    crossAxis: Double = 0.0, walking: Double = 0.0) {
            repeat((duration * hz).toInt() + 1) { i ->
                val t = i.toDouble() / hz
                val u = t / duration
                val speed = direction * if (shake) {
                    amplitude * PI / duration * (2 * cos(2 * PI * u) * sin(PI * u) + sin(2 * PI * u) * cos(PI * u))
                } else amplitude * PI / duration * sin(2 * PI * u)
                sample(if (axis == 0) speed else speed * crossAxis,
                    if (axis == 1) speed else speed * crossAxis,
                    if (axis == 2) speed else 0.0,
                    9.80665 + walking * sin(4 * PI * t))
            }
            still(0.4)
        }
    }

    @Test fun nodsAndShakesInBothDirectionsAt30And60Hz() {
        for (hz in listOf(30, 60)) for (axis in 0..1) for (sign in listOf(-1.0, 1.0)) {
            val s = Signal(hz = hz)
            s.still(); s.gesture(axis, direction = sign)
            assertEquals("hz=$hz axis=$axis sign=$sign", listOf(if (axis == 0) HeadGesture.NOD else HeadGesture.SHAKE), s.results)
        }
    }

    @Test fun quietNoiseAndBiasDoNotTrigger() {
        val s = Signal()
        repeat(600) { s.sample(1 + sin(it.toDouble()) * 3, cos(it.toDouble()) * 2, 1.0) }
        assertTrue(s.results.isEmpty())
    }

    @Test fun walkingIncludingLargePeriodicHeadBobbingIsRejected() {
        val s = Signal()
        s.still()
        repeat(4) { s.gesture(walking = 2.6) }
        assertTrue(s.results.isEmpty())
        val mild = Signal()
        mild.still(); mild.gesture(walking = 1.5, duration = 1.0)
        assertTrue(mild.results.isEmpty())
    }

    @Test fun turningLookingAsideRollAndMixedAxesAreRejected() {
        val turn = Signal()
        turn.still(); repeat(60) { turn.sample(yaw = 45.0) }; turn.still()
        assertTrue(turn.results.isEmpty())
        val glance = Signal()
        glance.still(); glance.gesture(axis = 1, shake = false)
        assertTrue(glance.results.isEmpty())
        val roll = Signal()
        roll.still(); roll.gesture(axis = 2, shake = false)
        assertTrue(roll.results.isEmpty())
        val mixed = Signal()
        mixed.still(); mixed.gesture(crossAxis = 0.7)
        assertTrue(mixed.results.isEmpty())
    }

    @Test fun tinyFastSlowAndOversizedMovementsAreRejected() {
        for ((amplitude, duration) in listOf(5.0 to 0.8, 25.0 to 0.15, 25.0 to 2.5, 100.0 to 1.0)) {
            val s = Signal()
            s.still(); s.gesture(amplitude = amplitude, duration = duration)
            assertTrue("amplitude=$amplitude duration=$duration", s.results.isEmpty())
        }
    }

    @Test fun sensitivityChangesMinimumExcursion() {
        val low = Signal(HeadGestureDetector(0f))
        low.still(); low.gesture(amplitude = 16.0)
        assertTrue(low.results.isEmpty())
        val high = Signal(HeadGestureDetector(1f))
        high.still(); high.gesture(amplitude = 16.0)
        assertEquals(listOf(HeadGesture.NOD), high.results)
    }

    @Test fun cooldownSuppressesRepeatAndThenRearms() {
        val s = Signal()
        s.still(); s.gesture(); s.gesture()
        assertEquals(1, s.results.size)
        s.still(1.5); s.gesture(axis = 1)
        assertEquals(listOf(HeadGesture.NOD, HeadGesture.SHAKE), s.results)
    }

    @Test fun requiresStillnessBeforeFirstGesture() {
        val s = Signal()
        s.gesture()
        assertTrue(s.results.isEmpty())
        s.still(1.0); s.gesture()
        assertEquals(listOf(HeadGesture.NOD), s.results)
    }

    @Test fun timestampGapsDuplicatesAndBackwardsTimeCannotCompleteOldGesture() {
        for (jump in listOf(-100_000_000L, 0L, 500_000_000L)) {
            val s = Signal()
            s.still(); repeat(20) { s.sample(pitch = 70.0) }
            s.ns += jump - 1_000_000_000 / s.hz
            s.sample(pitch = -70.0)
            repeat(20) { s.sample(pitch = -70.0) }; s.still()
            assertTrue("jump=$jump", s.results.isEmpty())
            s.still(1.0); s.gesture()
            assertEquals(listOf(HeadGesture.NOD), s.results)
        }
    }

    @Test fun invalidSamplesAndResetDiscardPartialGesture() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val s = Signal()
            s.still(); repeat(20) { s.sample(pitch = 70.0) }; s.sample(pitch = bad)
            repeat(20) { s.sample(pitch = -70.0) }; s.still()
            assertTrue(s.results.isEmpty())
            s.detector.reset(); s.still(); s.gesture()
            assertEquals(listOf(HeadGesture.NOD), s.results)
        }
    }

    @Test fun randomJitteredSamplingDoesNotDependOnSampleCount() {
        val s = Signal()
        s.still()
        val start = s.ns
        val random = java.util.Random(42)
        while (s.ns - start < 800_000_000) {
            val t = (s.ns - start) / 1e9
            s.sample(pitch = 25 * PI / 0.8 * sin(2 * PI * t / 0.8) + random.nextGaussian())
            s.ns += random.nextInt(8_000_000) - 4_000_000
        }
        s.still()
        assertEquals(listOf(HeadGesture.NOD), s.results)
    }
}
