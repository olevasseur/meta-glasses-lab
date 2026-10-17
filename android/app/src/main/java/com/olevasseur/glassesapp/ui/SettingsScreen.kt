package com.olevasseur.glassesapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.app.Activity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.olevasseur.glassesapp.*
import kotlin.math.roundToInt

@Composable
fun LabeledSwitch(label: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun SettingsScreen(state: ConversationState, model: ConversationViewModel, permissions: () -> Unit, register: () -> Unit,
                   microphone: () -> Unit, camera: () -> Unit, pinShortcut: () -> Unit, setup: () -> Unit, diagnostics: () -> Unit) {
    val gestures by model.gestures.state.collectAsState()
    val enabled = !state.running && !state.stopping && state.settingsLoaded
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ScreenTitle("Settings", "Tune once. Keep your phone in your pocket.")
        OutlinedButton(onClick = diagnostics, modifier = Modifier.testTag("open_diagnostics")) { Text("Diagnostics") }
        SectionCard("Conversation") {
            Text("Stop the conversation to change turn settings.")
            Text("Auto patience: ${state.settings.silenceMs} ms")
            Slider(state.settings.silenceMs.toFloat(), { model.setSettings(state.settings.copy(silenceMs = it.roundToInt())) },
                valueRange = 500f..5000f, steps = 17, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "Auto patience in milliseconds" })
            LabeledSwitch("Thinking sound for slow tools", state.settings.thinkingCue, enabled) {
                model.setSettings(state.settings.copy(thinkingCue = it))
            }
            Text("Over keyword")
            // A column also accommodates large accessibility fonts without clipped choices.
            ConversationSettings.KEYWORDS.forEach { keyword ->
                FilterChip(selected = state.settings.keyword == keyword,
                    onClick = { model.setSettings(state.settings.copy(keyword = keyword)) }, enabled = enabled,
                    label = { Text(keyword) }, modifier = Modifier.heightIn(min = 48.dp))
            }
            Text("Over silence: ${state.settings.overSilenceSeconds} seconds")
            Slider(state.settings.overSilenceSeconds.toFloat(), { model.setSettings(state.settings.copy(overSilenceSeconds = it.roundToInt())) },
                valueRange = 5f..30f, steps = 24, enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "Over silence timeout in seconds" })
            Text("No speaking time limit in Over. Say “over and out” to end the session.")
            Text("Stop after ${state.idleMinutes} idle minutes")
            Slider(state.idleMinutes.toFloat(), { model.setIdleMinutes(it.toInt()) }, valueRange = 1f..60f, steps = 58,
                enabled = state.settingsLoaded, modifier = Modifier.semantics { contentDescription = "Idle timeout in minutes" })
        }
        SilentPageSettings(state, model)
        SectionCard("Glasses & gestures") {
            Text(state.glasses)
            Text("Audio: ${state.route}", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = register, enabled = !state.handsFree.armed) { Text("Connect glasses") }
            val activity = LocalContext.current as? Activity
            TextButton(onClick = { activity?.let(model.glasses::unregister) }, enabled = !state.handsFree.armed) { Text("Disconnect from Meta AI (then Connect again)") }
            OutlinedButton(onClick = permissions, enabled = !state.handsFree.armed) { Text("Permissions") }
            TextButton(onClick = microphone, enabled = !state.handsFree.armed) { Text("Grant DAT microphone permission") }
            TextButton(onClick = camera, enabled = !state.handsFree.armed) { Text("Grant DAT camera permission") }
            GestureControls(gestures, model::setGestures, model.gestures::setSensitivity)
            if (state.handsFree.armed) OutlinedButton(onClick = model::stop) { Text("Stop service and glasses controls") }
            else OutlinedButton(onClick = model::arm, enabled = state.permissions && state.settingsLoaded) { Text("Enable glasses controls") }
            Text(state.inputs, style = MaterialTheme.typography.bodySmall)
            Text("Capture HOLD starts/stops. Double-tap mutes Auto or toggles PTT speech. On-screen PTT supports hold/release; glasses PTT ends after 60 seconds.")
            TextButton(onClick = pinShortcut) { Text("Add Start shortcut to home screen") }
        }
        SectionCard("Backend & setup") {
            OutlinedTextField(BuildConfig.BACKEND_URL, {}, readOnly = true, label = { Text("Backend URL") }, modifier = Modifier.fillMaxWidth())
            Text("Configured in android/local.properties (BACKEND_URL). Rebuild after changing it; the same server owns the conversation, approvals and saved data.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = setup) { Text("Run setup again") }
        }
        if (BuildConfig.DEBUG) SectionCard("Developer tools") {
            LabeledSwitch("Mock glasses (phone audio)", state.mock, !state.handsFree.armed && !state.running && !state.stopping, model::setMock)
            if (state.mock) {
                Text("Vision uses a sample blue mug and orange.")
                OutlinedButton(onClick = model.glasses::mockHold, enabled = state.handsFree.armed) { Text("Simulate capture hold") }
                OutlinedButton(onClick = model.glasses::mockDoubleTap, enabled = state.connected && state.handsFree.shouldRun) { Text("Simulate double-tap") }
            }
        }
    }
}

@Composable
fun SilentPageSettings(state: ConversationState, model: ConversationViewModel) {
    val enabled = !state.running && !state.stopping && state.settingsLoaded
    var nickname by remember(state.settings.pageCaptureDoc) { mutableStateOf(state.settings.pageCaptureDoc) }
    SectionCard("Silent page capture") {
        LabeledSwitch("Silent page capture", state.settings.silentPageCapture, enabled) {
            model.setSettings(state.settings.copy(silentPageCapture = it))
        }
        OutlinedTextField(nickname, { nickname = it.take(60) }, enabled = enabled, singleLine = true,
            label = { Text("Target doc nickname") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { model.setSettings(state.settings.copy(pageCaptureDoc = nickname)) }, enabled = enabled) {
            Text("Save nickname")
        }
        Text("Create this Google Doc nickname first. Enable glasses controls, then short-press capture to save highlights and handwritten notes. Audio cues only; no conversation needed.")
    }
}
