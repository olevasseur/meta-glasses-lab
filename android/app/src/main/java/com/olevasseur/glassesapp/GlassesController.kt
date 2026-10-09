package com.olevasseur.glassesapp

import android.app.Activity
import android.content.Context
import android.net.Uri
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputSource
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.inputs.types.InputsState
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GlassesController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onGlasses: (String) -> Unit,
    private val onInputs: (String) -> Unit,
    private val onApprovalCapture: () -> Boolean = { false },
    private val onHold: () -> Unit,
    private val onInputLost: () -> Unit,
    private val onDoubleTap: () -> Unit = {},
    private val onInputReady: () -> Unit = {},
    private val onError: (String) -> Unit,
) {
    private var initialized = false
    private var monitorJob: Job? = null
    private var sessionDiagnostics: Job? = null
    private val inputsJob = SerialSessionJob(scope)
    private val mutableSession = MutableStateFlow<DeviceSession?>(null)
    val activeSession = mutableSession.asStateFlow()
    private var deviceSession: DeviceSession? = null
    private var mock: MockGlasses? = null
    internal val mockDevice get() = if (BuildConfig.DEBUG) mock else null
    private val kit by lazy { MockDeviceKit.getInstance(context) }

    fun initialize(): Boolean {
        if (initialized) return true
        Wearables.initialize(context).fold(
            onSuccess = { initialized = true },
            onFailure = { error, _ -> onError("DAT: ${error.description}") },
        )
        if (!initialized) return false
        monitorJob = scope.launch {
            launch {
                Wearables.registrationErrorStream.collect { onError("Registration: ${it.description}") }
            }
            Wearables.registrationState.collectLatest { registration ->
                onGlasses("$registration · no glasses connected")
                AutoDeviceSelector().activeDeviceFlow().collectLatest { id ->
                    if (id == null) onGlasses("$registration · no glasses connected")
                    else Wearables.devicesMetadata[id]?.collect { device ->
                        val link = if (device.linkState == LinkState.CONNECTED) "connected" else device.linkState.toString()
                        onGlasses("$registration · ${device.name.ifBlank { "Meta glasses" }} · $link · ${device.compatibility} · battery=${device.batteryLevel}% · worn=${device.donState}")
                    }
                }
            }
        }
        return true
    }

    fun register(activity: Activity) { if (initialize()) Wearables.startRegistration(activity) }

    // Camera, Inputs, Motion and Speech share one DAT session on Main.
    fun acquireSession(selector: DeviceSelector): DeviceSession {
        deviceSession?.takeIf { it.state.value != DeviceSessionState.STOPPED && it.state.value != DeviceSessionState.STOPPING }?.let { return it }
        return Wearables.createSession(selector)
            .fold(onSuccess = { it }, onFailure = { error, _ ->
                throw DatInputsFailure("createSession code=$error description=${error.description}")
            })
            .also { session ->
                deviceSession = session
                sessionDiagnostics?.cancel()
                // start() is fire-and-forget: subscribe before it can emit failures.
                sessionDiagnostics = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val errors = launch(start = CoroutineStart.UNDISPATCHED) {
                        session.errors.collect {
                            DiagnosticLog.add("dat", "Session error code=$it description=${it.description}", "ERROR")
                        }
                    }
                    try {
                        var previous: DeviceSessionState? = null
                        session.state.first { state ->
                            DiagnosticLog.add("dat", "Session state $previous -> $state")
                            previous = state
                            state == DeviceSessionState.STOPPED
                        }
                    } finally { errors.cancel() }
                }
                session.start()
            }
    }

    fun releaseCameraSession(session: DeviceSession) {
        if (!inputsJob.active && deviceSession === session) {
            session.stop(); deviceSession = null
        }
    }

    private fun reportInputs(label: String) {
        DiagnosticLog.add("dat", label)
        onInputs(label)
    }

    fun startInputs() {
        if (!initialized) return
        inputsJob.start { isCurrent ->
            AutoDeviceSelector().activeDeviceFlow().collectLatest { id ->
                onInputLost()
                if (id == null) {
                    reportInputs("Glasses controls unavailable; no glasses; use screen/notification")
                    return@collectLatest
                }
                recoverDatInputs(::reportInputs) { ready ->
                    val session = acquireSession(com.meta.wearable.dat.core.selectors.SpecificDeviceSelector(id))
                    try {
                        coroutineScope {
                            val errors = launch(start = CoroutineStart.UNDISPATCHED) {
                                session.errors.collect {
                                    throw DatInputsFailure("session code=$it description=${it.description}")
                                }
                            }
                            try {
                                awaitDatInputsSession(session.state) {
                                    mutableSession.value = session
                                    val inputs = session.addInputs(InputsConfiguration(
                                        sources = setOf(InputSource.CAPTURE_BUTTON, InputSource.CAPTOUCH), consumeBack = false,
                                    )).fold(onSuccess = { it }, onFailure = { error, _ ->
                                        throw DatInputsFailure("addInputs code=$error description=${error.description}")
                                    })
                                    coroutineScope {
                                        launch {
                                            combine(session.state, inputs.state) { s, i -> s to i }
                                                .collectLatest { (sessionState, inputState) ->
                                                    reportInputs("Glasses controls: $sessionState · $inputState")
                                                    if (sessionState == DeviceSessionState.STARTED && inputState == InputsState.ACTIVE) {
                                                        ready()
                                                        onInputReady()
                                                    } else {
                                                        onInputLost()
                                                        onInputs("Glasses controls unavailable ($sessionState · $inputState); use screen/notification")
                                                        // PAUSED is resumable. Only replace inputs stuck on an active session.
                                                        if (sessionState == DeviceSessionState.STARTED) {
                                                            delay(15_000)
                                                            throw DatInputsFailure("inputs activation timed out state=$inputState")
                                                        }
                                                    }
                                                }
                                        }
                                        launch {
                                            inputs.errors.collect { error -> error?.let {
                                                throw DatInputsFailure("inputs code=$it description=${it.description}")
                                            } }
                                        }
                                        launch {
                                            inputs.events.collect { event ->
                                                if (approvalCapture(event, onApprovalCapture)) return@collect
                                                when (glassesTrigger(event)) {
                                                    GlassesTrigger.CONVERSATION -> onHold()
                                                    GlassesTrigger.TALK_OR_MUTE -> onDoubleTap()
                                                    null -> Unit
                                                }
                                            }
                                        }
                                    }
                                }
                            } finally { errors.cancel() }
                        }
                    } finally {
                        if (mutableSession.value === session) mutableSession.value = null
                        DiagnosticLog.add("dat", "Releasing inputs session state=${session.state.value}")
                        session.stop()
                        if (deviceSession === session) deviceSession = null
                        if (isCurrent()) onInputLost()
                    }
                }
            }
        }
    }

    fun stopInputs() { mutableSession.value = null; inputsJob.stop(); onInputLost(); onInputs("Inactive") }

    fun setMock(enabled: Boolean): Boolean {
        if (!BuildConfig.DEBUG) return false
        if (!initialized) { onError("Grant Nearby devices permission to initialize DAT first"); return false }
        stopInputs()
        if (enabled) {
            kit.enable(MockDeviceKitConfig(initiallyRegistered = true, initialPermissionsGranted = true))
            kit.pairGlasses(GlassesModel.RAYBAN_META).fold(
                onSuccess = { glasses ->
                    mock = glasses
                    glasses.powerOn(); glasses.unfold(); glasses.don()
                    glasses.services.camera.setCameraFeed(Uri.parse("android.resource://${context.packageName}/${R.raw.vision_stream}"))
                    glasses.services.camera.setCapturedImage(Uri.parse("android.resource://${context.packageName}/${R.raw.vision_sample}"))
                },
                onFailure = { error, _ -> kit.disable(); onError("Mock: ${error.description}") },
            )
        } else {
            mock?.let { kit.unpairDevice(it) }; mock = null; kit.disable()
        }
        return kit.isEnabled == enabled
    }

    fun mockHold() { mock?.services?.input?.capture(CapturePressType.HOLD) }
    fun mockDoubleTap() { mock?.services?.input?.select(InputSource.CAPTOUCH) }
    fun close() { stopInputs(); monitorJob?.cancel(); sessionDiagnostics?.cancel(); if (mock != null) setMock(false) }
}
