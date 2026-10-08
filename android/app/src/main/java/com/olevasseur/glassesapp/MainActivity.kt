package com.olevasseur.glassesapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import com.meta.wearable.dat.core.voiceinvocations.isVoiceInvocationsIntent
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var boundService by mutableStateOf<ConversationService?>(null)
    private var binding = false
    private var pendingStart = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            boundService = (binder as ConversationService.LocalBinder).service
            boundService?.uiResumed(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
            updatePermissions()
        }
        override fun onServiceDisconnected(name: ComponentName) { boundService = null }
    }
    private val androidPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        updatePermissions()
    }
    // Same DAT permission contract as CameraAccess; HFP itself uses Android RECORD_AUDIO.
    private val glassesPermission = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        if (result.getOrDefault(PermissionStatus.Denied) != PermissionStatus.Granted) {
            boundService?.model?.error("Glasses microphone permission denied. HFP/phone audio can still be tested.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingStart = savedInstanceState?.getBoolean("pending_start")
            ?: (isVoiceInvocationsIntent(intent) || intent.action == ConversationService.START)
        binding = bindService(Intent(this, ConversationService::class.java), connection, BIND_AUTO_CREATE)
        setContent {
            MaterialTheme {
                val model = boundService?.model
                if (model == null) { Text("Connecting to conversation service…"); return@MaterialTheme }
                val state by model.state.collectAsStateWithLifecycle()
                ConversationScreen(state, model, ::requestPermissions,
                    pinShortcut = {
                        getSystemService(ShortcutManager::class.java).requestPinShortcut(
                            ShortcutInfo.Builder(this, "conversation").build(), null)
                    },
                    register = { model.glasses.register(this) },
                    requestGlassesPermission = {
                        lifecycleScope.launch {
                            if (model.glasses.initialize()) {
                                val status = Wearables.checkPermissionStatus(Permission.MICROPHONE)
                                if (status.getOrDefault(PermissionStatus.Denied) != PermissionStatus.Granted) {
                                    glassesPermission.launch(Permission.MICROPHONE)
                                }
                            }
                        }
                    },
                )
            }
        }
        requestPermissions()
    }

    private fun requestPermissions() {
        val requested = mutableListOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 31) requested += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
        val missing = requested.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) updatePermissions() else androidPermissions.launch(missing.toTypedArray())
    }
    private fun updatePermissions() {
        boundService?.permissionsChanged()
        launchWhenReady()
    }
    private fun launchWhenReady() {
        if (pendingStart && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && boundService?.model?.state?.value?.permissions == true) {
            pendingStart = false
            boundService?.startConversation()
        }
    }
    override fun onResume() {
        super.onResume()
        boundService?.uiResumed(true)
        updatePermissions()
    }
    override fun onPause() { boundService?.uiResumed(false); super.onPause() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isVoiceInvocationsIntent(intent) || intent.action == ConversationService.START) pendingStart = true
        launchWhenReady()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_start", pendingStart)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (binding) unbindService(connection)
        boundService = null
        super.onDestroy()
    }
}

