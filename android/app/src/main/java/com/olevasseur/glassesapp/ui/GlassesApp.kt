package com.olevasseur.glassesapp.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.olevasseur.glassesapp.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@Composable
fun GlassesApp(state: ConversationState, model: ConversationViewModel, permissions: () -> Unit,
               pinShortcut: () -> Unit, register: () -> Unit, requestGlassesPermission: () -> Unit, requestCameraPermission: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_ui", Context.MODE_PRIVATE) }
    var setup by rememberSaveable { mutableStateOf(!prefs.getBoolean("setup_complete", false)) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    val approval by model.approvals.state.collectAsStateWithLifecycle()
    var overview by remember { mutableStateOf(Overview()) }
    var status by remember { mutableStateOf("Checking") }
    var reviewStatus by remember { mutableStateOf("Loading recent items…") }
    var refresh by remember { mutableIntStateOf(0) }
    var timers by remember { mutableStateOf(emptyList<String>()) }
    val backend = remember {
        BackendClient(OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build(),
            BuildConfig.BACKEND_URL, BuildConfig.APP_SHARED_SECRET)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // UI-only reads stop when the Activity is not visible. Controllers keep their service lifetime.
    LaunchedEffect(lifecycle, refresh) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                while (isActive) {
                    try {
                        backend.diagnosticHealth(); status = "Connected"
                        try {
                            overview = Overview.parse(backend.overview())
                            reviewStatus = "Recent snapshot · refreshes every 15 seconds"
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { reviewStatus = "Recent items unavailable. Showing the last snapshot, if available." }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        status = "Check connection"
                        reviewStatus = "Refresh failed. Showing the last snapshot, if available."
                    }
                    delay(15_000)
                }
            }
            launch {
                val alarm = CoachAlarm(context.applicationContext)
                while (isActive) {
                    val list = alarm.tool(FunctionCall("ui-timers", "list_timers", JSONObject())).response.optJSONArray("timers")
                    timers = List(list?.length() ?: 0) { i ->
                        val timer = list!!.getJSONObject(i)
                        "Timer · ${timer.getString("name")} · ${timer.getLong("remaining_seconds")}s"
                    }
                    delay(1_000)
                }
            }
        }
    }
    LaunchedEffect(approval?.id) { if (approval != null) { diagnostics = false; setup = false } }
    val dashboard: () -> Unit = {
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.BACKEND_URL.trimEnd('/') + "/dashboard"))) }
        catch (_: android.content.ActivityNotFoundException) { model.error("Install a browser to open the dashboard.") }
    }
    if (setup && approval == null) {
        OnboardingScreen(state, model, backend, permissions, register, requestGlassesPermission, requestCameraPermission) {
            prefs.edit().putBoolean("setup_complete", true).apply(); setup = false
        }
    } else {
        val savedScreens = rememberSaveableStateHolder()
        AppNavigation(approval?.id, state.visualMemory || state.visualCapturing, state.visualCapturing) { destination ->
            savedScreens.SaveableStateProvider(destination.name) {
                when (destination) {
                    Destination.TALK -> TalkScreen(state, model, approval, status, overview, timers) { setup = true }
                    Destination.FEATURES -> FeaturesScreen(state, model, overview, timers, dashboard)
                    Destination.MEMORY -> {
                        LaunchedEffect(Unit) { refresh++ }
                        MemoryScreen(overview, reviewStatus, { refresh++ }, dashboard)
                    }
                    Destination.SETTINGS -> if (diagnostics) DiagnosticsScreen(state, model) { diagnostics = false }
                        else SettingsScreen(state, model, permissions, register, requestGlassesPermission, requestCameraPermission,
                            pinShortcut, { setup = true }, { diagnostics = true })
                }
            }
        }
    }
}
