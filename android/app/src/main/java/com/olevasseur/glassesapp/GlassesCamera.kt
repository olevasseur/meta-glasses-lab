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
    private val onError: (String) -> Unit,
) {
    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var monitor: Job? = null

    private var highResolution = false

    suspend fun capture(): ByteArray = capture(false)
    suspend fun captureText(): ByteArray = capture(true)
    suspend fun capturePhoto(): ByteArray = capture(true, checkText = false)

    private suspend fun capture(forText: Boolean, checkText: Boolean = forText): ByteArray {
        try {
            check(glasses.initialize()) { "Connect glasses and grant Nearby devices permission first" }
            check(Wearables.checkPermissionStatus(Permission.CAMERA).getOrDefault(PermissionStatus.Denied) == PermissionStatus.Granted) {
                "Grant DAT camera permission before using vision"
            }
            if (camera != null && forText && !highResolution) stop()
            if (camera == null) {
                onState("Starting glasses camera…")
                val id = AutoDeviceSelector().activeDeviceFlow().first { it != null }!!
                val deviceSession = glasses.acquireSession(SpecificDeviceSelector(id))
                session = deviceSession
                deviceSession.state.first { it == DeviceSessionState.STARTED }
                val added = deviceSession.addCamera(StreamConfiguration(videoQuality = if (forText) VideoQuality.HIGH else VideoQuality.MEDIUM, frameRate = 2))
                    .fold(onSuccess = { it }, onFailure = { error, _ -> error(error.description) })
                camera = added
                highResolution = forText
                added.stream.start().fold(onSuccess = { }, onFailure = { error, _ -> error(error.description) })
                added.stream.state.first { it == StreamState.STREAMING }
                monitor = scope.launch {
                    launch { added.stream.errorStream.collect { stop(); onError("Camera: ${it.description}") } }
                    added.stream.state.collect {
                        if (it == StreamState.STOPPED || it == StreamState.CLOSED || it == StreamState.PAUSED) {
                            stop()
                            onError("Glasses camera stopped ($it); check the connection and DAT camera permission before retrying.")
                        }
                    }
                }
                onState("Camera active")
            }
            val photo = checkNotNull(camera).stream.capturePhoto()
                .fold(onSuccess = { it }, onFailure = { error, _ -> error(error.description) })
            return withContext(Dispatchers.Default) { encode(photo, forText, checkText) }
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

    private fun checkSharpness(bitmap: Bitmap) {
        val scale = minOf(1.0, 512.0 / maxOf(bitmap.width, bitmap.height))
        val small = Bitmap.createScaledBitmap(bitmap, maxOf(3, (bitmap.width * scale).toInt()),
            maxOf(3, (bitmap.height * scale).toInt()), true)
        try {
            val pixels = IntArray(small.width * small.height)
            small.getPixels(pixels, 0, small.width, 0, 0, small.width, small.height)
            for (i in pixels.indices) {
                val color = pixels[i]
                pixels[i] = (77 * (color shr 16 and 255) + 150 * (color shr 8 and 255) + 29 * (color and 255)) shr 8
            }
            // ponytail: scene-dependent heuristic; calibrate on glasses before tuning this threshold.
            if (laplacianVariance(pixels, small.width, small.height) < 60.0) throw BlurryTextFrame()
        } finally { if (small !== bitmap) small.recycle() }
    }

    private fun encode(photo: PhotoData, forText: Boolean, checkText: Boolean): ByteArray {
        // ImageDecoder applies HEIC orientation and downsamples during decode (API 28+).
        val original = when (photo) {
            is PhotoData.Bitmap -> photo.bitmap
            is PhotoData.HEIC -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(photo.data.duplicate())) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                if (!forText) {
                    val (width, height) = visionDimensions(info.size.width, info.size.height)
                    decoder.setTargetSize(width, height)
                }
            }
        }
        val (width, height) = if (forText) original.width to original.height else visionDimensions(original.width, original.height)
        val scaled = Bitmap.createScaledBitmap(original, width, height, true)
        try {
            if (checkText) checkSharpness(original)
            return ByteArrayOutputStream().use { output ->
                check(scaled.compress(Bitmap.CompressFormat.JPEG, if (forText) 95 else 80, output)) { "JPEG encoding failed" }
                output.toByteArray()
            }
        } finally {
            if (scaled !== original) scaled.recycle()
            if (photo is PhotoData.HEIC) original.recycle()
        }
    }
}
