package com.olevasseur.glassesapp

import java.io.File
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeetingCaptureTest {
    @Test fun cutsAtSilenceWithExactOverlapAndRecoversUnfinishedTail() = runBlocking {
        val dir = Files.createTempDirectory("meeting-chunks").toFile()
        try {
            val capture = MeetingCapture(dir) { fail(it) }
            capture.begin("Test", "")
            // Deterministic ramp identifies exact overlap bytes, quiet enough for silence cut.
            val frame = ByteArray(640) { (it % 2).toByte() }
            repeat(3000) { capture.audio(frame) }
            val folder = dir.listFiles()!!.single()
            val wav = folder.listFiles()!!.single { it.extension == "wav" }.readBytes()
            assertEquals(60 * 32000 + 44, wav.size)
            assertEquals(16000, ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int)
            val raw = folder.listFiles()!!.single { it.extension == "pcm" }
            assertEquals("1_58000.pcm", raw.name)
            assertArrayEquals(wav.takeLast(64000).toByteArray(), raw.readBytes())
            repeat(150) { capture.audio(frame) }
            // Reconstructing after process death seals raw PCM and marks the manifest ended.
            MeetingCapture(dir) { fail(it) }
            assertEquals(2, folder.listFiles()!!.count { it.extension == "wav" })
            assertTrue(JSONObject(File(folder, "meeting.json").readText()).getBoolean("ending"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun continuousSpeechCutsAtHardCapAndKeepsFinalPartial() {
        val dir = Files.createTempDirectory("meeting-speech").toFile()
        try {
            val capture = MeetingCapture(dir) { fail(it) }
            capture.begin("Speech", "")
            val frame = ByteBuffer.allocate(640).order(ByteOrder.LITTLE_ENDIAN).apply {
                repeat(320) { putShort((5000 * kotlin.math.sin(it * 2 * Math.PI / 100)).toInt().toShort()) }
            }.array()
            repeat(3100) { capture.audio(frame) }
            val folder = dir.listFiles()!!.single()
            assertEquals(MeetingCapture.MAX + 44L, folder.listFiles()!!.single { it.extension == "wav" }.length())
            capture.stop()
            assertEquals(1, folder.listFiles()!!.count { it.extension == "wav" })
            assertEquals(1, JSONObject(File(folder, "meeting.json").readText()).getInt("count"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun offlineAndLostAcknowledgementRetrySameBytesBeforeEnding() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dir = Files.createTempDirectory("meeting-offline").toFile()
        try {
            val errors = mutableListOf<String>()
            val capture = MeetingCapture(dir, errors::add)
            capture.begin("Pilot", "Inbox")
            capture.audio(ByteArray(32000)); capture.stop()
            val seen = mutableListOf<ByteArray>()
            var ends = 0
            var fail = true
            suspend fun flush() = capture.flush(
                register = { _, metadata -> assertTrue(metadata.getBoolean("chunked")) },
                upload = { _, index, start, file ->
                    assertEquals(0, index); assertEquals(0L, start)
                    seen += file.readBytes()
                    if (fail) throw java.io.IOException("lost response")
                }, process = {}, end = { _, count ->
                    assertEquals(1, count); ends++
                    JSONObject().put("status", "ok")
                })
            flush(); assertEquals(0, ends); assertEquals(1, errors.size)
            fail = false
            flush()
            assertArrayEquals(seen[0], seen[1]); assertEquals(1, ends)
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { Dispatchers.resetMain(); dir.deleteRecursively() }
    }

    @Test fun twoHourCaptureHasBoundedChunkSizesAndNoOverlapOnlyFinalChunk() {
        val dir = Files.createTempDirectory("meeting-long").toFile()
        try {
            val capture = MeetingCapture(dir) { fail(it) }
            capture.begin("Long", "")
            repeat(7200) { capture.audio(ByteArray(32000)) }
            capture.stop()
            val folder = dir.listFiles()!!.single()
            val files = folder.listFiles()!!.filter { it.extension == "wav" }
            assertTrue(files.size in 124..125)
            assertTrue(files.all { it.length() <= MeetingCapture.MAX + 44 })
            assertEquals(7200L * 32000 + (files.size - 1) * 64000L, files.sumOf { it.length() - 44 })
            assertEquals(files.size, JSONObject(File(folder, "meeting.json").readText()).getInt("count"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun wakeHandoffClosesAfterPlaybackOrTimeoutAndNeverOnRoomSpeech() = runTest {
        var opens = 0; var closes = 0; var listening = true
        val requests = mutableListOf<String>()
        val played = CompletableDeferred<Unit>()
        val assistant = MeetingAssistant(this, { opens++ }, { closes++ }, { requests.add(it) }, { played.await() }, { listening = it })
        assistant.recognized("we should stop the meeting", true, "hey glasses")
        assistant.recognized("hey glasses what is the budget", false, "hey glasses")
        assertEquals(0, opens)
        assistant.recognized("Hey glasses, what is the budget?", true, "hey glasses")
        assertEquals(1, opens); assertFalse(listening)
        assistant.ready(); assistant.ready()
        assertEquals(listOf("what is the budget?"), requests)
        assistant.turnEnded(); runCurrent(); assertEquals(0, closes)
        played.complete(Unit); advanceTimeBy(101); runCurrent()
        assertEquals(1, closes); assertTrue(listening)
        assistant.begin() // Capture button shares the same short exchange.
        advanceTimeBy(60001); runCurrent()
        assertEquals(2, closes); assertFalse(assistant.active)
        assistant.recognized("okay helper stop meeting", true, "okay helper")
        assertEquals(3, opens)
        assistant.stop(false)
    }
}
