package com.olevasseur.glassesapp

import android.app.Activity
import android.content.Context
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.types.LinkState
import com.meta.wearable.dat.inputs.addInputs
import com.meta.wearable.dat.inputs.types.CapturePressType
import com.meta.wearable.dat.inputs.types.InputEvent
import com.meta.wearable.dat.inputs.types.InputSource
import com.meta.wearable.dat.inputs.types.InputsConfiguration
import com.meta.wearable.dat.inputs.types.InputsState
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockDeviceKitConfig
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class GlassesController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onGlasses: (String) -> Unit,
    private val onInputs: (String) -> Unit,
    private val onHold: () -> Unit,
    private val onInputLost: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private var initialized = false
    private var monitorJob: Job? = null
    private var inputsJob: Job? = null
    private val mutableSession = MutableStateFlow<DeviceSession?>(null)
    val activeSession = mutableSession.asStateFlow()
    private var mock: MockGlasses? = null
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
                        onGlasses("$registration · ${device.name.ifBlank { "Meta glasses" }} · $link · ${device.compatibility}")
                    }
                }
            }
        }
        return true
    }

    fun register(activity: Activity) { if (initialize()) Wearables.startRegistration(activity) }

    fun startInputs() {
        if (inputsJob?.isActive == true) return
        if (!initialized) return
        inputsJob = scope.launch {
            AutoDeviceSelector().activeDeviceFlow().collectLatest { id ->
                if (id == null) { onInputs("No glasses; use on-screen talk"); return@collectLatest }
                val session = Wearables.createSession(com.meta.wearable.dat.core.selectors.SpecificDeviceSelector(id))
                    .fold(onSuccess = { it }, onFailure = { error, _ -> onInputs(error.description); null })
                    ?: return@collectLatest
                try {
                    coroutineScope {
                        session.start()
                        withTimeout(15_000) { session.state.first { it == DeviceSessionState.STARTED } }
                        mutableSession.value = session
                        // Motion can remain usable even if capture inputs are unavailable.
                        val inputs = session.addInputs(InputsConfiguration(sources = setOf(InputSource.CAPTURE_BUTTON)))
                            .fold(onSuccess = { it }, onFailure = { error, _ -> onInputs(error.description); null })
                        launch { session.errors.collect { onInputs(it.description); onInputLost() } }
                        launch {
                            session.state.collect { state ->
                                if (state == DeviceSessionState.STOPPED || state == DeviceSessionState.STOPPING) {
                                    if (mutableSession.value === session) mutableSession.value = null
                                }
                                if (state != DeviceSessionState.STARTED) {
                                    onInputs("Glasses session: $state"); onInputLost()
                                }
                            }
                        }
                        launch {
                            inputs?.state?.collect {
                                onInputs("Capture button: $it")
                                if (it != InputsState.ACTIVE) onInputLost()
                            }
                        }
                        launch { inputs?.errors?.collect { error -> error?.let { onInputs(it.description); onInputLost() } } }
                        launch {
                            inputs?.events?.collect { event ->
                                if (event is InputEvent.Capture && event.source == InputSource.CAPTURE_BUTTON && event.pressType == CapturePressType.HOLD) onHold()
                            }
                        }
                        awaitCancellation()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    if (e is kotlinx.coroutines.TimeoutCancellationException) onInputs("Glasses session timed out; use on-screen talk")
                    else throw e
                } catch (_: Exception) {
                    onInputs("Inputs unavailable; use on-screen talk")
                } finally {
                    if (mutableSession.value === session) mutableSession.value = null
                    session.stop()
                    onInputLost()
                }
            }
        }
    }

    fun stopInputs() { mutableSession.value = null; inputsJob?.cancel(); inputsJob = null; onInputLost(); onInputs("Inactive") }

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
                },
                onFailure = { error, _ -> kit.disable(); onError("Mock: ${error.description}") },
            )
        } else {
            mock?.let { kit.unpairDevice(it) }; mock = null; kit.disable()
        }
        return kit.isEnabled == enabled
    }

    fun mockHold() { mock?.services?.input?.capture(CapturePressType.HOLD) }
    fun close() { stopInputs(); monitorJob?.cancel(); if (mock != null) setMock(false) }
}
