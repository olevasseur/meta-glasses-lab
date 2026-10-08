package com.olevasseur.glassesapp

import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Local system speech: a dropped Live session cannot suppress a timer announcement. */
class CoachAlarmService : Service() {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val waiting = mutableListOf<CoachTimer>()
    private val speaking = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private val timeout = Runnable { stopSelf() }

    override fun onCreate() {
        super.onCreate()
        val alarms = CoachAlarm(this)
        alarms.channels()
        startForeground(7301, alarms.notification("Announcing a finished timer", speech = true))
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:coachTimer")
            .apply { acquire(60_000) }
        handler.postDelayed(timeout, 55_000) // Notification remains even if a speech engine hangs.
        tts = TextToSpeech(this) { status -> handler.post {
            if (status != TextToSpeech.SUCCESS) { stopSelf(); return@post }
            val engine = tts ?: return@post
            val voice = engine.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale == Locale.getDefault() }
                ?: engine.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == Locale.getDefault().language }
            if (voice == null) { stopSelf(); return@post }
            engine.voice = voice
            val audio = getSystemService(AudioManager::class.java)
            // Existing conversation owns SCO routing; otherwise media follows Bluetooth/A2DP.
            engine.setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(if (audio.mode == AudioManager.MODE_IN_COMMUNICATION) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA).build())
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) = finished(id)
                @Deprecated("Platform callback")
                override fun onError(id: String?) = finished(id)
                override fun onError(id: String?, errorCode: Int) = finished(id)
            })
            ready = true
            waiting.toList().also { waiting.clear() }.forEach(::speak)
        } }
    }
    private fun finished(id: String?) { handler.post {
        speaking.remove(id)
        if (waiting.isEmpty() && speaking.isEmpty()) stopSelf()
    } }
    private fun speak(timer: CoachTimer) {
        speaking += timer.id
        if (tts?.speak("The ${timer.name} timer is done", TextToSpeech.QUEUE_ADD, null, timer.id) != TextToSpeech.SUCCESS) finished(timer.id)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val timer = intent?.getStringExtra("id")?.let { CoachAlarm(this).due(it) }
        if (timer != null) { if (ready) speak(timer) else waiting += timer }
        else if (waiting.isEmpty() && speaking.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tts?.stop(); tts?.shutdown(); tts = null
        if (wake?.isHeld == true) wake?.release()
        super.onDestroy()
    }
}
