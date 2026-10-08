package com.olevasseur.glassesapp

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.SpecificDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Uses DAT's supported photo-from-stream API, sharing the device session with Inputs. */
class GlassesCamera(
    private val scope: CoroutineScope,
    private val glasses: GlassesController,
    private val onState: (String) -> Unit,
) {
    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var monitor: Job? = null

    suspend fun capture(): ByteArray {
        try {
            check(glasses.initialize()) { "Connect glasses and grant Nearby devices permission first" }
            check(Wearables.checkPermissionStatus(Permission.CAMERA).getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted) {
                "Grant DAT camera permission before using vision"
            }
            if (camera == null) {
                onState("Starting glasses camera…")
                val id = AutoDeviceSelector().activeDeviceFlow().first { it != null }!!
                val deviceSession = glasses.acquireSession(SpecificDeviceSelector(id))
                session = deviceSession
                deviceSession.state.first { it == DeviceSessionState.STARTED }
                val added = deviceSession.addCamera(StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 2))
                    .fold(onSuccess = { it }, onFailure = { error, _ -> error(error.description) })
                camera = added
                added.stream.start().fold(onSuccess = { }, onFailure = { error, _ -> error(error.description) })
                added.stream.state.first { it == StreamState.STREAMING }
                monitor = scope.launch {
                    launch { added.stream.errorStream.collect { onState("Camera: ${it.description}"); stop() } }
                    added.stream.state.collect {
                        if (it == StreamState.STOPPED || it == StreamState.CLOSED || it == StreamState.PAUSED) stop()
                    }
                }
                onState("Camera active")
            }
            val photo = checkNotNull(camera).stream.capturePhoto()
                .fold(onSuccess = { it }, onFailure = { error, _ -> error(error.description) })
            return withContext(Dispatchers.Default) { encode(photo) }
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    fun stop() {
        monitor?.cancel(); monitor = null
        camera?.close(); camera = null // Close the capability, not just its restartable stream.
        session?.let(glasses::releaseCameraSession); session = null
        onState("Camera off")
    }

    private fun encode(photo: PhotoData): ByteArray {
        // ImageDecoder applies HEIC orientation and downsamples during decode (API 28+).
        val original = when (photo) {
            is PhotoData.Bitmap -> photo.bitmap
            is PhotoData.HEIC -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(photo.data.duplicate())) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (width, height) = visionDimensions(info.size.width, info.size.height)
                decoder.setTargetSize(width, height)
            }
        }
        val (width, height) = visionDimensions(original.width, original.height)
        val scaled = Bitmap.createScaledBitmap(original, width, height, true)
        try {
            return ByteArrayOutputStream().use { output ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, 80, output)) { "JPEG encoding failed" }
                output.toByteArray()
            }
        } finally {
            if (scaled !== original) scaled.recycle()
            if (photo is PhotoData.HEIC) original.recycle()
        }
    }
}
