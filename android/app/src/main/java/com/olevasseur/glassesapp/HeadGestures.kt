package com.olevasseur.glassesapp

import android.content.Context
import android.os.SystemClock
import com.meta.wearable.dat.core.session.DeviceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

data class HeadGesturesState(
    val enabled: Boolean = false,
    val sensitivity: Float = 0.5f,
    val live: Boolean = false,
    val status: String = "Off",
    val lastGesture: String = "None",
    val question: String? = null,
)

/** Main-thread coordinator; foreground/session lifecycle is owned by the view model. */
class HeadGestures(context: Context, private val scope: CoroutineScope,
    private val onMotion: (HeadMotionSample) -> Unit = {}) {
    private val preferences = context.getSharedPreferences("head_gestures", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(HeadGesturesState(
        enabled = preferences.getBoolean("enabled", false),
        sensitivity = preferences.getFloat("sensitivity", 0.5f).let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0.5f },
    ))
    val state = mutableState.asStateFlow()
    private val detector = HeadGestureDetector(state.value.sensitivity)
    private val confirmation = GestureConfirmation()
    private var lastSampleAt = 0L

    private val motionRequested = MutableStateFlow(false)
    fun requestMotion(enabled: Boolean) { motionRequested.value = enabled }

    private var workoutMotion = false

    fun observe(
        sessions: StateFlow<DeviceSession?>, send: (String) -> Boolean,
        workoutDemand: Flow<Boolean> = flowOf(false),
        onWorkoutSample: (HeadMotionSample) -> Unit = {},
        onWorkoutUnavailable: () -> Unit = {},
        deviceApproval: (HeadGesture) -> Boolean = { false },
        navigate: (HeadGesture) -> Boolean = { false },
    ) {
        scope.launch {
            combine(sessions, state.map { it.enabled }.distinctUntilChanged(), workoutDemand, motionRequested) { session, enabled, workout, requested ->
                workoutMotion = workout
                session.takeIf { enabled || workout || requested }
            }.distinctUntilChanged().collectLatest { session ->
                onWorkoutUnavailable()
                unavailable(if (!state.value.enabled) "Off" else "Start a conversation with connected glasses")
                if (session == null) return@collectLatest
                try {
                    collectGlassesMotion(session, onSample = { sample ->
                        onMotion(sample)
                        lastSampleAt = SystemClock.elapsedRealtime()
                        onWorkoutSample(sample)
                        if (workoutMotion || !state.value.enabled) {
                            confirmation.clear(); detector.reset()
                            mutableState.update { it.copy(live = true, question = null,
                                status = if (workoutMotion) "Workout motion · gestures paused" else "Off") }
                            return@collectGlassesMotion
                        }
                        mutableState.update { it.copy(live = true, status = "Live · confirmations / project steps") }
                        val gesture = detector.add(sample)
                        if (gesture != null) {
                            val sent = deviceApproval(gesture) || confirmation.route(gesture, lastSampleAt, send, navigate)
                            mutableState.update { it.copy(lastGesture = "${gesture.name.lowercase()} · ${if (sent) "handled" else "no action"}", question = confirmation.question) }
                        }
                    }, onStatus = { label -> onWorkoutUnavailable(); unavailable(label) })
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { onWorkoutUnavailable(); unavailable("Motion unavailable; toggle gestures to retry") }
            }
        }
        scope.launch {
            while (isActive) {
                delay(500)
                val now = SystemClock.elapsedRealtime()
                if (state.value.live && now - lastSampleAt > 1_000) {
                    onWorkoutUnavailable(); unavailable("No recent IMU samples")
                }
                confirmation.expire(now)
                mutableState.update { it.copy(question = confirmation.question) }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        confirmation.clear(); detector.reset()
        mutableState.update { it.copy(enabled = enabled, live = false, question = null, status = if (enabled) "Waiting for glasses" else "Off") }
        preferences.edit().putBoolean("enabled", enabled).apply()
    }

    fun setSensitivity(sensitivity: Float) {
        if (!sensitivity.isFinite()) return
        val value = sensitivity.coerceIn(0f, 1f)
        detector.sensitivity = value
        mutableState.update { it.copy(sensitivity = value) }
        preferences.edit().putFloat("sensitivity", value).apply()
    }

    fun localTool(call: FunctionCall): JSONObject? {
        val now = SystemClock.elapsedRealtime()
        val result = confirmation.request(call, !workoutMotion && state.value.enabled && state.value.live && now - lastSampleAt <= 1_000, now)
        if (result != null) {
            detector.reset() // A motion begun before the question cannot answer it.
            mutableState.update { it.copy(question = confirmation.question) }
        }
        return result
    }

    fun onEvent(event: ServerEvent) {
        confirmation.onEvent(event)
        if (event.turnComplete) detector.reset()
        mutableState.update { it.copy(question = confirmation.question) }
    }

    fun disconnected() {
        confirmation.clear(); detector.reset()
        mutableState.update { it.copy(question = null) }
    }

    private fun unavailable(label: String) {
        disconnected()
        mutableState.update { it.copy(live = false, status = label) }
    }
}
