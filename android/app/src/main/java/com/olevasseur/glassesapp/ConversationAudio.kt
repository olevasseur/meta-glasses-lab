package com.olevasseur.glassesapp

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** OS communication routing, independent of DAT's camera audio capability. */
@Suppress("DEPRECATION")
class ConversationAudio(private val context: Context, private val scope: CoroutineScope) {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var recorder: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var engaged = false
    private var originalMode = AudioManager.MODE_NORMAL
    private var routeDevice: AudioDeviceInfo? = null
    private var deviceCallback: AudioDeviceCallback? = null
    private val playbackLock = Any()
    private val queue = ArrayDeque<ByteArray>()
    private var queueBytes = 0
    private var headOffset = 0

    @SuppressLint("MissingPermission") // Activity requests RECORD_AUDIO / BLUETOOTH_CONNECT first.
    suspend fun start(forcePhone: Boolean, onAudio: (ByteArray) -> Unit, onRoute: (String) -> Unit, onError: (String) -> Unit, onBluetoothLost: () -> Unit = {}) {
        check(!engaged) { "Audio already active" }
        engaged = true
        originalMode = manager.mode
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        try {
            val canUseBluetooth = Build.VERSION.SDK_INT < 31 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val bluetooth = if (!forcePhone && canUseBluetooth) selectRoute() else { usePhoneRoute(); false }
            val rate = if (bluetooth) 8000 else 16000
            val buffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(buffer > 0) { "Microphone does not support $rate Hz PCM" }
            val record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(buffer, rate / 5 * 2)).build()
            recorder = record
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            if (!bluetooth) preferPhoneMic(record)
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
            }
            val minimum = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0) { "24 kHz playback unavailable" }
            val output = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(24000)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum, 4800))
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            synchronized(playbackLock) { track = output }
            check(output.state == AudioTrack.STATE_INITIALIZED) { "Speaker initialization failed" }
            routeDevice?.let { output.setPreferredDevice(it) }
            output.play()
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
            onRoute(if (bluetooth) "Bluetooth HFP · $rate → 16000 Hz" else "Phone microphone · 16000 Hz")
            deviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    if (bluetooth && removedDevices.any { it.id == routeDevice?.id }) {
                        // Gate uplink immediately; the owner then releases capture/playback.
                        onBluetoothLost()
                        captureJob?.cancel()
                    }
                }
            }.also { manager.registerAudioDeviceCallback(it, Handler(Looper.getMainLooper())) }
            captureJob = scope.launch(Dispatchers.IO) {
                val samples = ShortArray(rate / 50) // 20 ms; bounded latency and WebSocket traffic.
                val resampler = PcmResampler(rate)
                try {
                    while (isActive) {
                        val count = record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                        if (count < 0) error("Microphone read failed ($count)")
                        if (count > 0 && isActive) onAudio(resampler.process(samples, count))
                    }
                } catch (e: Exception) {
                    if (isActive) onError(e.message ?: "Microphone failed")
                }
            }
            playbackJob = scope.launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        synchronized(playbackLock) {
                            val chunk = queue.peekFirst()
                            if (chunk != null) {
                                // Nonblocking writes mean flush() never waits behind buffered speech.
                                val count = output.write(chunk, headOffset, minOf(960, chunk.size - headOffset), AudioTrack.WRITE_NON_BLOCKING)
                                check(count >= 0) { "Speaker write failed ($count)" }
                                headOffset += count
                                queueBytes -= count
                                if (headOffset == chunk.size) { queue.removeFirst(); headOffset = 0 }
                            }
                        }
                        delay(5)
                    }
                } catch (e: Exception) {
                    if (isActive) onError(e.message ?: "Speaker failed")
                }
            }
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    private suspend fun selectRoute(): Boolean {
        if (Build.VERSION.SDK_INT >= 31) {
            val bt = manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (bt != null && manager.setCommunicationDevice(bt)) {
                val connected = withTimeoutOrNull(3000) {
                    while (manager.communicationDevice?.id != bt.id) delay(50)
                    true
                } == true
                if (connected) { routeDevice = bt; return true }
            }
        } else if (manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) {
            val connected = withTimeoutOrNull(3000) {
                suspendCancellableCoroutine { continuation ->
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED && continuation.isActive) {
                                context.unregisterReceiver(this)
                                continuation.resume(true)
                            }
                        }
                    }
                    context.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
                    continuation.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                    manager.startBluetoothSco()
                    manager.isBluetoothScoOn = true
                }
            } == true
            if (connected) {
                routeDevice = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                return true
            }
        }
        usePhoneRoute()
        return false
    }

    private fun preferPhoneMic(record: AudioRecord) {
        manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            ?.let { record.setPreferredDevice(it) }
    }

    private fun usePhoneRoute() {
        if (Build.VERSION.SDK_INT >= 31) {
            manager.clearCommunicationDevice()
            routeDevice = manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                ?: manager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            routeDevice?.let { manager.setCommunicationDevice(it) }
        } else {
            manager.stopBluetoothSco()
            manager.isBluetoothScoOn = false
            routeDevice = null
        }
        synchronized(playbackLock) { track?.setPreferredDevice(routeDevice) }
    }

    fun enqueue(pcm: ByteArray) = synchronized(playbackLock) {
        if (pcm.isNotEmpty() && track != null) {
            // ponytail: cap at ~40 seconds; stop on overflow instead of growing without bound.
            check(queueBytes + pcm.size <= 1_920_000) { "Audio playback fell behind" }
            check(pcm.size % 2 == 0) { "Malformed 16-bit audio" }
            queue.addLast(pcm)
            queueBytes += pcm.size
        }
    }

    fun flush() = synchronized(playbackLock) {
        queue.clear()
        queueBytes = 0
        headOffset = 0
        track?.let { output -> output.pause(); output.flush(); output.play() }
    }

    suspend fun stop() {
        if (!engaged) return
        deviceCallback?.let { manager.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        captureJob?.cancel()
        withContext(Dispatchers.IO) { runCatching { recorder?.stop() } }
        captureJob?.cancelAndJoin()
        captureJob = null
        playbackJob?.cancelAndJoin()
        playbackJob = null
        recorder?.release()
        recorder = null
        echoCanceler?.release()
        echoCanceler = null
        synchronized(playbackLock) {
            track?.let { runCatching { it.pause(); it.flush() }; it.release() }
            track = null
            queue.clear(); queueBytes = 0; headOffset = 0
        }
        if (Build.VERSION.SDK_INT >= 31) manager.clearCommunicationDevice()
        else { manager.stopBluetoothSco(); manager.isBluetoothScoOn = false }
        manager.mode = originalMode
        routeDevice = null
        engaged = false
    }
}
