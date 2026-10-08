package com.olevasseur.glassesapp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.sin

internal fun observeDiagnostics(scope: CoroutineScope, states: StateFlow<ConversationState>, backend: BackendClient) {
    scope.launch { DiagnosticLog.uploadLoop(backend) }
    scope.launch {
        var previous = emptyMap<String, String>()
        states.collect { s ->
            val values = mapOf("gemini" to s.gemini, "audio" to s.route,
                "dat" to "${s.glasses} · ${s.inputs}", "over" to s.overStatus,
                "vision" to "${s.camera} watch=${s.watchMode}",
                "visual-memory" to "enabled=${s.visualMemory} capturing=${s.visualCapturing}",
                "meeting" to "active=${s.meeting}", "translator" to "active=${s.translationLanguage != null}",
                "settings" to "mode=${s.settings.mode} mock=${s.mock} patience_ms=${s.settings.silenceMs}",
                "hands-free" to "running=${s.running} paused=${!s.handsFree.shouldRun}",
                "permissions" to "microphone=${s.permissions}")
            values.forEach { (feature, value) -> if (previous[feature] != value) DiagnosticLog.add(feature, value) }
            // Error text can contain user text from a tool. Log occurrence, show details only in existing UI.
            if (s.error != null && previous["error"] != s.error) DiagnosticLog.add("app", "error_shown_on_phone", "ERROR")
            previous = values + ("error" to (s.error ?: ""))
        }
    }
}

internal fun diagnosticBeep(): ByteArray = ByteArray(12_000).also { pcm ->
    repeat(pcm.size / 2) { i ->
        val sample = (sin(2 * PI * 660 * i / 24000) * 3000 * minOf(1.0, i / 240.0, (5999 - i) / 240.0)).toInt()
        pcm[i * 2] = sample.toByte(); pcm[i * 2 + 1] = (sample shr 8).toByte()
    }
}

internal fun diagnosticPlayback(pcm: ByteArray): ByteArray {
    require(pcm.size % 2 == 0)
    val samples = pcm.size / 2
    return ByteArray(samples * 3 / 2 * 2).also { out ->
        repeat(out.size / 2) { i ->
            val source = i * 2 / 3
            out[i * 2] = pcm[source * 2]; out[i * 2 + 1] = pcm[source * 2 + 1]
        }
    }
}

