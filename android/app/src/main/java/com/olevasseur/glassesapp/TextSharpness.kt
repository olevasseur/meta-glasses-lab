package com.olevasseur.glassesapp

class BlurryTextFrame : IllegalStateException("Hold still and point the camera squarely at the text")

/** Variance of the four-neighbour Laplacian, on 8-bit grayscale pixels. */
fun laplacianVariance(gray: IntArray, width: Int, height: Int): Double {
    require(width >= 3 && height >= 3 && gray.size == width * height)
    var sum = 0.0
    var squares = 0.0
    for (y in 1 until height - 1) for (x in 1 until width - 1) {
        val i = y * width + x
        val value = (gray[i - 1] + gray[i + 1] + gray[i - width] + gray[i + width] - 4 * gray[i]).toDouble()
        sum += value
        squares += value * value
    }
    val count = (width - 2).toDouble() * (height - 2)
    return (squares / count - (sum / count) * (sum / count)).coerceAtLeast(0.0)
}
