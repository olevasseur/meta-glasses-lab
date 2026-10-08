package com.olevasseur.glassesapp

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
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
                it[intPreferencesKey("silence_ms")] = -1
                it[intPreferencesKey("over_silence_seconds")] = 999
            }
            assertEquals(ConversationSettings(silenceMs = 500, overSilenceSeconds = 30), settings.settings.first())
            assertEquals(5000, ConversationSettings(silenceMs = 9999).normalized().silenceMs)
            assertEquals(10, ConversationSettings().overSilenceSeconds)
            assertEquals(5, ConversationSettings(overSilenceSeconds = -1).normalized().overSilenceSeconds)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun modeKeywordPatienceAndSilencePersistAcrossStoreRecreation() = runBlocking {
        val file = File(temporary.root, "saved.preferences_pb")
        val expected = ConversationSettings(ConversationMode.OVER, 3250, "go ahead", 25)
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
        val expected = ConversationSettings(ConversationMode.OVER, 3250, "your turn", 25, idleMinutes = 42)
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

}
