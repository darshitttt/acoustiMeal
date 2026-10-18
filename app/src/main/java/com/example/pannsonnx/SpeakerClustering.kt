package com.example.pannsonnx

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Offline speaker clustering that mirrors the Python evaluation pipeline (mealaudio/speaker/diarize.py):
 *   centre the embeddings (subtract the mean) -> L2-normalise -> average-linkage (UPGMA) agglomerative clustering on
 *   cosine distance, merging only pairs with distance < threshold -> merge clusters holding < minFrac of the windows
 *   into the nearest large cluster (cosine to the cluster centroid).
 * Average linkage is computed with the nearest-neighbour-chain algorithm, O(n^2) time and memory. Above [maxExact]
 * windows the clustering runs on a regular subsample and the remaining windows join the nearest centroid.
 * Pure Kotlin, no Android APIs.
 */
object SpeakerClustering {

    fun cluster(emb: List<FloatArray>, distThreshold: Float, minFrac: Float = 0.05f, maxExact: Int = 3000): IntArray {
        val n = emb.size
        if (n == 0) return IntArray(0)
        if (n == 1) return intArrayOf(0)
        val x = centreAndNormalise(emb)
        if (n <= maxExact) return mergeSmall(x, upgma(x, distThreshold), minFrac)

        val stride = ceil(n.toDouble() / maxExact).toInt()
        val subIdx = (0 until n step stride).toList()
        val sub = Array(subIdx.size) { x[subIdx[it]] }
        val subLab = mergeSmall(sub, upgma(sub, distThreshold), minFrac)
        val k = (subLab.maxOrNull() ?: 0) + 1
        val cents = centroids(sub, subLab, k)
        return IntArray(n) { i -> nearest(x[i], cents) }
    }

    private fun centreAndNormalise(emb: List<FloatArray>): Array<FloatArray> {
        val n = emb.size
        val dim = emb[0].size
        val mean = DoubleArray(dim)
        for (e in emb) for (d in 0 until dim) mean[d] += e[d].toDouble()
        //for (d in 0 until dim) mean[d] /= n
        for (d in 0 until dim) {
            mean[d] = mean[d] / n
        }
        return Array(n) { i ->
            val v = DoubleArray(dim)
            var s = 0.0
            for (d in 0 until dim) { v[d] = emb[i][d] - mean[d]; s += v[d] * v[d] }
            val nrm = sqrt(s) + 1e-9
            FloatArray(dim) { d -> (v[d] / nrm).toFloat() }
        }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** Average-linkage dendrogram cut at [thr] (merge iff distance < thr). Returns compact labels 0..k-1. */
    private fun upgma(x: Array<FloatArray>, thr: Float): IntArray {
        val n = x.size
        val d = FloatArray(n * n)
        for (i in 0 until n) for (j in i + 1 until n) {
            val v = 1f - dot(x[i], x[j])
            d[i * n + j] = v; d[j * n + i] = v
        }
        val active = BooleanArray(n) { true }
        val size = IntArray(n) { 1 }
        val uf = IntArray(n) { it }
        fun find(a: Int): Int { var r = a; while (uf[r] != r) r = uf[r]; var c = a; while (uf[c] != r) { val nx = uf[c]; uf[c] = r; c = nx }; return r }
        val root = IntArray(n) { it }           // union-find representative of every live cluster slot
        val chain = IntArray(n)
        var cl = 0
        var remaining = n
        while (remaining > 1) {
            if (cl == 0) { var s = 0; while (!active[s]) s++; chain[cl++] = s }
            while (true) {
                val a = chain[cl - 1]
                val prev = if (cl > 1) chain[cl - 2] else -1
                var b = prev
                var best = if (prev >= 0) d[a * n + prev] else Float.MAX_VALUE
                for (j in 0 until n) if (active[j] && j != a) {
                    val v = d[a * n + j]
                    if (v < best) { best = v; b = j }
                }
                if (b == prev) {                            // reciprocal nearest neighbours -> merge a into b
                    cl -= 2
                    if (best < thr) { val ra = find(root[a]); val rb = find(root[b]); if (ra != rb) uf[ra] = rb; root[b] = find(rb) }
                    val sa = size[a].toFloat(); val sb = size[b].toFloat()
                    for (k in 0 until n) if (active[k] && k != a && k != b) {
                        val nd = (sa * d[a * n + k] + sb * d[b * n + k]) / (sa + sb)
                        d[b * n + k] = nd; d[k * n + b] = nd
                    }
                    size[b] += size[a]; active[a] = false; remaining--
                    break
                } else chain[cl++] = b
            }
        }
        val ids = HashMap<Int, Int>()
        return IntArray(n) { i -> ids.getOrPut(find(i)) { ids.size } }
    }

    private fun centroids(x: Array<FloatArray>, lab: IntArray, k: Int): Array<FloatArray> {
        val dim = x[0].size
        val c = Array(k) { DoubleArray(dim) }
        val cnt = IntArray(k)

        for (i in x.indices) {
            val clusterIdx = lab[i]
            cnt[clusterIdx]++
            val xRow = x[i]
            val cRow = c[clusterIdx]
            for (d in 0 until dim) {
                cRow[d] += xRow[d].toDouble()
            }
        }

        return Array(k) { j ->
            var s = 0.0
            val cRow = c[j]
            val divisor = maxOf(cnt[j], 1).toDouble()
            for (d in 0 until dim) {
                cRow[d] /= divisor
                s += cRow[d] * cRow[d]
            }
            val nrm = sqrt(s)
            FloatArray(dim) { d -> (cRow[d] / (if (nrm > 0) nrm else 1.0)).toFloat() }
        }
    }

    private fun nearest(v: FloatArray, cents: Array<FloatArray>): Int {
        var b = 0; var best = -Float.MAX_VALUE
        for (j in cents.indices) { val s = dot(v, cents[j]); if (s > best) { best = s; b = j } }
        return b
    }

    private fun mergeSmall(x: Array<FloatArray>, lab: IntArray, minFrac: Float): IntArray {
        val k = (lab.maxOrNull() ?: 0) + 1
        val cnt = IntArray(k)
        for (l in lab) cnt[l]++
        var big = (0 until k).filter { cnt[it] >= minFrac * lab.size }
        if (big.isEmpty()) big = listOf(cnt.indices.maxByOrNull { cnt[it] }!!)
        if (big.size == k) return lab
        val cents = centroids(x, lab, k)
        val bigCents = Array(big.size) { cents[big[it]] }
        val out = IntArray(lab.size) { i -> val pos = big.indexOf(lab[i]); if (pos >= 0) pos else nearest(x[i], bigCents) }
        return out
    }
}
