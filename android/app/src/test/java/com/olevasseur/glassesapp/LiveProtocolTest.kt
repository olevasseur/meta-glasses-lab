package com.olevasseur.glassesapp

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LiveProtocolTest {
    @Test fun setupUsesBackendModelAndDoesNotOverrideLockedConfig() {
        val message = JSONObject(LiveProtocol.setup("gemini-3.8-live"))
        assertEquals(1, message.length())
        val setup = message.getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setup.getString("model"))
        assertEquals(2, setup.length())
        assertEquals(0, setup.getJSONObject("sessionResumption").length())
        assertTrue(LiveProtocol.ENDPOINT.endsWith("v1beta.GenerativeService.BidiGenerateContentConstrained"))
    }
    @Test fun resumptionIsNestedInsideSetupAndEscaped() {
        val setup = JSONObject(LiveProtocol.setup("models/gemini-3.8-live", "handle\"\\x")).getJSONObject("setup")
        assertEquals("models/gemini-3.8-live", setup.getString("model"))
        assertEquals("handle\"\\x", setup.getJSONObject("sessionResumption").getString("handle"))
    }
    @Test fun sendsAudioAsBase64Blob() {
        val pcm = byteArrayOf(0, -128, -1, 127)
        val blob = JSONObject(LiveProtocol.audio(pcm)).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", blob.getString("mimeType"))
        assertArrayEquals(pcm, Base64.getDecoder().decode(blob.getString("data")))
    }
    @Test fun manualActivityMessagesHaveEmptyObjects() {
        for ((start, key) in listOf(true to "activityStart", false to "activityEnd")) {
            val realtime = JSONObject(LiveProtocol.activity(start)).getJSONObject("realtimeInput")
            assertEquals(1, realtime.length())
            assertEquals(0, realtime.getJSONObject(key).length())
        }
    }
    @Test fun receivesEveryPartAndBothTranscriptsInOneEvent() {
        val event = LiveProtocol.parse("""{"serverContent":{"modelTurn":{"parts":[
            {"text":"ignored when audio transcription is enabled"},
            {"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQI="}},
            {"inlineData":{"mimeType":"image/png","data":"AA=="}},
            {"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AwQ="}}]},
            "inputTranscription":{"text":"hello"},"outputTranscription":{"text":"hi"},"turnComplete":true},
            "usageMetadata":{"totalTokenCount":42}}""")
        assertEquals(2, event.audio.size)
        assertArrayEquals(byteArrayOf(1, 2), event.audio[0])
        assertArrayEquals(byteArrayOf(3, 4), event.audio[1])
        assertEquals("hello", event.inputText)
        assertEquals("hi", event.outputText)
        assertTrue(event.turnComplete)
    }
    @Test fun parsesInterruptionsAndSetupComplete() {
        assertTrue(LiveProtocol.parse("""{"serverContent":{"interrupted":true}}""").interrupted)
        assertTrue(LiveProtocol.parse("""{"setupComplete":{}}""").setupComplete)
    }
    @Test fun toolCallsAndResponsesPreserveIdsNamesAndNestedArguments() {
        val calls = LiveProtocol.parse("""{"toolCall":{"functionCalls":[
            {"id":"a","name":"append_to_doc","args":{"text":"test","nested":{"count":2}}},
            {"id":"b","name":"recall","args":{}}]}}""").calls
        assertEquals(2, calls.size)
        assertEquals(2, calls[0].args.getJSONObject("nested").getInt("count"))
        val response = JSONObject(LiveProtocol.toolResponse(calls[0], JSONObject("""{"status":"ok"}""")))
            .getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
        assertEquals("a", response.getString("id"))
        assertEquals("append_to_doc", response.getString("name"))
        assertEquals("ok", response.getJSONObject("response").getString("status"))
    }
    @Test fun recognizesResumptionGoAwayAndCancellation() {
        val event = LiveProtocol.parse("""{"sessionResumptionUpdate":{"newHandle":"opaque","resumable":true}}""")
        assertEquals(true, event.resumable)
        assertEquals("opaque", event.handle)
        assertNull(LiveProtocol.parse("""{"sessionResumptionUpdate":{"resumable":false,"newHandle":""}}""").handle)
        assertTrue(LiveProtocol.parse("""{"goAway":{"timeLeft":"10s"}}""").goAway)
        assertEquals(listOf("a", "b"), LiveProtocol.parse("""{"toolCallCancellation":{"ids":["a","b"]}}""").cancelledIds)
    }
    @Test fun unknownFieldsDoNotBreakReception() {
        val event = LiveProtocol.parse("""{"futureMessage":{"futureField":true}}""")
        assertTrue(event.audio.isEmpty())
        assertFalse(event.interrupted)
    }
    @Test(expected = org.json.JSONException::class) fun malformedJsonIsRejected() { LiveProtocol.parse("{oops") }
}
