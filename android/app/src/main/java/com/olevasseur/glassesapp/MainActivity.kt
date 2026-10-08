package com.olevasseur.glassesapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: ConversationViewModel by viewModels()
    private val androidPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        updatePermissions()
    }
    // Same DAT permission contract as CameraAccess; HFP itself uses Android RECORD_AUDIO.
    private val glassesPermission = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        if (result.getOrDefault(PermissionStatus.Denied) != PermissionStatus.Granted) {
            model.error("Glasses microphone permission denied. HFP/phone audio can still be tested.")
        }
    }
    private val cameraPermission = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        if (result.getOrDefault(PermissionStatus.Denied) != PermissionStatus.Granted) {
            model.error("Glasses camera permission denied. Grant it before asking Gemini to look.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val state by model.state.collectAsStateWithLifecycle()
                DisposableEffect(state.running) {
                    if (state.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                ConversationScreen(state, model, ::requestPermissions,
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
                    requestCameraPermission = {
                        lifecycleScope.launch {
                            if (model.glasses.initialize() && Wearables.checkPermissionStatus(Permission.CAMERA)
                                    .getOrDefault(PermissionStatus.Denied) != PermissionStatus.Granted) {
                                cameraPermission.launch(Permission.CAMERA)
                            }
                        }
                    },
                )
            }
        }
        requestPermissions()
    }

    private fun requestPermissions() {
        val requested = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) requested += Manifest.permission.BLUETOOTH_CONNECT
        androidPermissions.launch(requested.toTypedArray())
    }
    private fun updatePermissions() {
        model.permissionsGranted(ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        if (Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            model.glasses.initialize()
        }
    }
    override fun onStop() {
        if (!isChangingConfigurations) model.stop() // M1 is a foreground conversation.
        super.onStop()
    }
}

@Composable
private fun ConversationScreen(
    state: ConversationState,
    model: ConversationViewModel,
    permissions: () -> Unit,
    register: () -> Unit,
    requestGlassesPermission: () -> Unit,
    requestCameraPermission: () -> Unit,
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            if (state.visualCapturing) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text("● Visual memory capturing · say ‘pause visual memory’", Modifier.fillMaxWidth().padding(12.dp))
            }
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("the glasses app", style = MaterialTheme.typography.headlineMedium)
            Text("Glasses: ${state.glasses}")
            Text("Gemini: ${state.gemini}")
            Text("Audio: ${state.route}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = register, enabled = !state.running) { Text("Connect glasses") }
                OutlinedButton(onClick = permissions, enabled = !state.running) { Text("Permissions") }
            }
            TextButton(onClick = requestGlassesPermission, enabled = !state.running) { Text("Grant DAT microphone permission") }
            TextButton(onClick = requestCameraPermission, enabled = !state.running) { Text("Grant DAT camera permission") }
            Text("Meta AI → Settings → App Info → tap version 5× → Developer Mode", style = MaterialTheme.typography.bodySmall)
            ConversationSettingsControls(state.settings, !state.running && !state.stopping && state.settingsLoaded, model::setSettings)
            Button(onClick = { if (state.running) model.stop() else model.start() }, enabled = !state.stopping && state.permissions && state.settingsLoaded, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.stopping) "Stopping…" else if (state.running) "Stop" else "Start")
            }
            if (state.settings.mode == ConversationMode.OVER && state.running) {
                Text(state.overStatus)
            }
            if (state.ptt) {
                HoldToTalk(state.connected, state.talking, model::talk, model::finishTalking)
                if (state.talking) OutlinedButton(onClick = model::finishTalking) { Text("Finish speaking") }
                Text(state.inputs, style = MaterialTheme.typography.bodySmall)
                Text("Capture HOLD has no release event in DAT 1.0.0. Speech ends automatically after 60 seconds.", style = MaterialTheme.typography.bodySmall)
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val gestureState by model.gestures.state.collectAsStateWithLifecycle()
            GestureControls(gestureState, model::setGestures, model.gestures::setSensitivity)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = state.watchMode, onCheckedChange = model::setWatchMode, enabled = state.connected)
                Text("Watch mode · 2 minute limit", modifier = Modifier.padding(start = 8.dp))
            }
            VisualMemoryControls(state, model)
            Text("${state.camera}. Ask: What am I looking at?", style = MaterialTheme.typography.bodySmall)
            if (BuildConfig.DEBUG) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = state.mock, onCheckedChange = model::setMock, enabled = !state.running && !state.stopping)
                    Text("Mock glasses (phone audio)", modifier = Modifier.padding(start = 8.dp))
                }
                if (state.mock && state.ptt) OutlinedButton(onClick = model.glasses::mockHold, enabled = state.connected) { Text("Simulate capture hold") }
                if (state.mock) Text("Vision uses the sample image: a blue mug and an orange.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Live transcript", style = MaterialTheme.typography.titleMedium)
            if (state.transcript.isEmpty()) Text("Your conversation will appear here.")
            state.transcript.forEach { line -> Text("${line.speaker}: ${line.text}") }
            Text("Last tool result", style = MaterialTheme.typography.titleMedium)
            Text(state.lastTool)
            Text("Keep this screen open during M1 conversations.", style = MaterialTheme.typography.bodySmall)
        }
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
