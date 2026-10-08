package com.olevasseur.glassesapp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

enum class ConversationMode(val wireName: String, val label: String) {
    AUTO("auto", "Auto"), PTT("ptt", "Push-to-talk"), OVER("over", "Over"),
}

data class ConversationSettings(
    val mode: ConversationMode = ConversationMode.AUTO,
    val silenceMs: Int = 2000,
    val keyword: String = "over",
    val safetySeconds: Int = 45,
) {
    fun normalized() = copy(
        silenceMs = silenceMs.coerceIn(500, 5000),
        keyword = keyword.takeIf { it in KEYWORDS } ?: "over",
        safetySeconds = safetySeconds.coerceIn(10, 120),
    )

    companion object { val KEYWORDS = listOf("over", "go ahead", "your turn") }
}

val Context.conversationSettingsStore by preferencesDataStore(name = "conversation_settings")

class ConversationSettingsStore(private val store: DataStore<Preferences>) {
    private val modeKey = stringPreferencesKey("mode")
    private val silenceKey = intPreferencesKey("silence_ms")
    private val keywordKey = stringPreferencesKey("over_keyword")
    private val safetyKey = intPreferencesKey("over_safety_seconds")

    val settings = store.data.map { prefs ->
        ConversationSettings(
            mode = ConversationMode.entries.firstOrNull { it.wireName == prefs[modeKey] } ?: ConversationMode.AUTO,
            silenceMs = prefs[silenceKey] ?: 2000,
            keyword = prefs[keywordKey] ?: "over",
            safetySeconds = prefs[safetyKey] ?: 45,
        ).normalized()
    }

    suspend fun save(settings: ConversationSettings) {
        val value = settings.normalized()
        store.edit {
            it[modeKey] = value.mode.wireName
            it[silenceKey] = value.silenceMs
            it[keywordKey] = value.keyword
            it[safetyKey] = value.safetySeconds
        }
    }
}
