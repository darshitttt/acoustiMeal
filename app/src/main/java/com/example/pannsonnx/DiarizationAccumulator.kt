package com.example.pannsonnx

import kotlin.math.abs
import kotlin.math.roundToInt

/** A speaker turn, in seconds from the start of the recording. */
data class SpeakerSegment(val start: Double, val end: Double, val speaker: Int)

data class DiarizationResult(val speakers: List<SpeakerSegment>, val overlap: List<Pair<Double, Double>>)

/**
 * Collects the per-window outputs of the model during a recording and turns them into speaker turns and overlap
 * segments afterwards. Mirrors diarize.decode() of the Python pipeline: every window with a speech embedding
 * "owns" the central [hopS] seconds of its [winS]-second span; adjacent windows with the same speaker are merged.
 * Window k starts at k * hopS seconds. Pure Kotlin, thread-safe.
 */
class DiarizationAccumulator(
    private val winS: Double,
    private val hopS: Double,
    private val overlapFps: Int = 25
) {
    private class Rec(val index: Long, val emb: FloatArray?, val overlapFlags: BooleanArray?)

    private val recs = ArrayList<Rec>()
    private val padS = (winS - hopS) / 2.0
    private val firstFrame = (padS * overlapFps).roundToInt()
    private val frameCount = (hopS * overlapFps).roundToInt()

    /** [overlapProb]: per-40 ms probabilities of the whole window (null if no overlap head). */
    @Synchronized
    fun add(index: Long, emb: FloatArray?, overlapProb: FloatArray?, overlapThr: Float) {
        val flags = if (overlapProb != null && overlapProb.size >= firstFrame + frameCount)
            BooleanArray(frameCount) { overlapProb[firstFrame + it] >= overlapThr } else null
        recs.add(Rec(index, emb, flags))
    }

    @Synchronized
    fun discardBefore(timeS: Double) {
        recs.removeAll { it.index * hopS + padS + hopS <= timeS }
    }

    @Synchronized
    fun size(): Int = recs.size

    fun finalize(fromS: Double, toS: Double, distThreshold: Float, minFrac: Float): DiarizationResult {
        val sel: List<Rec> = synchronized(this) {
            recs.filter { val c0 = it.index * hopS + padS; c0 + hopS > fromS && c0 < toS }.sortedBy { it.index }
        }
        val withEmb = sel.filter { it.emb != null }
        val speakers = if (withEmb.isEmpty()) emptyList() else {
            val labels = SpeakerClustering.cluster(withEmb.map { it.emb!! }, distThreshold, minFrac)
            buildSegments(DoubleArray(withEmb.size) { withEmb[it].index * hopS }, labels, winS, hopS)
        }
        return DiarizationResult(speakers, overlapSegments(sel))
    }

    private fun overlapSegments(sel: List<Rec>): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        var curStart = -1.0
        var curEnd = -1.0
        val dt = 1.0 / overlapFps
        for (r in sel) {
            val flags = r.overlapFlags ?: continue
            val c0 = r.index * hopS + padS
            for (i in flags.indices) {
                if (!flags[i]) continue
                val a = c0 + i * dt
                if (curEnd >= 0 && abs(a - curEnd) < 1e-6) curEnd = a + dt
                else {
                    if (curEnd >= 0) out.add(Pair(curStart, curEnd))
                    curStart = a; curEnd = a + dt
                }
            }
        }
        if (curEnd >= 0) out.add(Pair(curStart, curEnd))
        return out
    }

    companion object {
        /** starts[i] = start time (s) of window i; labels[i] = its speaker. Same merge rule as the Python decode(). */
        fun buildSegments(starts: DoubleArray, labels: IntArray, winS: Double, hopS: Double): List<SpeakerSegment> {
            val segs = ArrayList<SpeakerSegment>()
            for (i in starts.indices) {
                val a = starts[i] + (winS - hopS) / 2.0
                val b = a + hopS
                val last = segs.lastOrNull()
                if (last != null && last.speaker == labels[i] && abs(last.end - a) < 1e-6) segs[segs.size - 1] = SpeakerSegment(last.start, b, last.speaker)
                else segs.add(SpeakerSegment(a, b, labels[i]))
            }
            return segs
        }
    }
}
