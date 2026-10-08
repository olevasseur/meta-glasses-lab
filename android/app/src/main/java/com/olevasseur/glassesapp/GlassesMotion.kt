package com.olevasseur.glassesapp

import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.motion.addMotion
import com.meta.wearable.dat.motion.removeMotion
import com.meta.wearable.dat.motion.types.MotionConfiguration
import com.meta.wearable.dat.motion.types.MotionSample
import com.meta.wearable.dat.motion.types.MotionSamplingRate
import com.meta.wearable.dat.motion.types.MotionSource
import com.meta.wearable.dat.motion.types.MotionState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/** Attach to the capture-button session: DAT capabilities share one device session. */
suspend fun collectGlassesMotion(
    session: DeviceSession,
    onSample: (HeadMotionSample) -> Unit,
    onStatus: (String) -> Unit,
) = coroutineScope {
    val motion = session.addMotion(MotionConfiguration(MotionSamplingRate.HZ_30)).fold(
        onSuccess = { it },
        onFailure = { error, _ -> onStatus("Motion unavailable: ${error.description}"); null },
    ) ?: return@coroutineScope
    val jobs = listOf(
        launch(start = CoroutineStart.UNDISPATCHED) {
            motion.state.collect { onStatus(if (it == MotionState.STARTED) "Waiting for IMU samples" else "Motion: $it") }
        },
        launch(start = CoroutineStart.UNDISPATCHED) {
            motion.errors.collect { error -> error?.let { onStatus("Motion: ${it.description}") } }
        },
        launch(start = CoroutineStart.UNDISPATCHED) {
            motion.samples.collect { sample ->
                if (sample.source != MotionSource.GLASSES || motion.state.value != MotionState.STARTED) return@collect
                val converted = sample.toHeadMotionSample()
                if (converted == null) onStatus("Glasses gyro/accelerometer unavailable") else {
                    DiagnosticMetrics.gyro = "x=%.3f y=%.3f z=%.3f rad/s".format(converted.pitchRate, converted.yawRate, converted.rollRate)
                    DiagnosticMetrics.gyroUpdatedMs = android.os.SystemClock.elapsedRealtime()
                    onSample(converted)
                }
            }
        },
    )
    try {
        motion.start()
        awaitCancellation()
    } finally {
        jobs.forEach { it.cancel() }
        motion.stop()
        session.removeMotion().fold(onSuccess = {}, onFailure = { error, _ -> onStatus("Motion cleanup: ${error.description}") })
    }
}

internal fun MotionSample.toHeadMotionSample(): HeadMotionSample? {
    if (source != MotionSource.GLASSES || timestampNs < 0) return null
    val gyro = gyroscope ?: return null
    val accel = accelerometer ?: return null
    if (listOf(gyro.x, gyro.y, gyro.z, accel.x, accel.y, accel.z).any { !it.isFinite() }) return null
    // BirdSpotter's measured body frame: X lateral, Y up, -Z forward.
    // Rotation about X is pitch, about Y is yaw. Both gesture polarities are accepted.
    val magnitude = sqrt(accel.x.toDouble() * accel.x + accel.y.toDouble() * accel.y + accel.z.toDouble() * accel.z)
    if (magnitude < 1.0) return null
    return HeadMotionSample(timestampNs, gyro.x.toDouble(), gyro.y.toDouble(), gyro.z.toDouble(), magnitude)
}
