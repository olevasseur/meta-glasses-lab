package com.olevasseur.glassesapp

import com.meta.wearable.dat.motion.types.MotionSample
import com.meta.wearable.dat.motion.types.MotionSource
import com.meta.wearable.dat.motion.types.Vector3
import org.junit.Assert.*
import org.junit.Test

class GlassesMotionTest {
    private fun sample() = MotionSample(
        timestampNs = 123_000_000L,
        accelerometer = Vector3(0f, 9.80665f, 0f),
        gyroscope = Vector3(0.1f, 0.2f, 0.3f),
        source = MotionSource.GLASSES,
    )

    @Test fun realDatSampleMapsBodyAxesAndUnitsWithoutOrientation() {
        val value = sample().toHeadMotionSample()!!
        assertEquals(123_000_000L, value.timestampNs)
        assertEquals(0.1, value.pitchRate, 1e-6)
        assertEquals(0.2, value.yawRate, 1e-6)
        assertEquals(0.3, value.rollRate, 1e-6)
        assertEquals(9.80665, value.acceleration, 1e-6)
    }

    @Test fun wrongSourceMissingSensorsAndMalformedValuesAreRejected() {
        for (invalid in listOf(
            sample().copy(source = MotionSource.NEURAL_BAND), sample().copy(source = MotionSource.UNKNOWN),
            sample().copy(gyroscope = null), sample().copy(accelerometer = null),
            sample().copy(gyroscope = Vector3(Float.NaN, 0f, 0f)),
            sample().copy(accelerometer = Vector3(0f, Float.POSITIVE_INFINITY, 0f)),
            sample().copy(accelerometer = Vector3(0f, 0f, 0f)), sample().copy(timestampNs = -1),
        )) assertNull(invalid.toHeadMotionSample())
    }
}
