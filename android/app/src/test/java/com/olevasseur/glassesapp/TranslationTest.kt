package com.olevasseur.glassesapp

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TranslationTest {
    @Test fun tokenIncludesPersonaWithoutChangingVadMode() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val backend = BackendClient(OkHttpClient(), server.url("/").toString(), "test")
            for (mode in ConversationMode.entries) {
                server.enqueue(MockResponse().setBody("""{"token":"t","model":"m","expires_at":"e"}"""))
                backend.token(mode, 3250, "es")
                val body = JSONObject(server.takeRequest().body.readUtf8())
                assertEquals(mode.wireName, body.getString("mode"))
                if (mode == ConversationMode.AUTO) assertEquals(3250, body.getInt("silence_ms"))
                else assertFalse(body.has("silence_ms"))
                assertEquals("translator", body.getString("persona"))
                assertEquals("es", body.getString("language"))
            }
        }
    }

    @Test fun routesOnlyAfterCompleteTranscriptEvenWhenAudioArrivesFirst() {
        val buffer = TranslationBuffer()
        assertNull(buffer.accept(ServerEvent(audio = listOf(byteArrayOf(1, 2))), "es"))
        assertNull(buffer.accept(ServerEvent(outputText = "Spa"), "es"))
        assertNull(buffer.accept(ServerEvent(outputText = "nish: Hola."), "es"))
        val utterance = buffer.accept(ServerEvent(turnComplete = true), "es")!!
        assertTrue(utterance.phone)
        assertArrayEquals(byteArrayOf(1, 2), utterance.pcm)
    }

    @Test fun transcriptBeforeAudioAndSameEventAudioArePreserved() {
        val buffer = TranslationBuffer()
        buffer.accept(ServerEvent(outputText = "Spanish. "), "es")
        val utterance = buffer.accept(ServerEvent(outputText = "Hola", audio = listOf(byteArrayOf(1, 2)), turnComplete = true), "es")!!
        assertTrue(utterance.phone)
        assertEquals(2, utterance.pcm.size)
    }

    @Test fun missingAmbiguousAndEnglishLabelsStayPrivate() {
        for (text in listOf("", "Hola", "English: Hello", "Spanishish: Hello", "English: Spanish is fun", "Spanish")) {
            val result = TranslationBuffer().accept(ServerEvent(outputText = text, turnComplete = true), "es")!!
            assertFalse(text, result.phone)
        }
    }

    @Test fun interruptionAndPersonaSwitchDiscardPendingAudioAndLabels() {
        val buffer = TranslationBuffer()
        buffer.accept(ServerEvent(outputText = "Spanish: Hola", audio = listOf(byteArrayOf(1, 2))), "es")
        assertNull(buffer.accept(ServerEvent(interrupted = true, audio = listOf(byteArrayOf(3, 4)), turnComplete = true), "es"))
        val interrupted = buffer.accept(ServerEvent(turnComplete = true), "es")!!
        assertFalse(interrupted.phone)
        assertTrue(interrupted.pcm.isEmpty())
        buffer.accept(ServerEvent(outputText = "Spanish: Hola", audio = listOf(byteArrayOf(1, 2))), "es")
        buffer.clear() // Used by session stop, persona switches and network reconnects.
        assertFalse(buffer.accept(ServerEvent(turnComplete = true), "es")!!.phone)
    }

    @Test fun nextTurnCannotInheritPreviousLanguage() {
        val buffer = TranslationBuffer()
        assertTrue(buffer.accept(ServerEvent(outputText = "Spanish: Hola", turnComplete = true), "es")!!.phone)
        assertFalse(buffer.accept(ServerEvent(outputText = "English: Hello", turnComplete = true), "es")!!.phone)
    }

    @Test fun callPauseDiscardsWholeTranslationIncludingAudioAfterResume() {
        val buffer = TranslationBuffer()
        buffer.accept(ServerEvent(outputText = "Spanish: Hola", audio = listOf(byteArrayOf(1, 2))), "es")
        buffer.discardTurn() // An active reply is paused by the service.
        assertNull(buffer.accept(ServerEvent(audio = listOf(byteArrayOf(3, 4)), turnComplete = true), "es"))
        val next = buffer.accept(ServerEvent(outputText = "English: Hello", audio = listOf(byteArrayOf(5, 6)), turnComplete = true), "es")!!
        assertFalse(next.phone)
        assertArrayEquals(byteArrayOf(5, 6), next.pcm)
        // A reply that begins while paused must also be discarded through its boundary.
        assertNull(buffer.accept(ServerEvent(outputText = "Spanish: Hola"), "es", suspended = true))
        assertNull(buffer.accept(ServerEvent(audio = listOf(byteArrayOf(7, 8)), turnComplete = true), "es"))
        assertTrue(buffer.accept(ServerEvent(outputText = "Spanish: Gracias", turnComplete = true), "es")!!.phone)
    }

    @Test fun malformedOrUnboundedAudioFailsInsteadOfLeakingToSpeaker() {
        for (pcm in listOf(byteArrayOf(1), ByteArray(1_920_002))) {
            try { TranslationBuffer().accept(ServerEvent(audio = listOf(pcm)), "es"); fail("Accepted bad audio") }
            catch (_: IllegalStateException) { }
        }
    }

    @Test fun validatesModeSwitchResponsesAndNormalModeOmitsPersona() {
        val start = FunctionCall("1", "start_translation", JSONObject())
        assertEquals("es", Translation.switchedLanguage(start, JSONObject("""{"status":"ok","language":"es"}""")))
        assertNull(Translation.switchedLanguage(start.copy(name = "stop_translation"), JSONObject("""{"status":"ok"}""")))
        assertFalse(Translation.tokenRequest(ConversationMode.AUTO, 2000, null).has("persona"))
        try { Translation.tokenRequest(ConversationMode.AUTO, 2000, "injected"); fail("Accepted unknown language") }
        catch (_: IllegalArgumentException) { }
    }
}
