package com.example.pannsonnx

import kotlin.math.exp
import kotlin.math.roundToInt

enum class FeatureKind { FRAC, MEAN, EVENTS }

/** classIdx indexes the FULL tag-probability vector of the tag model (order of deploy_config.json "tag_classes"). */
class MealFeature(
    val kind: FeatureKind, val classIdx: Int, val thr: Float, val mu: Float, val sd: Float, val coef: Float
)

class MealEpisodeModel(
    val frameHopS: Double,
    val winFrames: Int,
    val strideFrames: Int,
    val features: List<MealFeature>,
    val intercept: Float,
    val smoothN: Int,
    val onThr: Float,
    val offThr: Float,
    val minOn: Int,
    val minOff: Int
)

enum class MealEventType { START, END }

/** timeS = seconds since the first tag frame of the recording (add the recording start time for wall-clock time). */
data class MealEvent(val type: MealEventType, val timeS: Double)

/**
 * Meal / no-meal episode detector: a logistic regression over a few per-class summaries of the last winFrames tag frames,
 * evaluated every strideFrames frames, followed by smoothing and an on/off state machine with hysteresis.
 * Exact mirror of mealaudio/episode/stream.py (see kotlin_tests/EpisodeTest.kt). Pure Kotlin, call from one thread.
 *
 * Usage: for every processed audio window call push(tagProbs) with the FULL probability vector; handle the returned events.
 */
class MealEpisodeDetector(private val m: MealEpisodeModel) {

    private val needed: IntArray = m.features.map { it.classIdx }.distinct().toIntArray()
    private val local: Map<Int, Int> = needed.withIndex().associate { (i, c) -> c to i }
    private val ring = FloatArray(m.winFrames * needed.size)

    private var frames = 0L
    private var windowIdx = -1L
    private val recent = ArrayDeque<Double>()
    private var on = false
    private var posRun = 0
    private var negRun = 0
    private var startIdx = -1L
    private var lastPos = -1L

    /** Smoothed probability of the latest analysis window (null before the first one) and the current state. */
    var lastProbability: Float? = null
        private set
    /** Raw (unsmoothed) probability of the latest analysis window and the number of windows evaluated so far (for logging/tests). */
    var rawProbability: Double? = null
        private set
    var windowCount: Long = 0
        private set
    val isOn: Boolean get() = on

    /** Debug string: current run counters of the state machine. */
    val debugState: String get() = "posRun=$posRun negRun=$negRun"

    /** Switch to OFF from outside (safety net in the service) and forget the smoothing history. */
    fun forceOff() {
        on = false; posRun = 0; negRun = 0; startIdx = -1; lastPos = -1
        recent.clear()
    }

    fun reset() {
        frames = 0; windowIdx = -1; recent.clear(); on = false; posRun = 0; negRun = 0; startIdx = -1; lastPos = -1
        lastProbability = null; rawProbability = null; windowCount = 0
    }

    fun push(tagProbs: FloatArray): List<MealEvent> {
        val slot = (frames % m.winFrames).toInt() * needed.size
        for (i in needed.indices) ring[slot + i] = tagProbs[needed[i]]
        frames++
        if (frames < m.winFrames || (frames - m.winFrames) % m.strideFrames != 0L) return emptyList()
        windowIdx = (frames - m.winFrames) / m.strideFrames
        val p = windowProbability()
        rawProbability = p
        windowCount++
        return update(p)
    }

    /** Raw logistic probability of the last winFrames frames. */
    private fun windowProbability(): Double {
        var z = m.intercept.toDouble()
        val minutes = m.winFrames * m.frameHopS / 60.0
        val first = frames - m.winFrames
        for (f in m.features) {
            val li = local.getValue(f.classIdx)
            var sum = 0.0
            var cnt = 0
            var rises = 0
            var prev = false
            for (t in 0 until m.winFrames) {
                val p = ring[(((first + t) % m.winFrames).toInt()) * needed.size + li]
                val b = p >= f.thr
                if (b) cnt++
                if (b && !prev) rises++
                prev = b
                sum += p
            }
            val x = when (f.kind) {
                FeatureKind.FRAC -> cnt.toDouble() / m.winFrames
                FeatureKind.MEAN -> sum / m.winFrames
                FeatureKind.EVENTS -> rises / minutes
            }
            z += f.coef * (x - f.mu) / f.sd
        }
        return 1.0 / (1.0 + exp(-z))
    }

    private fun centre(i: Long): Double = (i * m.strideFrames + m.winFrames / 2.0) * m.frameHopS

    private fun update(p: Double): List<MealEvent> {
        recent.addLast(p)
        while (recent.size > m.smoothN) recent.removeFirst()
        val ps = recent.average()
        lastProbability = ps.toFloat()
        val strideS = m.strideFrames * m.frameHopS
        val out = ArrayList<MealEvent>(1)
        val i = windowIdx
        if (!on) {
            posRun = if (ps >= m.onThr) posRun + 1 else 0
            if (posRun >= m.minOn) {
                on = true; negRun = 0
                startIdx = i - (m.minOn - 1); lastPos = i
                out.add(MealEvent(MealEventType.START, centre(startIdx) - strideS / 2))
            }
        } else if (ps >= m.offThr) {
            negRun = 0; lastPos = i
        } else {
            negRun++
            if (negRun >= m.minOff) {
                on = false; posRun = 0
                out.add(MealEvent(MealEventType.END, centre(lastPos) + strideS / 2))
            }
        }
        return out
    }
}