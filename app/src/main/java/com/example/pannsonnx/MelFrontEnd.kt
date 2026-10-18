package com.example.pannsonnx

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Kotlin port of mealaudio/export/mel_reference.py (the EfficientAT front end). Pure Kotlin, no Android APIs.
 *
 *  1. pre-emphasis        y[n] = x[n+1] - 0.97 * x[n]
 *  2. STFT                n_fft 1024, hop 320, centre=True (reflect padding 512), window from window.bin (Hann 800 padded to 1024)
 *  3. power spectrum      |X|^2, 513 bins
 *  4. mel filterbank      matrix from mel_basis.bin (128 x 513, float32 little endian, row-major)
 *  5. log + normalise     (ln(mel + 1e-5) + 4.5) / 5
 *
 * Input: mono float samples in [-1, 1] at 32 kHz. Output: FloatArray laid out row-major as (nMels, frames), i.e.
 * exactly the flattened ONNX input of shape (1, 1, nMels, frames).
 */
class MelFrontEnd(basisBytes: ByteArray, windowBytes: ByteArray, val nMels: Int = 128) {

    companion object {
        const val N_FFT = 1024
        const val HOP = 320
        const val N_BINS = N_FFT / 2 + 1
        private const val PREEMPH = 0.97
        private const val LOG_EPS = 1e-5

        private fun floats(b: ByteArray): FloatArray {
            val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(fb.remaining()).also { fb.get(it) }
        }
    }

    private val basis: FloatArray = floats(basisBytes)
    private val window = DoubleArray(N_FFT)
    private val lo = IntArray(nMels)      // first / last non-zero bin of every (triangular, sparse) filter
    private val hi = IntArray(nMels)
    private val cosT = DoubleArray(N_FFT / 2)
    private val sinT = DoubleArray(N_FFT / 2)
    private val rev = IntArray(N_FFT)
    private val re = DoubleArray(N_FFT)
    private val im = DoubleArray(N_FFT)
    private val power = DoubleArray(N_BINS)

    init {
        require(basis.size == nMels * N_BINS) { "mel_basis.bin has ${basis.size} floats, expected ${nMels * N_BINS}" }
        val w = floats(windowBytes)
        require(w.size == N_FFT) { "window.bin has ${w.size} floats, expected $N_FFT" }
        for (i in 0 until N_FFT) window[i] = w[i].toDouble()
        for (m in 0 until nMels) {
            var a = N_BINS
            var b = -1
            for (k in 0 until N_BINS) if (basis[m * N_BINS + k] != 0f) { if (k < a) a = k; b = k }
            lo[m] = a; hi[m] = b
        }
        for (i in 0 until N_FFT / 2) {
            val ang = -2.0 * PI * i / N_FFT
            cosT[i] = cos(ang); sinT[i] = sin(ang)
        }
        val bits = Integer.numberOfTrailingZeros(N_FFT)
        for (i in 0 until N_FFT) {
            var x = i; var r = 0
            for (j in 0 until bits) { r = (r shl 1) or (x and 1); x = x shr 1 }
            rev[i] = r
        }
    }

    fun numFrames(nSamples: Int): Int = 1 + (nSamples - 1) / HOP

    @Synchronized
    fun compute(wave: FloatArray): FloatArray {
        val n = wave.size - 1                       // length after pre-emphasis
        require(n > N_FFT / 2) { "window too short" }
        val frames = 1 + n / HOP
        val out = FloatArray(nMels * frames)
        for (t in 0 until frames) {
            // windowed frame with reflect padding (numpy/torch 'reflect': no edge repeat), written in bit-reversed order
            for (k in 0 until N_FFT) {
                var j = t * HOP + k - N_FFT / 2
                if (j < 0) j = -j
                if (j >= n) j = 2 * (n - 1) - j
                val x = wave[j + 1].toDouble() - PREEMPH * wave[j].toDouble()
                val r = rev[k]
                re[r] = x * window[k]
                im[r] = 0.0
            }
            var len = 2
            while (len <= N_FFT) {
                val half = len shr 1
                val step = N_FFT / len
                var i = 0
                while (i < N_FFT) {
                    for (j in 0 until half) {
                        val wr = cosT[j * step]; val wi = sinT[j * step]
                        val a = i + j; val b = a + half
                        val tr = re[b] * wr - im[b] * wi
                        val ti = re[b] * wi + im[b] * wr
                        re[b] = re[a] - tr; im[b] = im[a] - ti
                        re[a] += tr; im[a] += ti
                    }
                    i += len
                }
                len = len shl 1
            }
            for (k in 0 until N_BINS) power[k] = re[k] * re[k] + im[k] * im[k]
            for (m in 0 until nMels) {
                var acc = 0.0
                val base = m * N_BINS
                for (k in lo[m]..hi[m]) acc += basis[base + k] * power[k]
                out[m * frames + t] = ((ln(acc + LOG_EPS) + 4.5) / 5.0).toFloat()
            }
        }
        return out
    }
}
