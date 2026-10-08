package com.olevasseur.glassesapp

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConversationSettingsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun defaultsBoundsAndUnknownStoredValuesAreSafe() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(temporary.root, "defaults.preferences_pb") }
            val settings = ConversationSettingsStore(store)
            assertEquals(ConversationSettings(), settings.settings.first())
            store.edit {
                it[stringPreferencesKey("mode")] = "unknown"
                it[stringPreferencesKey("over_keyword")] = ""
                it[stringPreferencesKey("translation_language")] = "unsupported"
                it[intPreferencesKey("silence_ms")] = -1
                it[intPreferencesKey("over_silence_seconds")] = 999
            }
            assertEquals(ConversationSettings(silenceMs = 500, overSilenceSeconds = 30), settings.settings.first())
            assertEquals(5000, ConversationSettings(silenceMs = 9999).normalized().silenceMs)
            assertEquals("okay ", ConversationSettings(meetingWakePhrase = "okay ").normalized().meetingWakePhrase)
            assertEquals(10, ConversationSettings().overSilenceSeconds)
            assertEquals(5, ConversationSettings(overSilenceSeconds = -1).normalized().overSilenceSeconds)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun modeKeywordPatienceAndSilencePersistAcrossStoreRecreation() = runBlocking {
        val file = File(temporary.root, "saved.preferences_pb")
        val expected = ConversationSettings(ConversationMode.OVER, 3250, "go ahead", 25, meetingWakePhrase = "okay helper")
        val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val first = ConversationSettingsStore(PreferenceDataStoreFactory.create(scope = firstScope) { file })
        first.save(expected)
        firstScope.coroutineContext[Job]!!.cancelAndJoin()
        val nextScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val next = ConversationSettingsStore(PreferenceDataStoreFactory.create(scope = nextScope) { file })
            assertEquals(expected, next.settings.first())
        } finally { nextScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun migratesLegacySpeechLimitToTenSecondSilenceWithoutChangingOtherSettings() = runBlocking {
        for (legacy in listOf(10, 45, 120, 999)) {
            val file = File(temporary.root, "legacy-$legacy.preferences_pb")
            val firstScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val store = PreferenceDataStoreFactory.create(scope = firstScope) { file }
            val oldKey = intPreferencesKey("over_safety_seconds")
            val newKey = intPreferencesKey("over_silence_seconds")
            val expected = ConversationSettings(ConversationMode.OVER, 3250, "your turn", 10)
            try {
                store.edit {
                    it[oldKey] = legacy
                    it[stringPreferencesKey("mode")] = "over"
                    it[intPreferencesKey("silence_ms")] = 3250
                    it[stringPreferencesKey("over_keyword")] = "your turn"
                }
                assertEquals(expected, ConversationSettingsStore(store).settings.first())
                assertFalse(store.data.first().contains(oldKey))
                assertEquals(10, store.data.first()[newKey])
            } finally { firstScope.coroutineContext[Job]!!.cancelAndJoin() }
            val nextScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            try {
                val next = PreferenceDataStoreFactory.create(scope = nextScope) { file }
                assertEquals(expected, ConversationSettingsStore(next).settings.first())
                assertFalse(next.data.first().contains(oldKey))
            } finally { nextScope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }

    @Test fun migrationKeepsExplicitNewValueAndSaveRemovesLegacyKey() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(temporary.root, "mixed.preferences_pb") }
            val oldKey = intPreferencesKey("over_safety_seconds")
            store.edit {
                it[oldKey] = 45
                it[intPreferencesKey("over_silence_seconds")] = 17
            }
            val settings = ConversationSettingsStore(store)
            assertEquals(17, settings.settings.first().overSilenceSeconds)
            assertFalse(store.data.first().contains(oldKey))
            store.edit { it[oldKey] = 120 }
            settings.save(ConversationSettings(overSilenceSeconds = 30))
            assertFalse(store.data.first().contains(oldKey))
            assertEquals(30, settings.settings.first().overSilenceSeconds)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun idleAndNotificationModeChangesPreserveOverPreferencesOnDisk() = runBlocking {
        val file = File(temporary.root, "hands-free.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val expected = ConversationSettings(ConversationMode.OVER, 3250, "your turn", 25, idleMinutes = 42,
            translationLanguage = "es", translationPhoneSpeaker = true)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }
            val settings = ConversationSettingsStore(store)
            settings.save(expected)
            for (mode in listOf(ConversationMode.AUTO, ConversationMode.PTT, ConversationMode.OVER)) {
                settings.save(settings.settings.first().copy(mode = mode))
                assertEquals(expected.copy(mode = mode), settings.settings.first())
            }
            store.edit { it[intPreferencesKey("idle_minutes")] = -1 }
            assertEquals(expected.copy(idleMinutes = 1), settings.settings.first())
            settings.save(expected.copy(idleMinutes = 999))
            assertEquals(expected.copy(idleMinutes = 60), settings.settings.first())
            settings.save(expected)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        val reopenedScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val reopened = ConversationSettingsStore(PreferenceDataStoreFactory.create(scope = reopenedScope) { file })
            assertEquals(expected, reopened.settings.first())
        } finally { reopenedScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun jobCursorsSurviveSettingsChangesAndServiceRecreationPerBackend() = runBlocking {
        val file = File(temporary.root, "job-cursors.preferences_pb")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val expected = ConversationSettings(ConversationMode.OVER, 3250, "your turn", 25, idleMinutes = 42,
            translationLanguage = "fr", translationPhoneSpeaker = true, workoutSensitivity = 0.8f, coachNodNavigation = false,
            meetingPhone = false, meetingOver = true, thinkingCue = true)
        try {
            val settings = ConversationSettingsStore(PreferenceDataStoreFactory.create(scope = scope) { file })
            assertEquals(0L, settings.jobCursor("backend-a"))
            settings.save(expected)
            settings.saveJobCursor("backend-a", 41L)
            settings.saveJobCursor("backend-b", 7L)
            settings.save(expected.copy(mode = ConversationMode.PTT))
            assertEquals(41L, settings.jobCursor("backend-a"))
            assertEquals(7L, settings.jobCursor("backend-b"))
            assertEquals(expected.copy(mode = ConversationMode.PTT), settings.settings.first())
            settings.save(settings.settings.first().copy(translationLanguage = null))
            assertEquals(expected.copy(mode = ConversationMode.PTT, translationLanguage = null), settings.settings.first())
            assertEquals(41L, settings.jobCursor("backend-a"))
            settings.save(expected.copy(mode = ConversationMode.PTT))
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
        val reopenedScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val settings = ConversationSettingsStore(PreferenceDataStoreFactory.create(scope = reopenedScope) { file })
            assertEquals(41L, settings.jobCursor("backend-a"))
            assertEquals(7L, settings.jobCursor("backend-b"))
            assertEquals(0L, settings.jobCursor("backend-c"))
            assertEquals(expected.copy(mode = ConversationMode.PTT), settings.settings.first())
        } finally { reopenedScope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun workoutSensitivityMigratesAndKeepsExplicitSettings() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(temporary.root, "workout.preferences_pb") }
            val settings = ConversationSettingsStore(store)
            val legacy = floatPreferencesKey("sensitivity")
            val current = floatPreferencesKey("workout_sensitivity")
            store.edit { it[legacy] = 0.75f }
            assertEquals(0.75f, settings.settings.first().workoutSensitivity)
            assertFalse(legacy in store.data.first())
            store.edit { it[legacy] = 0.25f; it[current] = 0.8f }
            assertEquals(0.8f, settings.settings.first().workoutSensitivity)
            assertFalse(legacy in store.data.first())
            for ((input, expected) in listOf(-1f to 0f, 2f to 1f, Float.NaN to 0.5f, Float.POSITIVE_INFINITY to 0.5f)) {
                settings.save(ConversationSettings(workoutSensitivity = input))
                assertEquals(expected, settings.settings.first().workoutSensitivity)
            }
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

}
