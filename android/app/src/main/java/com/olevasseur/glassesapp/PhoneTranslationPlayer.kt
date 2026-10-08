package com.olevasseur.glassesapp

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Main-thread lifecycle. The microphone thread only reads [playing]. */
class PhoneTranslationPlayer(context: Context, private val scope: CoroutineScope) : TranslationPlayback {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var job: Job? = null
    private var track: AudioTrack? = null
    private var previous: AudioDeviceInfo? = null
    private var routeSelected = false
    private var generation = 0
    @Volatile override var playing = false
        private set

    override fun play(pcm: ByteArray, onError: () -> Unit) {
        stop()
        if (pcm.isEmpty()) return
        if (Build.VERSION.SDK_INT < 31) { onError(); return }
        val current = generation
        playing = true // Gate input before switching the communication microphone as well.
        job = scope.launch {
            try {
                previous = manager.communicationDevice
                val speaker = manager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } ?: error("Phone speaker unavailable")
                check(manager.setCommunicationDevice(speaker))
                routeSelected = true
                withTimeout(3000) { while (manager.communicationDevice?.id != speaker.id) delay(20) }
                val minimum = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                val output = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(24000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(maxOf(minimum, 4800)).setTransferMode(AudioTrack.MODE_STREAM).build()
                track = output
                check(output.state == AudioTrack.STATE_INITIALIZED && output.setPreferredDevice(speaker))
                output.play()
                // Establish and verify routing with silence before sending any translation.
                val silence = ByteArray(4800)
                check(output.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING) >= 0)
                withTimeout(3000) { while (output.routedDevice?.id != speaker.id) delay(20) }
                output.pause(); output.flush(); output.play()
                withTimeout(45_000) {
                    var offset = 0
                    while (offset < pcm.size) {
                        check(manager.communicationDevice?.id == speaker.id)
                        val count = output.write(pcm, offset, minOf(960, pcm.size - offset), AudioTrack.WRITE_NON_BLOCKING)
                        check(count >= 0)
                        offset += count
                        delay(5)
                    }
                    while (output.playbackHeadPosition.toLong() < pcm.size / 2) delay(10)
                }
                releaseTrack()
                restoreRoute()
                previous?.let { device ->
                    withTimeout(3000) { while (manager.communicationDevice?.id != device.id) delay(20) }
                }
                delay(200) // Let speaker echo and the route transition settle before accepting input.
            } catch (e: CancellationException) {
                // A routing/playback timeout is a failure, not an intentional Stop.
                if (e is kotlinx.coroutines.TimeoutCancellationException) onError() else throw e
            } catch (_: Exception) {
                onError()
            } finally {
                if (generation == current) { releaseTrack(); restoreRoute(); playing = false }
            }
        }
    }

    private fun releaseTrack() {
        track?.let { runCatching { it.pause(); it.flush() }; it.release() }
        track = null
    }

    private fun restoreRoute() {
        if (Build.VERSION.SDK_INT >= 31 && routeSelected) {
            routeSelected = false
            val device = previous
            if (device == null || manager.availableCommunicationDevices.none { it.id == device.id } ||
                !manager.setCommunicationDevice(device)) manager.clearCommunicationDevice()
        }
    }

    override fun stop() {
        generation++
        job?.cancel(); job = null
        releaseTrack(); restoreRoute()
        playing = false
    }
}
