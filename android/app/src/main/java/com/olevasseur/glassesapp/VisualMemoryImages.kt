package com.olevasseur.glassesapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

suspend fun visualImageHash(jpeg: ByteArray): Long = withContext(Dispatchers.Default) {
    val original = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
    val small = Bitmap.createScaledBitmap(original, 9, 8, true)
    try {
        val pixels = IntArray(72)
        small.getPixels(pixels, 0, 9, 0, 0, 9, 8)
        visualDifferenceHash(IntArray(72) { index ->
            val color = pixels[index]
            (((color shr 16) and 255) * 299 + ((color shr 8) and 255) * 587 + (color and 255) * 114) / 1000
        })
    } finally {
        if (small !== original) small.recycle()
        original.recycle()
    }
}

fun visualCaptureChime(scope: CoroutineScope) {
    val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
    try {
        check(tone.startTone(ToneGenerator.TONE_PROP_BEEP, 180))
    } catch (e: Exception) { tone.release(); throw e }
    scope.launch { try { delay(250) } finally { tone.release() } }
}
