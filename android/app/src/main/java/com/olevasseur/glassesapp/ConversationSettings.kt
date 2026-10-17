package com.olevasseur.glassesapp

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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
    val workoutSensitivity: Float = 0.5f,
    val coachNodNavigation: Boolean = true,
    val meetingPhone: Boolean = true,
    val meetingWakePhrase: String = "hey glasses",
    val meetingOver: Boolean = false,
    val thinkingCue: Boolean = false,
    val silentPageCapture: Boolean = false,
    val pageCaptureDoc: String = "reading notes",
) {
    fun normalized() = copy(
        pageCaptureDoc = pageCaptureDoc.filterNot { it.isISOControl() }.trim().take(60).ifBlank { "reading notes" },
        meetingWakePhrase = meetingWakePhrase.take(80).ifBlank { "hey glasses" },
        workoutSensitivity = if (workoutSensitivity.isFinite()) workoutSensitivity.coerceIn(0f, 1f) else 0.5f,
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
        SharedPreferencesMigration(context, "workout", setOf("sensitivity")),
    ) },
)

class ConversationSettingsStore(private val store: DataStore<Preferences>) {
    private val silentPageCaptureKey = booleanPreferencesKey("silent_page_capture")
    private val pageCaptureDocKey = stringPreferencesKey("page_capture_doc")
    private val thinkingCueKey = booleanPreferencesKey("thinking_cue")
    private val meetingPhoneKey = booleanPreferencesKey("meeting_phone")
    private val meetingWakeKey = stringPreferencesKey("meeting_wake_phrase")
    private val meetingOverKey = booleanPreferencesKey("meeting_over")
    private val workoutKey = floatPreferencesKey("workout_sensitivity")
    private val coachNavigationKey = booleanPreferencesKey("coach_nod_navigation")
    private val legacyWorkoutKey = floatPreferencesKey("sensitivity")
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
            it[legacyWorkoutKey]?.let { sensitivity ->
                if (workoutKey !in it) it[workoutKey] = sensitivity
                it.remove(legacyWorkoutKey)
            }
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
            workoutSensitivity = prefs[workoutKey] ?: 0.5f,
            coachNodNavigation = prefs[coachNavigationKey] ?: true,
            meetingPhone = prefs[meetingPhoneKey] ?: true,
            meetingWakePhrase = prefs[meetingWakeKey] ?: "hey glasses",
            meetingOver = prefs[meetingOverKey] ?: false,
            thinkingCue = prefs[thinkingCueKey] ?: false,
            silentPageCapture = prefs[silentPageCaptureKey] ?: false,
            pageCaptureDoc = prefs[pageCaptureDocKey] ?: "reading notes",
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
            it[meetingPhoneKey] = value.meetingPhone
            it[meetingWakeKey] = value.meetingWakePhrase
            it[meetingOverKey] = value.meetingOver
            it[thinkingCueKey] = value.thinkingCue
            it[silentPageCaptureKey] = value.silentPageCapture
            it[pageCaptureDocKey] = value.pageCaptureDoc
            it[workoutKey] = value.workoutSensitivity
            it[coachNavigationKey] = value.coachNodNavigation
            it.remove(legacyWorkoutKey)
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
