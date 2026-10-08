package com.olevasseur.glassesapp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

enum class ConversationMode(val wireName: String, val label: String) {
    AUTO("auto", "Auto"), PTT("ptt", "Push-to-talk"), OVER("over", "Over"),
}

data class ConversationSettings(
    val mode: ConversationMode = ConversationMode.AUTO,
    val silenceMs: Int = 2000,
    val keyword: String = "over",
    val overSilenceSeconds: Int = 10,
    val idleMinutes: Int = HandsFreeState.DEFAULT_IDLE_MINUTES,
    val translationLanguage: String? = null,
    val translationPhoneSpeaker: Boolean = false,
) {
    fun normalized() = copy(
        silenceMs = silenceMs.coerceIn(500, 5000),
        idleMinutes = HandsFreeState.idleMinutes(idleMinutes),
        keyword = keyword.takeIf { it in KEYWORDS } ?: "over",
        overSilenceSeconds = overSilenceSeconds.coerceIn(5, 30),
        translationLanguage = translationLanguage?.takeIf { it in Translation.languages },
    )

    companion object { val KEYWORDS = listOf("over", "go ahead", "your turn") }
}

val Context.conversationSettingsStore by preferencesDataStore(
    name = "conversation_settings",
    produceMigrations = { context -> listOf(
        SharedPreferencesMigration(context, "hands_free", setOf("idle_minutes")),
        SharedPreferencesMigration(context, "job_events"),
    ) },
)

class ConversationSettingsStore(private val store: DataStore<Preferences>) {
    private val idleKey = intPreferencesKey("idle_minutes")
    private val modeKey = stringPreferencesKey("mode")
    private val silenceKey = intPreferencesKey("silence_ms")
    private val keywordKey = stringPreferencesKey("over_keyword")
    private val legacySafetyKey = intPreferencesKey("over_safety_seconds")
    private val overSilenceKey = intPreferencesKey("over_silence_seconds")
    private val translationLanguageKey = stringPreferencesKey("translation_language")
    private val translationSpeakerKey = booleanPreferencesKey("translation_phone_speaker")

    val settings = store.data.onStart {
        store.edit {
            if (legacySafetyKey in it) {
                // Speech duration has different semantics: migrate to the silence default.
                if (overSilenceKey !in it) it[overSilenceKey] = 10
                it.remove(legacySafetyKey)
            }
        }
    }.map { prefs ->
        ConversationSettings(
            mode = ConversationMode.entries.firstOrNull { it.wireName == prefs[modeKey] } ?: ConversationMode.AUTO,
            idleMinutes = prefs[idleKey] ?: HandsFreeState.DEFAULT_IDLE_MINUTES,
            silenceMs = prefs[silenceKey] ?: 2000,
            keyword = prefs[keywordKey] ?: "over",
            overSilenceSeconds = prefs[overSilenceKey] ?: 10,
            translationLanguage = prefs[translationLanguageKey],
            translationPhoneSpeaker = prefs[translationSpeakerKey] ?: false,
        ).normalized()
    }

    suspend fun jobCursor(backendUrl: String): Long =
        store.data.first()[longPreferencesKey("cursor:$backendUrl")] ?: 0L

    suspend fun saveJobCursor(backendUrl: String, value: Long) {
        store.edit { it[longPreferencesKey("cursor:$backendUrl")] = value }
    }

    suspend fun save(settings: ConversationSettings) {
        val value = settings.normalized()
        store.edit {
            it[idleKey] = value.idleMinutes
            it[modeKey] = value.mode.wireName
            it[silenceKey] = value.silenceMs
            it[keywordKey] = value.keyword
            it[overSilenceKey] = value.overSilenceSeconds
            value.translationLanguage?.let { language -> it[translationLanguageKey] = language }
                ?: it.remove(translationLanguageKey)
            it[translationSpeakerKey] = value.translationPhoneSpeaker
            it.remove(legacySafetyKey)
        }
    }
}