@Composable
private fun ConversationScreen(
    state: ConversationState,
    model: ConversationViewModel,
    permissions: () -> Unit,
    pinShortcut: () -> Unit,
    register: () -> Unit,
    requestGlassesPermission: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("the glasses app", style = MaterialTheme.typography.headlineMedium)
            Text("Glasses: ${state.glasses}")
            Text("Gemini: ${state.gemini}")
            Text("Audio: ${state.route}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = register, enabled = !state.handsFree.armed) { Text("Connect glasses") }
                OutlinedButton(onClick = permissions, enabled = !state.handsFree.armed) { Text("Permissions") }
            }
            TextButton(onClick = requestGlassesPermission, enabled = !state.handsFree.armed) { Text("Grant DAT microphone permission") }
            Text("Meta AI → Settings → App Info → tap version 5× → Developer Mode", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !state.handsFree.ptt, onClick = { model.setMode(false) }, label = { Text("Auto") })
                FilterChip(selected = state.handsFree.ptt, onClick = { model.setMode(true) }, label = { Text("Push-to-talk") })
            }
            Text(if (state.ptt) "Hold on screen to speak; double-tap the glasses touchpad to start/finish speech."
                else "Speak naturally. Gemini uses the backend’s patient voice detection.", style = MaterialTheme.typography.bodyMedium)
            Text("Capture HOLD starts/stops a conversation. Double-tap mutes Auto or toggles speech in PTT.")
            if (state.handsFree.pauses.isNotEmpty() && state.handsFree.conversation) Text("Paused: ${state.handsFree.pauses.joinToString()}")
            Button(onClick = { if (state.handsFree.conversation && state.handsFree.pauses.isEmpty()) model.stop() else model.start() }, enabled = !state.stopping && state.permissions, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.stopping) "Stopping…" else if (state.handsFree.conversation && state.handsFree.pauses.isNotEmpty()) "Resume" else if (state.handsFree.conversation) "Stop" else "Start")
            }
            if (state.handsFree.armed) {
                OutlinedButton(onClick = model::stop) { Text("Stop service and glasses controls") }
            } else {
                OutlinedButton(onClick = model::arm, enabled = state.permissions) { Text("Enable glasses controls") }
            }
            Text(state.inputs, style = MaterialTheme.typography.bodySmall)
            if (state.running && !state.ptt) OutlinedButton(onClick = model::toggleInput) {
                Text(if (state.handsFree.muted) "Unmute microphone" else "Mute microphone")
            }
            if (state.ptt) {
                HoldToTalk(state.connected && state.handsFree.shouldRun, state.talking, model::talk, model::finishTalking)
                if (state.talking) OutlinedButton(onClick = model::finishTalking) { Text("Finish speaking") }
                Text(state.inputs, style = MaterialTheme.typography.bodySmall)
                Text("Double-tap has no release event. PTT speech ends automatically after 60 seconds.", style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (BuildConfig.DEBUG) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = state.mock, onCheckedChange = model::setMock, enabled = !state.handsFree.armed && !state.stopping)
                    Text("Mock glasses (phone audio)", modifier = Modifier.padding(start = 8.dp))
                }
                if (state.mock) {
                    OutlinedButton(onClick = model.glasses::mockHold, enabled = state.handsFree.armed) { Text("Simulate capture hold") }
                    OutlinedButton(onClick = model.glasses::mockDoubleTap, enabled = state.connected) { Text("Simulate double-tap") }
                }
            }
            Text("Live transcript", style = MaterialTheme.typography.titleMedium)
            if (state.transcript.isEmpty()) Text("Your conversation will appear here.")
            state.transcript.forEach { line -> Text("${line.speaker}: ${line.text}") }
            Text("Last tool result", style = MaterialTheme.typography.titleMedium)
            Text(state.lastTool)
            Text("Continues with the screen off. Switching Auto/PTT starts a new conversation.", style = MaterialTheme.typography.bodySmall)
            Text("Stop after ${state.idleMinutes} minutes without transcript or control activity")
            Slider(value = state.idleMinutes.toFloat(), onValueChange = { model.setIdleMinutes(it.toInt()) },
                valueRange = 1f..60f, steps = 58)
            TextButton(onClick = pinShortcut) { Text("Add Start shortcut to home screen") }
        }
    }
}

@Composable
private fun HoldToTalk(enabled: Boolean, talking: Boolean, onHold: (Boolean) -> Unit, onFinish: () -> Unit) {
    val currentHold by rememberUpdatedState(onHold)
    Box(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .background(if (talking) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.medium)
            .semantics {
                role = Role.Button
                onClick(if (talking) "Finish speaking" else "Start speaking") {
                    if (enabled) { if (talking) onFinish() else currentHold(true); true } else false
                }
            }
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onPress = {
                    currentHold(true)
                    try { awaitRelease() } finally { currentHold(false) }
                })
            },
        contentAlignment = Alignment.Center,
    ) { Text(if (!enabled) "Connect to talk" else if (talking) "Listening…" else "Hold to talk") }
}
