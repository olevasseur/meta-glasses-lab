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
                it[intPreferencesKey("over_safety_seconds")] = 999
            }
            assertEquals(ConversationSettings(silenceMs = 500, safetySeconds = 120), settings.settings.first())
            assertEquals(5000, ConversationSettings(silenceMs = 9999).normalized().silenceMs)
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin() }
    }

    @Test fun modeKeywordPatienceAndSafetyPersistAcrossStoreRecreation() = runBlocking {
        val file = File(temporary.root, "saved.preferences_pb")
        val expected = ConversationSettings(ConversationMode.OVER, 3250, "go ahead", 65)
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
}
