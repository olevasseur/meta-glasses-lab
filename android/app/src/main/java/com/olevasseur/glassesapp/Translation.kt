package com.olevasseur.glassesapp

import java.io.ByteArrayOutputStream
import org.json.JSONObject

object Translation {
    // Initial chooser subset; names also form the spoken routing labels.
    val languages = linkedMapOf(
        "es" to "Spanish", "fr" to "French", "de" to "German", "it" to "Italian",
        "pt-BR" to "Portuguese", "ja" to "Japanese", "ko" to "Korean",
        "zh-Hans" to "Chinese", "ar" to "Arabic", "hi" to "Hindi",
        "ru" to "Russian", "uk" to "Ukrainian",
    )

    fun tokenRequest(ptt: Boolean, language: String?): JSONObject {
        val body = if (ptt) JSONObject().put("mode", "ptt") else JSONObject()
        if (language != null) {
            require(language in languages) { "Unsupported translation language" }
            body.put("mode", if (ptt) "ptt" else "auto")
                .put("persona", "translator").put("language", language)
        }
        return body
    }

    fun switchedLanguage(call: FunctionCall, result: JSONObject): String? {
        require(result.optString("status") == "ok")
        return when (call.name) {
            "start_translation" -> result.getString("language").also { require(it in languages) }
            "stop_translation" -> null
            else -> error("Not a translation command")
        }
    }
}

data class TranslationUtterance(val pcm: ByteArray, val phone: Boolean)

/** Transcripts and audio can arrive in either order. Route only a complete turn. */
class TranslationBuffer {
    private val audio = ByteArrayOutputStream()
    private val transcript = StringBuilder()

    fun accept(event: ServerEvent, language: String): TranslationUtterance? {
        if (event.interrupted) { clear(); return null }
        event.outputText?.let {
            check(transcript.length + it.length <= 12_000) { "Translation transcript too long" }
            transcript.append(it)
        }
        event.audio.forEach {
            // ponytail: short consecutive interpretation, at most 40 seconds per turn.
            check(it.size % 2 == 0 && audio.size() + it.size <= 1_920_000) { "Translation audio too long or malformed" }
            audio.write(it)
        }
        if (!event.turnComplete) return null
        val name = Translation.languages.getValue(language)
        // Unknown/missing labels always stay on the private route. Never guess from Latin script.
        val phone = Regex("^\\s*${Regex.escape(name)}\\s*[:.,!—-]", RegexOption.IGNORE_CASE)
            .containsMatchIn(transcript)
        return TranslationUtterance(audio.toByteArray(), phone).also { clear() }
    }

    fun clear() { audio.reset(); transcript.clear() }
}
