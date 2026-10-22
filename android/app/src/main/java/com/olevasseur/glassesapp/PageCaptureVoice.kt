package com.olevasseur.glassesapp

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale

internal fun pageCapturePhrase(doc: String) = "Taking picture for ${doc.trim()}"

/** Short on-device spoken confirmation of the target doc; offline voice only, never Live. */
class PageCaptureVoice(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var engine: TextToSpeech? = null
    private var pending: Pair<String, Boolean>? = null
    init {
        engine = TextToSpeech(context) { status -> main.post {
            val tts = engine ?: return@post
            if (status != TextToSpeech.SUCCESS) return@post
            tts.voice = tts.voices?.firstOrNull { !it.isNetworkConnectionRequired && it.locale.language == Locale.getDefault().language }
                ?: return@post
            ready = true
            pending?.let { (phrase, call) -> pending = null; say(phrase, call) }
        } }
    }

    /** Conversation audio uses the call route (glasses SCO); otherwise media (glasses A2DP). */
    fun say(phrase: String, conversationAudio: Boolean) {
        val tts = engine ?: return
        if (!ready) { pending = phrase to conversationAudio; return } // First press: speak once the engine starts.
        tts.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(if (conversationAudio) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        tts.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "page-capture")
    }

    fun close() { engine?.shutdown(); engine = null }
}