@Composable
fun DiagnosticsScreen(state: ConversationState, model: ConversationViewModel, back: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentState by rememberUpdatedState(state)
    val probe = remember { ConversationAudio(context, scope) }
    var busy by remember { mutableStateOf(false) }
    var cameraBusy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready. Stop conversation for isolated audio tests.") }
    var route by remember { mutableStateOf("") }
    var lastProbeRoute by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var tick by remember { mutableLongStateOf(0L) }
    var geminiHealth by remember { mutableStateOf("Gemini backend probe: not checked") }
    var checkingGemini by remember { mutableStateOf(false) }
    var datPermissions by remember { mutableStateOf("Not checked") }
    var motion by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    BackHandler { back() }
    DisposableEffect(Unit) {
        DiagnosticLog.add("diagnostics", "screen_opened")
        onDispose { model.gestures.requestDiagnosticMotion(false) }
    }
    LaunchedEffect(Unit) {
        var previousRoute = ""
        var loggedAt = 0L
        while (isActive) {
            tick = SystemClock.elapsedRealtime()
            val audio = if (busy) probe else model.audio
            route = runCatching { audio.diagnosticRoute() }.getOrDefault("Audio route unavailable")
            level = audio.micLevel
            if (route != previousRoute) {
                route.lines().forEach { DiagnosticLog.add("audio", it) }
                previousRoute = route
            }
            if (tick - loggedAt >= 2000 && (currentState.running || busy || motion)) {
                DiagnosticLog.add("audio", "mic_rms_percent=%.1f".format(level * 100))
                if (motion) DiagnosticLog.add("motion", if (tick - DiagnosticMetrics.gyroUpdatedMs <= 1000) DiagnosticMetrics.gyro else "No recent gyro samples", "DEBUG")
                loggedAt = tick
            }
            if (tick / 1000 != (tick - 250) / 1000) datPermissions = runCatching {
                if (model.glasses.initialize()) "camera=${Wearables.checkPermissionStatus(Permission.CAMERA)}; mic=${Wearables.checkPermissionStatus(Permission.MICROPHONE)}"
                else "DAT unavailable"
            }.getOrDefault("DAT unavailable")
            delay(250)
        }
    }
    fun audioTest(record: Boolean) {
        if (busy) return
        busy = true
        scope.launch {
            val recording = ByteArrayOutputStream(160_000)
            val audioError = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val manager = context.getSystemService(AudioManager::class.java)
            val testJob = currentCoroutineContext().job
            val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).build())
                .setOnAudioFocusChangeListener { if (it < 0) { status = "Audio test stopped: focus lost"; testJob.cancel() } }.build()
            try {
                status = if (record) "Recording 5 seconds…" else "Testing communication speaker…"
                DiagnosticLog.add("diagnostics", if (record) "record_5s_started" else "beep_started")
                check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio focus unavailable" }
                probe.start(state.mock, onAudio = { bytes ->
                    if (record) synchronized(recording) {
                        val count = minOf(bytes.size, 160_000 - recording.size())
                        if (count > 0) recording.write(bytes, 0, count)
                    }
                }, onRoute = { DiagnosticLog.add("audio", it) }, onError = { audioError.set(it) },
                    onBluetoothLost = { audioError.set("Bluetooth lost; exit test and reconnect") })
                if (record) {
                    delay(5_000)
                    val bytes = synchronized(recording) { recording.toByteArray() }
                    check(bytes.size >= 144_000) { "Microphone captured less than 4.5 s; check permissions/route" }
                    status = "Playing back…"
                    probe.enqueue(diagnosticPlayback(bytes))
                } else probe.enqueue(diagnosticBeep())
                withTimeout(10_000) { probe.awaitPlaybackIdle() }
                lastProbeRoute = probe.diagnosticRoute()
                lastProbeRoute.lines().forEach { DiagnosticLog.add("audio", "probe $it") }
                check(audioError.get() == null) { audioError.get() ?: "Audio failed" }
                status = "Playback complete. Confirm you heard it on the expected speaker."
                DiagnosticLog.add("diagnostics", "audio_probe_complete")
            } catch (e: TimeoutCancellationException) {
                status = "Audio test timed out; check route/playback"
                DiagnosticLog.add("diagnostics", "audio_probe_timeout", "ERROR")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                status = e.message ?: "Audio test failed"
                DiagnosticLog.add("diagnostics", "audio_probe_failed", "ERROR")
            } finally {
                withContext(NonCancellable) { probe.stop() }
                manager.abandonAudioFocusRequest(focus)
                recording.reset()
                busy = false
            }
        }
    }
    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO); add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.joinToString("\n") { "${it.substringAfterLast('.')}: ${ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED}" }
    val summary = "Glasses: ${state.glasses}\nGemini: ${state.gemini}\n$route\nPermissions:\n$permissions\nDAT: $datPermissions"
    fun export(): String = "$summary\nLast probe:\n$lastProbeRoute\n${DiagnosticLog.ring.export()}"
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Diagnostics", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = back) { Text("Back to conversation") }
            Text("Glasses / DAT: ${state.glasses}\n${state.inputs}")
            Text(route)
            if (!busy && !state.running && lastProbeRoute.isNotEmpty()) Text("Last completed probe:\n$lastProbeRoute")
            Text("Microphone RMS: %.1f%%".format(level * 100))
            LinearProgressIndicator(progress = { level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("Gemini: ${state.gemini}\nResumption handle age: ${DiagnosticMetrics.handleUpdatedMs?.let { "${(tick - it).coerceAtLeast(0) / 1000} s" } ?: "none / unavailable"}")
            Text(geminiHealth)
            var offlineReview by remember { mutableStateOf("") }
            Button(onClick = { offlineReview = model.offlineCaptures() }) { Text("Review offline captures") }
            if (offlineReview.isNotEmpty()) Text(offlineReview)
            Button(enabled = !checkingGemini, onClick = {
                checkingGemini = true
                scope.launch {
                    try {
                        val result = model.geminiHealth()
                        geminiHealth = "Backend Live probe: ${result.optString("status")} · ${result.optString("checked_at")} · cached=${result.optBoolean("cached")}\n${result.optJSONArray("models")}"
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { geminiHealth = "Gemini health probe unavailable; check backend connection" }
                    finally { checkingGemini = false }
                }
            }) { Text("Check Gemini health") }
            Text("Speech end → first audio: ${DiagnosticMetrics.latencyMs?.let { "$it ms (local estimate, excludes speaker buffer)" } ?: "Awaiting spoken reply"}")
            Text("Backend: ${DiagnosticLog.uploadStatus}\nRing evictions: ${DiagnosticLog.ring.dropped}")
            Text("Permissions:\n$permissions\nDAT: $datPermissions")
            Text("Flags: debug=${BuildConfig.DEBUG}; mock=${state.mock}; mode=${state.settings.mode}; gestures=${model.gestures.state.value.enabled}; watch=${state.watchMode}; visual memory=${state.visualMemory}; meeting=${state.meeting}; translator=${state.translationLanguage != null}; phone translation=${state.translationPhoneSpeaker}; coach nod=${state.settings.coachNodNavigation}; workout IMU=${model.workout.state.value.needsMotion}")
            Text(status)
            Button(onClick = { audioTest(false) }, enabled = !state.handsFree.armed && !state.running && !state.stopping && !busy && state.permissions) { Text("Test speaker beep") }
            Button(onClick = { audioTest(true) }, enabled = !state.handsFree.armed && !state.running && !state.stopping && !busy && state.permissions) { Text("Record 5 s and play back") }
            Text("Audio tests require Stop service and glasses controls. Camera / Motion tests need a running Assistant conversation. Exit diagnostics to cancel an audio test.")
            Button(onClick = {
                cameraBusy = true
                scope.launch {
                    status = "Capturing frame…"
                    try {
                        val bytes = model.diagnosticCamera()
                        frame = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        check(frame != null) { "Cannot decode camera frame" }
                        status = "Frame ${frame!!.width} × ${frame!!.height}, ${bytes.size} bytes; local preview only"
                        DiagnosticLog.add("vision", "diagnostic_frame bytes=${bytes.size}")
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { status = e.message ?: "Camera failed"; DiagnosticLog.add("vision", "diagnostic_capture_failed", "ERROR") }
                    finally { cameraBusy = false }
                }
            }, enabled = state.canCaptureVisual && !cameraBusy) { Text("Test camera frame") }
            frame?.let { Image(it.asImageBitmap(), "Latest test camera frame", Modifier.fillMaxWidth().height(200.dp)) }
            Button(onClick = {
                motion = !motion
                model.gestures.requestDiagnosticMotion(motion)
                if (motion) model.glasses.startInputs()
                DiagnosticLog.add("motion", "diagnostic_requested=$motion")
            }, enabled = motion || state.motionAllowed) { Text(if (motion) "Stop motion test" else "Test motion (show live gyro)") }
            Text("Gyro: ${if (tick - DiagnosticMetrics.gyroUpdatedMs <= 1000) DiagnosticMetrics.gyro else "No recent samples"}\n${model.gestures.state.value.status}")
            Button(onClick = {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Glasses diagnostics", export()))
                status = "Logs copied"
            }) { Text("Copy logs") }
            Button(onClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, export())
                }, "Share diagnostics"))
            }) { Text("Share logs") }
            Text(state.error ?: "No app error")
        }
    }
}
