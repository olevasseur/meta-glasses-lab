package com.olevasseur.glassesapp

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class AnnouncementLimit {
    private val spoken = mutableMapOf<String, Long>()
    fun allow(phrase: String, now: Long): Boolean {
        if (spoken[phrase]?.let { now - it < 60_000 } == true) return false
        spoken[phrase] = now
        return true
    }
}

/** Uses the existing communication route (glasses SCO when connected), with an offline voice only. */
class RecoveryAnnouncements(context: Context, private val earcon: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val limit = AnnouncementLimit()
    private var ready = false
    private var pending: String? = null
    private var engine: TextToSpeech? = null
    @Volatile private var mutedUntil = 0L
    val speaking get() = SystemClock.elapsedRealtime() < mutedUntil
    init {
        engine = TextToSpeech(context) { status -> main.post {
            val tts = engine ?: return@post
            if (status != TextToSpeech.SUCCESS) return@post
            val voice = tts.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == Locale.getDefault().language }
                ?: return@post
            tts.voice = voice
            tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { mutedUntil = SystemClock.elapsedRealtime() + 10_000 }
                override fun onDone(id: String?) { mutedUntil = SystemClock.elapsedRealtime() + 500 }
                @Deprecated("Platform callback") override fun onError(id: String?) { mutedUntil = 0 }
                override fun onError(id: String?, errorCode: Int) { mutedUntil = 0 }
            })
            ready = true
            pending?.let { pending = null; speak(it) }
        } }
    }
    fun say(phrase: String) {
        if (!limit.allow(phrase, SystemClock.elapsedRealtime())) return
        mutedUntil = SystemClock.elapsedRealtime() + 10_000
        earcon()
        if (ready) speak(phrase) else pending = phrase
    }
    private fun speak(phrase: String) {
        if (engine?.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "recovery") != TextToSpeech.SUCCESS) mutedUntil = 0
    }
    fun stop() { pending = null; engine?.stop(); mutedUntil = 0 }
    fun close() { stop(); engine?.shutdown(); engine = null }
}
