package com.example.pannsonnx

/**
 * Turns a stream of PCM chunks into overlapping model windows: window k covers samples [k*hop, k*hop + window).
 * Usage:  windower.push(buf, n); while (windower.hasWindow()) { val k = windower.nextWindow(out); ... }
 * Pure Kotlin, single-threaded (call from the audio loop only).
 */
class AudioWindower(private val windowSamples: Int, private val hopSamples: Int, maxChunk: Int) {
    private val ringSize = windowSamples + hopSamples + maxChunk
    private val ring = ShortArray(ringSize)
    private var written = 0L
    private var nextStart = 0L

    fun push(samples: ShortArray, n: Int) {
        for (i in 0 until n) ring[((written + i) % ringSize).toInt()] = samples[i]
        written += n
    }

    fun hasWindow(): Boolean = written >= nextStart + windowSamples

    /** Fills [out] (size windowSamples) with the next window as floats in [-1, 1); returns the window index k. */
    fun nextWindow(out: FloatArray): Long {
        check(hasWindow())
        require(out.size == windowSamples)
        for (i in 0 until windowSamples) out[i] = ring[((nextStart + i) % ringSize).toInt()] / 32768f
        val k = nextStart / hopSamples
        nextStart += hopSamples
        return k
    }

    fun reset() { written = 0L; nextStart = 0L }
}
