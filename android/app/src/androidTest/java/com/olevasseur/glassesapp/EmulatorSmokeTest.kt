package com.olevasseur.glassesapp

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real-API suite: run only against the disposable AVD via emulator_smoke.sh. */
@RunWith(AndroidJUnit4::class)
class EmulatorSmokeTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: ConversationViewModel
    private val state get() = model.state.value

    private fun await(label: String, timeout: Long = 120_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) {
                "$label timed out: Gemini=${state.gemini}; error=${state.error}; camera=${state.camera}; Over=${state.overStatus}"
            }
            SystemClock.sleep(100)
        }
    }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun click(text: String) {
        ui.onNodeWithText(text, useUnmergedTree = true).performScrollTo().performClick()
    }
    private fun inject(name: String, transcript: String? = null) {
        SmokeHooks.inject(instrumentation.context.assets.open("smoke/$name.wav")) {
            if (transcript != null) main {
                checkNotNull(model.glasses.mockDevice).services.speech.simulateTranscription(transcript, true, 1f)
            }
        }
        await("WAV $name completed", 30_000) { !SmokeHooks.injecting }
    }
    private fun screenshot(name: String) {
        val original = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val small = Bitmap.createScaledBitmap(original, 360, original.height * 360 / original.width, true)
        val dir = File(instrumentation.targetContext.getExternalFilesDir(null), "smoke").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        small.recycle(); original.recycle()
    }
    private fun stop() {
        click("Stop")
        await("Stop", 30_000) { !state.running && !state.stopping && !state.connected }
        assertEquals("Stopped", state.gemini)
        assertEquals("No audio session", state.route)
    }
    private fun isFour(text: String) = text.contains("four", true) || Regex("\\b4\\b").containsMatchIn(text)
    private fun showLatestReply() {
        ui.onAllNodesWithText("Gemini: ", substring = true, useUnmergedTree = true)
            .onLast().performScrollTo().assertIsDisplayed()
    }
    private fun responseSince(index: Int) = state.transcript.drop(index).filter { it.speaker != "You" }.joinToString(" ") { it.text }

    @Test fun realApkDatAndGemini() {
        check(InstrumentationRegistry.getArguments().getString("realApi") == "true") {
            "Run scripts/emulator_smoke.sh: this suite requires explicit realApi=true and real backend credentials"
        }
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .putExtra("debug_mock_glasses", true).putExtra("debug_wav_audio", true)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            try {
                ui.waitUntil(60_000) {
                    scenario.onActivity { activity -> activity.smokeModel?.let { model = it } }
                    ::model.isInitialized && state.settingsLoaded && state.mock
                }
                assertTrue(state.permissions)
                ui.onNodeWithText("the glasses app").assertIsDisplayed()
                await("DAT connected") { state.glasses.contains("connected") && !state.glasses.contains("no glasses") }
                main { model.setSettings(ConversationSettings()); model.setGestures(true) }
                screenshot("01-launch")
                click("Start")
                await("Gemini setupComplete") { state.connected }
                assertTrue(state.route.startsWith("Debug WAV"))
                await("DAT Inputs active") { state.inputs.contains("ACTIVE") }
                await("DAT Motion replay") { model.gestures.state.value.live }
                main { model.glasses.mockDoubleTap() }
                await("DAT double-tap muted") { state.handsFree.muted }
                main { model.glasses.mockDoubleTap() }
                await("DAT double-tap unmuted") { !state.handsFree.muted }
                val played = SmokeHooks.playedBytes.get()
                inject("question")
                await("Gemini WAV reply") {
                    state.transcript.any { it.speaker == "You" && (it.text.contains("two", true) || it.text.contains("2")) } &&
                        isFour(responseSince(0)) && SmokeHooks.playedBytes.get() > played
                }
                ui.onAllNodesWithText("Gemini: ", substring = true, useUnmergedTree = true).onFirst().performScrollTo()
                screenshot("02-connected")
                showLatestReply()
                screenshot("03-audio-transcript")
                val beforeLook = state.transcript.size
                inject("look")
                await("look camera and description") {
                    state.lastTool.contains("look") && state.lastTool.contains("frame_sent") && (responseSince(beforeLook).contains("mug", true) || responseSince(beforeLook).contains("cup", true)) &&
                        responseSince(beforeLook).contains("orange", true)
                }
                ui.onNodeWithText("Last tool result").performScrollTo()
                screenshot("04-look")
                stop()

                main { model.setSettings(ConversationSettings(mode = ConversationMode.OVER, overSilenceSeconds = 30)) }
                click("Start")
                await("Over connected and speech listening") {
                    state.connected && state.talking && model.glasses.mockDevice?.services?.speech?.isListening == true
                }
                val beforeOver = state.transcript.size
                val beforeAudio = SmokeHooks.playedBytes.get()
                inject("question", "What is two plus two? Answer in one short sentence.")
                val quietDeadline = SystemClock.elapsedRealtime() + 4000
                while (SystemClock.elapsedRealtime() < quietDeadline) {
                    assertTrue("Over replied before sign-off", responseSince(beforeOver).isBlank())
                    assertEquals("Over played audio before sign-off", beforeAudio, SmokeHooks.playedBytes.get())
                    assertTrue("Over ended before sign-off", state.talking)
                    SystemClock.sleep(100)
                }
                ui.onNodeWithText(state.overStatus, useUnmergedTree = true).performScrollTo()
                screenshot("05-over-waiting")
                inject("over", "over")
                await("Over keyword closes turn") { !state.talking }
                assertFalse("Silence fallback must not pass keyword test", state.overStatus.contains("Silence timeout"))
                await("Over audio reply after sign-off") {
                    isFour(responseSince(beforeOver)) && SmokeHooks.playedBytes.get() > beforeAudio
                }
                showLatestReply()
                screenshot("06-over-reply")
                stop()
                screenshot("07-stopped")
                assertNull("App reported an error", state.error)
            } catch (failure: Throwable) {
                runCatching { screenshot("failure") }
                throw failure
            } finally {
                if (::model.isInitialized) main { model.stop() }
                UiDevice.getInstance(instrumentation).waitForIdle(2000)
            }
        }
    }
}
