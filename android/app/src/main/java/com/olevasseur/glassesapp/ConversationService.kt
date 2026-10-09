@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.olevasseur.glassesapp

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.voiceinvocations.VoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.startVoiceInvocationsStream
import com.meta.wearable.dat.core.voiceinvocations.types.actions.LaunchApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** One conversation owner across rotation, backgrounding and Activity rebinding. */
class ConversationService : Service(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    val model by lazy { ViewModelProvider(viewModelStore, ViewModelProvider.AndroidViewModelFactory(application))[ConversationViewModel::class.java] }
    inner class LocalBinder : Binder() { val service get() = this@ConversationService }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager by lazy { getSystemService(AudioManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private var controls = HandsFreeState()
    private var foreground = false
    private val uiResumed = MutableStateFlow(false)
    private var hasFocus = false
    private var focusRequested = false
    private var phoneListening = false
    private var voiceStream: VoiceInvocationsStream? = null
    private var settingsInitialized = false
    private var lastNotification: String? = null
    private val wakeLock by lazy { getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "glassesapp:conversation").apply { setReferenceCounted(false) } }
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ focus ->
                hasFocus = controls.conversation && focus == AudioManager.AUDIOFOCUS_GAIN
                if (controls.conversation) update(controls.pause(PauseReason.FOCUS, !hasFocus))
            }, Handler(Looper.getMainLooper())).build()
    }
    private val phoneListener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            val inCall = state != TelephonyManager.CALL_STATE_IDLE
            update(controls.pause(PauseReason.CALL, inCall))
            if (!inCall && controls.conversation) obtainFocus()
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Hands-free conversation", NotificationManager.IMPORTANCE_LOW))
        model.service = this
        model.controls(controls)
        scope.launch { model.state.collect { state ->
            if (!settingsInitialized && state.settingsLoaded) {
                settingsInitialized = true
                controls = controls.copy(mode = state.settings.mode)
                model.controls(controls)
            }
            reconcile(); updateNotification()
        } }
        scope.launch {
            while (true) {
                delay(1_000)
                if (controls.shouldRun && (model.workout.state.value.active || model.state.value.meeting ||
                    model.state.value.visualCapturing || (model.state.value.running && !model.state.value.connected))) activity()
                if (controls.expired(now(), model.state.value.idleMinutes * 60_000L)) stopAll()
            }
        }
    }

    override fun onBind(intent: Intent) = LocalBinder()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP -> stopAll()
            MODE -> if (controls.armed) setMode(controls.toggleMode(now()).mode)
            INPUT -> if (controls.armed) {
                if (controls.ptt && model.state.value.talking) { model.finishTalking(); activity() }
                else toggleInput()
            }
        }
        // A killed microphone session must never restart without a new user action.
        return START_NOT_STICKY
    }

    fun uiResumed(resumed: Boolean) { uiResumed.value = resumed }

    fun permissionsChanged() {
        model.permissionsGranted(granted(Manifest.permission.RECORD_AUDIO))
        if (!model.state.value.permissions && controls.armed) stopAll()
        if (Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            if (model.glasses.initialize() && voiceStream == null) {
                val stream = try { Wearables.startVoiceInvocationsStream(AutoDeviceSelector()) }
                catch (_: Exception) { model.error("Voice launch unavailable; use Start or the launcher shortcut."); null }
                if (stream != null) {
                    voiceStream = stream
                    scope.launch {
                        stream.invocations.collect { invocation ->
                            if (invocation is LaunchApp) {
                                // Meta can deliver the action before the launched Activity reaches onResume.
                                val visible = foreground || withTimeoutOrNull(10_000) { uiResumed.first { it } } == true
                                val loaded = withTimeoutOrNull(10_000) { model.state.first { it.settingsLoaded } } != null
                                val accepted = visible && loaded && startConversation()
                                val delivered = if (accepted) invocation.responseHandle.sendSuccess(null)
                                    else invocation.responseHandle.sendFailure("Open the app, grant permissions, and retry when audio is available")
                                if (!delivered) model.error("Voice launch acknowledgement could not be delivered.")
                            }
                        }
                    }
                    scope.launch { stream.errors.collect { model.error("Voice invocation: ${it.description}") } }
                }
            }
        }
        if (!phoneListening && granted(Manifest.permission.READ_PHONE_STATE)) {
            try {
                getSystemService(TelephonyManager::class.java).listen(phoneListener, PhoneStateListener.LISTEN_CALL_STATE)
                phoneListening = true
            } catch (_: Exception) { model.error("Call monitoring unavailable; audio focus will pause interruptions.") }
        }
    }

    private fun ensureForeground(): Boolean {
        if (foreground) return true
        if (!granted(Manifest.permission.RECORD_AUDIO)) { model.error("Grant microphone permission before starting."); return false }
        return try {
            var types = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            if (Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            startForeground(NOTIFICATION, notification(), types)
            foreground = true
            startService(Intent(this, ConversationService::class.java)) // Keep ownership after the UI unbinds.
            wakeLock.acquire(65 * 60_000L)
            true
        } catch (_: RuntimeException) {
            model.error("Android blocked microphone startup. Open the app and tap Start.")
            if (wakeLock.isHeld) wakeLock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
            false
        }
    }

    fun arm(): Boolean {
        if (!model.state.value.settingsLoaded) return false
        if (!settingsInitialized) {
            settingsInitialized = true
            controls = controls.copy(mode = model.state.value.settings.mode)
        }
        permissionsChanged()
        if (!ensureForeground()) return false
        update(controls.arm(now()))
        activity()
        model.glasses.startInputs()
        return true
    }

    fun startConversation(): Boolean {
        if (!BuildConfig.DEBUG || BuildConfig.APP_SHARED_SECRET.isBlank()) {
            model.error("Set APP_SHARED_SECRET in android/local.properties and rebuild the debug app.")
            return false
        }
        if (!arm()) return false
        // Check synchronously too: the initial phone callback may still be queued.
        if (granted(Manifest.permission.READ_PHONE_STATE)) {
            runCatching { getSystemService(TelephonyManager::class.java).callState }.getOrNull()?.let {
                controls = controls.pause(PauseReason.CALL, it != TelephonyManager.CALL_STATE_IDLE)
            }
        }
        update(controls.start(now()))
        obtainFocus()
        return controls.shouldRun && hasFocus
    }

    fun captureHold() {
        if (!controls.armed) return
        if (model.state.value.meeting) { model.askMeetingAssistant(); return }
        if (!controls.conversation) startConversation()
        else {
            if (controls.shouldRun && hasFocus) chime(false)
            update(controls.captureHold(now()))
            activity()
        }
    }

    fun endConversation() {
        if (controls.conversation) update(controls.captureHold(now()))
    }

    fun setMode(mode: ConversationMode) {
        if (!model.state.value.settingsLoaded || model.state.value.meeting) return
        if (controls.mode != mode) {
            activity()
            model.finishTalking()
            update(controls.setMode(mode, now()))
        }
    }

    fun toggleInput() {
        if (model.state.value.meeting || !controls.shouldRun || !model.state.value.connected) return
        if (controls.ptt) model.toggleGlassesTalking()
        else if (controls.mode == ConversationMode.AUTO) update(controls.toggleMute(now()))
        else return // Over ends only on sign-off, silence, or a pending gesture answer.
        activity()
    }

    fun inputsAvailable(available: Boolean) {
        update(controls.inputsAvailable(available))
    }

    fun bluetoothLost() { update(controls.pause(PauseReason.BLUETOOTH, true)) }
    fun activity() {
        controls = controls.activity(now())
        if (foreground) wakeLock.acquire(65 * 60_000L)
    }
    private fun obtainFocus() {
        if (!controls.conversation || PauseReason.CALL in controls.pauses) return
        focusRequested = true
        hasFocus = manager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        update(controls.pause(PauseReason.FOCUS, !hasFocus))
    }

    private fun update(value: HandsFreeState) {
        controls = value
        model.controls(controls)
        reconcile()
        updateNotification()
    }

    private fun reconcile() {
        val state = model.state.value
        if (!settingsInitialized) return
        val run = controls.shouldRun && hasFocus
        if (state.running && (!controls.conversation || !controls.armed || state.settings.mode != controls.mode)) model.stopSession()
        if (state.stopping) return
        if (!state.running) {
            model.applyMode(controls.mode)
            if (run) model.startSession()
            else if (!controls.conversation && focusRequested) {
                manager.abandonAudioFocusRequest(focusRequest); focusRequested = false; hasFocus = false
            }
        }
        if (!controls.armed && !model.state.value.running && foreground) {
            if (wakeLock.isHeld) wakeLock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            lastNotification = null
            stopSelf()
        }
    }

    fun stopAll() {
        model.cancelMeetingTransition()
        if (controls.shouldRun && hasFocus) chime(false)
        update(controls.stop())
        model.glasses.stopInputs()
    }

    fun audioStarted() { if (controls.shouldRun && hasFocus) chime(true) }

    fun thinkingCue() {
        if (!controls.shouldRun || !hasFocus) return
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 20) }.getOrNull() ?: return
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 140)
    }

    private fun chime(start: Boolean) {
        // Platform-generated short tones: no bundled media or runtime AI request.
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 35) }.getOrNull() ?: return
        tone.startTone(if (start) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, 140)
        Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 200)
    }

    private fun notification(): Notification {
        val state = model.state.value
        val status = when {
            controls.pauses.isNotEmpty() && controls.conversation -> "Paused: ${controls.pauses.joinToString()}. Open to resume."
            !controls.conversation -> if (controls.inputsAvailable) "Glasses controls ready · hold capture to start" else "Open app to start"
            state.meeting -> "Meeting capture · ${state.gemini} · say ${state.settings.meetingWakePhrase}"
            controls.muted -> "Microphone muted · double-tap to unmute"
            controls.mode == ConversationMode.OVER -> "Over · ${state.overStatus}"
            controls.ptt -> if (state.talking) "Listening · double-tap to finish" else "Push-to-talk · double-tap to speak"
            else -> "${state.gemini} · automatic microphone"
        }
        val label = if (controls.armed && !controls.inputsAvailable)
            "Glasses controls unavailable · use screen/notification · $status" else status
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_glasses)
            .setContentTitle(if (state.visualCapturing) "Visual memory is ON · the glasses app" else "the glasses app")
            .setContentText(label).setStyle(Notification.BigTextStyle().bigText(label)).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(Notification.Action.Builder(null, "Stop", action(STOP)).build())
            .apply { if (!state.meeting) addAction(Notification.Action.Builder(null, "${controls.toggleMode(now()).mode.label} mode", action(MODE)).build()) }
            .apply {
                if (!state.meeting && controls.mode != ConversationMode.OVER) addAction(Notification.Action.Builder(null,
                    if (controls.ptt) (if (state.talking) "Finish" else "Talk") else (if (controls.muted) "Unmute" else "Mute"), action(INPUT)).build())
            }
            .build()
    }

    private fun action(action: String) = PendingIntent.getService(this, 0, Intent(this, ConversationService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun updateNotification() {
        if (!foreground) return
        // Transcripts and the idle clock should not spam NotificationManager.
        val state = model.state.value
        val key = "${controls.copy(lastActivityMs = 0)}:${state.gemini}:${state.talking}:${state.overStatus}:${state.meeting}:${state.visualCapturing}"
        if (key != lastNotification) { notifications.notify(NOTIFICATION, notification()); lastNotification = key }
    }
    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    private fun now() = SystemClock.elapsedRealtime()

    override fun onDestroy() {
        scope.cancel()
        voiceStream?.close()
        if (phoneListening) runCatching { getSystemService(TelephonyManager::class.java).listen(phoneListener, PhoneStateListener.LISTEN_NONE) }
        manager.abandonAudioFocusRequest(focusRequest)
        viewModelStore.clear()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "conversation"
        private const val NOTIFICATION = 11
        private const val STOP = "com.olevasseur.glassesapp.STOP"
        private const val MODE = "com.olevasseur.glassesapp.MODE"
        private const val INPUT = "com.olevasseur.glassesapp.INPUT"
        const val START = "com.olevasseur.glassesapp.START_CONVERSATION"
    }
}
