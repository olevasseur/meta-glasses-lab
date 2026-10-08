package com.olevasseur.glassesapp

import android.content.Context
import com.meta.wearable.dat.core.session.DeviceSession
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class OfflineCapture(context: Context, scope: CoroutineScope, sessions: StateFlow<DeviceSession?>,
    private val backend: BackendClient, private val audio: LiveAudio,
    private val recap: (String) -> Boolean, private val status: (String) -> Unit) {
    private val outbox = OfflineOutbox(File(context.noBackupFilesDir, "offline-capture"))
    private val voice = RecoveryAnnouncements(context) { audio.enqueue(diagnosticBeep()) }
    private var listening = false
    private var connected = false
    private var enabled = false
    private var outage = false
    private var pendingRecap = emptyList<String>()
    val speaking get() = voice.speaking
    private val speech = OverSpeechRecognizer(context, scope, sessions, onText = { text, final ->
        if (final && listening && !voice.speaking) {
            try { outbox.capture(text); status("Offline speech saved on phone; delivery pending") }
            catch (_: Exception) { status("Offline speech could not be saved; repeat a shorter note") }
        }
    }, onStatus = { if (listening) status("Offline voice ready: add a note … / remember …") },
        onError = { if (listening) status("Offline speech unavailable; install the on-device English speech model") })
    private val worker = scope.launch {
        while (isActive) {
            try {
                outbox.replay(backend::offlineTool)
                if (enabled && connected && pendingRecap.isEmpty() && !voice.speaking) {
                    outbox.summary()?.let { (ids, text) -> if (recap(text)) pendingRecap = ids }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { status("Offline queue needs attention; files retained on phone") }
            delay(3_000)
        }
    }
    fun update(active: Boolean, online: Boolean, unavailable: Boolean) {
        enabled = active
        connected = online
        if (!online) pendingRecap = emptyList()
        if (active && unavailable) { outage = true; voice.say("Gemini is busy, retrying") }
        if (active && online && outage) { outage = false; voice.say("Back online") }
        val listen = active && !online && outage
        if (listen != listening) {
            listening = listen
            if (listen) speech.startOnDevice() else speech.stop()
        }
        if (!active) voice.stop()
    }
    fun audio(pcm: ByteArray) { if (listening && !voice.speaking) speech.audio(pcm) }
    fun review() = outbox.review()
    fun interrupted() { pendingRecap = emptyList() }
    fun turnComplete() {
        if (pendingRecap.isNotEmpty()) {
            try { outbox.acknowledge(pendingRecap); pendingRecap = emptyList() }
            catch (_: Exception) { status("Offline receipt could not be acknowledged; files retained") }
        }
    }
    fun stop() { update(false, false, false); outage = false }
    fun close() { stop(); worker.cancel(); voice.close() }
}
