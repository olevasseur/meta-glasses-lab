package com.olevasseur.glassesapp.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.olevasseur.glassesapp.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(state: ConversationState, model: ConversationViewModel, backend: BackendClient,
                     permissions: () -> Unit, register: () -> Unit, microphone: () -> Unit, camera: () -> Unit,
                     finish: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var health by rememberSaveable { mutableStateOf("Not checked") }
    var checking by remember { mutableStateOf(false) }
    var testStarted by rememberSaveable { mutableStateOf(false) }
    var transcriptStart by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    BackHandler { if (step > 0) step-- else finish() }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
    if (state.visualMemory || state.visualCapturing) PrivacyIndicator(state.visualCapturing)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle("Welcome to the glasses app", "A little setup. Then go hands-free.")
        Text("Step ${step + 1} of 5", style = MaterialTheme.typography.labelLarge)
        LinearProgressIndicator(progress = { (step + 1) / 5f }, modifier = Modifier.fillMaxWidth())
        when (step) {
            0 -> SectionCard("Permissions") {
                Text("Microphone lets Gemini hear you. Nearby devices connects glasses audio. Notifications show background controls and approval requests. Phone access is optional and helps pause during calls.")
                Text("Camera access is requested separately through Meta; the app does not use your phone camera or GPS.")
                Text(if (state.permissions) "Microphone granted" else "Microphone access is required to start.")
                Button(onClick = permissions) { Text("Grant permissions") }
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) {
                    Text("Open Android app settings")
                }
            }
            1 -> SectionCard("Meta AI Developer Mode") {
                Text("1. Pair your glasses in the Meta AI app.\n\n2. Open Settings → App Info.\n\n3. Tap the app version five times, then enable Developer Mode.\n\n4. Return here with your glasses powered on, unfolded and worn.")
                Text("Only one developer app can be registered at a time. Phone-only testing can skip this step.")
            }
            2 -> SectionCard("Register your glasses") {
                Text(state.glasses)
                Button(onClick = register, enabled = !state.handsFree.armed) { Text("Connect glasses") }
                TextButton(onClick = microphone, enabled = !state.handsFree.armed) { Text("Grant DAT microphone permission") }
                TextButton(onClick = camera, enabled = !state.handsFree.armed) { Text("Grant DAT camera permission") }
                Text("Accept Meta's registration screen and return here. You can also continue using the phone microphone.")
            }
            3 -> SectionCard("Check your backend") {
                Text(BuildConfig.BACKEND_URL)
                Text("For USB development, keep the backend running and forward its port with adb reverse. The check verifies connectivity and app authentication.")
                Text(health)
                Button(onClick = {
                    checking = true
                    scope.launch {
                        try { backend.diagnosticHealth(); health = "Connected · authentication accepted" }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { health = "Could not connect. Check the backend address, shared secret and USB forwarding." }
                        finally { checking = false }
                    }
                }, enabled = !checking) { Text(if (checking) "Checking…" else "Check connection") }
            }
            4 -> SectionCard("Say hello") {
                Text("Tap Start test and say “Hello”. Listen for a reply. For PTT hold the button in Talk; for Over finish with your keyword.")
                Text("Gemini: ${state.gemini}\nAudio: ${state.route}")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (testStarted && state.transcript.drop(transcriptStart).any { it.speaker != "You" })
                    Text("Reply received. Confirm you heard it through the expected speaker.")
                Button(onClick = {
                    if (state.handsFree.conversation) model.stop()
                    else { transcriptStart = state.transcript.size; testStarted = true; model.start() }
                }, enabled = state.permissions && state.settingsLoaded && !state.stopping) {
                    Text(if (state.handsFree.conversation) "Stop test" else "Start test")
                }
                Text("Finish setup to use the full Talk controls. A running conversation continues when you leave setup.")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) OutlinedButton(onClick = { step-- }) { Text("Back") }
            Button(onClick = { if (step == 4) finish() else step++ }) { Text(if (step == 4) "Finish setup" else "Continue") }
        }
        TextButton(onClick = finish, modifier = Modifier.testTag(UiTags.SETUP_LATER)) { Text("Set up later") }
    }
    }
}
