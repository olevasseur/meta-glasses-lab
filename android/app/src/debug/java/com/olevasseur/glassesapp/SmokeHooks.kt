package com.olevasseur.glassesapp

import android.content.Intent
import android.os.SystemClock
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** Debug-only PCM source. Silence between fixtures prevents the host microphone leaking in. */
object SmokeHooks {
    @Volatile var wavEnabled = false
        private set
    val playedBytes = AtomicLong()
    private var pending: Pair<ByteArray, () -> Unit>? = null
    @Volatile var injecting = false
        private set

    fun configure(intent: Intent, model: ConversationViewModel) {
        if (!intent.hasExtra("debug_mock_glasses") || !model.state.value.settingsLoaded ||
            !model.state.value.permissions || model.state.value.handsFree.armed ||
            model.state.value.running || model.state.value.stopping) return
        val enabled = intent.getBooleanExtra("debug_mock_glasses", false)
        model.setMock(enabled)
        if (model.state.value.mock != enabled) return
        if (enabled) model.glasses.mockDevice?.let { glasses ->
            glasses.services.speech.setTranscriptionSource(com.meta.wearable.dat.mockdevice.api.speech.MockSpeechSource.INJECTED)
            glasses.services.motion.setMotionFeed((0 until 90).map { index ->
                com.meta.wearable.dat.motion.types.MotionSample(
                    timestampNs = index * 33_333_333L,
                    accelerometer = com.meta.wearable.dat.motion.types.Vector3(0f, 9.81f, 0f),
                    gyroscope = com.meta.wearable.dat.motion.types.Vector3(0f, 0f, 0f),
                    source = com.meta.wearable.dat.motion.types.MotionSource.GLASSES,
                )
            }, true)
        }
        wavEnabled = enabled && intent.getBooleanExtra("debug_wav_audio", false)
        intent.removeExtra("debug_mock_glasses")
    }

    @Synchronized fun mockChanged(enabled: Boolean) {
        if (!enabled) { wavEnabled = false; pending = null; injecting = false }
    }

    @Synchronized fun inject(input: InputStream, finished: () -> Unit = {}) {
        check(wavEnabled && !injecting) { "Enable debug WAV audio; wait for the previous fixture" }
        pending = PcmWav.read(input) to finished
        injecting = true
    }

    @Synchronized private fun take() = pending.also { pending = null }

    suspend fun capture(onAudio: (ByteArray) -> Unit) {
        try {
            while (currentCoroutineContext().isActive) {
                val clip = take()
                if (clip == null) { onAudio(ByteArray(640)); delay(20); continue }
                val start = SystemClock.elapsedRealtime()
                for (offset in clip.first.indices step 640) {
                    onAudio(clip.first.copyOfRange(offset, minOf(offset + 640, clip.first.size)))
                    delay(maxOf(1, start + (offset / 2 + 320) * 1000L / 16000 - SystemClock.elapsedRealtime()))
                }
                clip.second()
                injecting = false
            }
        } finally { synchronized(this) { pending = null; injecting = false } }
    }

    fun played(count: Int) { if (wavEnabled && count > 0) playedBytes.addAndGet(count.toLong()) }
}
