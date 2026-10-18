package com.example.pannsonnx

import org.json.JSONObject
import kotlin.math.roundToInt

/** Reads assets/deploy_config.json written by mealaudio.export.export_onnx. */
data class DeployConfig(
    val sampleRate: Int,
    val windowS: Double,
    val hopS: Double,
    val melFrames: Int,
    val classes: List<String>,
    val groups: List<String>,
    val speechIndex: Int,
    val speechThreshold: Float,
    val tagThresholds: FloatArray,
    val overlapThreshold: Float?,
    val embeddingDim: Int,
    val clusterThreshold: Float,
    val mergeFraction: Float
) {
    val windowSamples: Int get() = (windowS * sampleRate).roundToInt()
    val hopSamples: Int get() = (hopS * sampleRate).roundToInt()

    /** Class indices whose group is "meal" (falls back to an empty list if the export had no groups). */
    val mealIndices: IntArray
        get() = classes.indices.filter { groups.getOrNull(it)?.equals("meal", ignoreCase = true) == true }.toIntArray()

    companion object {
        fun parse(json: String): DeployConfig {
            val o = JSONObject(json)
            fun strings(key: String): List<String> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return List(a.length()) { a.getString(it) }
            }
            val classes = strings("tag_classes")
            val thrArr = o.optJSONArray("tag_thresholds_calibrated_on_2s_windows")
            val thr = FloatArray(classes.size) { i -> thrArr?.optDouble(i, 0.5)?.toFloat() ?: 0.5f }
            val clu = o.optJSONObject("clustering")
            return DeployConfig(
                sampleRate = o.getInt("sample_rate"),
                windowS = o.getDouble("window_s"),
                hopS = o.getDouble("hop_s"),
                melFrames = o.getInt("mel_frames"),
                classes = classes,
                groups = strings("tag_groups"),
                speechIndex = o.optInt("speech_index", -1),
                speechThreshold = o.optDouble("speech_threshold", 0.2).toFloat(),
                tagThresholds = thr,
                overlapThreshold = if (o.isNull("overlap_threshold")) null else o.optDouble("overlap_threshold", 0.5).toFloat(),
                embeddingDim = o.optInt("embedding_dim", 0),
                clusterThreshold = (clu?.optDouble("distance_threshold", 0.8) ?: 0.8).toFloat(),
                mergeFraction = (clu?.optDouble("merge_clusters_below_fraction", 0.05) ?: 0.05).toFloat()
            )
        }
    }
}
