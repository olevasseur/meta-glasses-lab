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
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import com.olevasseur.glassesapp.ui.GlassesApp
import com.olevasseur.glassesapp.ui.GlassesTheme
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
    internal val smokeModel get() = boundService?.model
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
    private val cameraPermission = registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
        if (result.getOrDefault(PermissionStatus.Denied) != PermissionStatus.Granted) {
            boundService?.model?.error("Glasses camera permission denied. Grant it before asking Gemini to look.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingStart = savedInstanceState?.getBoolean("pending_start")
            ?: (isVoiceInvocationsIntent(intent) || intent.action == ConversationService.START)
        binding = bindService(Intent(this, ConversationService::class.java), connection, BIND_AUTO_CREATE)
        setContent {
            GlassesTheme {
                val model = boundService?.model
                if (model == null) { Text("Connecting to conversation service…"); return@GlassesTheme }
                val state by model.state.collectAsStateWithLifecycle()
                LaunchedEffect(state.settingsLoaded, state.permissions) { launchWhenReady() }
                GlassesApp(state, model, ::requestPermissions,
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
        CoachAlarm(this).restore()
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
        boundService?.model?.let { SmokeHooks.configure(intent, it) }
        if (pendingStart && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && boundService?.model?.state?.value?.permissions == true && boundService?.model?.state?.value?.settingsLoaded == true) {
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
