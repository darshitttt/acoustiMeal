package com.example.pannsonnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/** Result of one 3 s window. [embedding] is null unless the Speech tag fired (speaker graph is skipped otherwise). */
class WindowOutput(
    val tagProbs: FloatArray,
    val overlapProb: FloatArray?,
    val overlapFraction: Float,
    val embedding: FloatArray?
)

/**
 * Replaces ONNXModelProcessor. Two modes, chosen by which model files are in assets/:
 *   TAGS ONLY : tags_int8.onnx                                          mel -> tag_probs   (every window)
 *   FULL      : always_on_int8.onnx + speaker_int8.onnx                 mel -> tag_probs, overlap_prob, map_* (every window)
 *                                                                       mel, map_* -> spk_emb (only when Speech >= speech_threshold)
 * Also needed: deploy_config.json, mel_basis.bin, window.bin (all produced by mealaudio.export; copy into src/main/assets/).
 */
class JointModelProcessor(context: Context) {

    val config: DeployConfig
    private val mel: MelFrontEnd
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessA: OrtSession
    private val sessB: OrtSession?
    private val outNamesA: List<String>
    private val mapNames: List<String>
    private val iTag: Int
    private val iOv: Int
    private val overlapFirst: Int
    private val overlapCount: Int
    private var closed = false

    init {
        val am = context.assets
        config = DeployConfig.parse(am.open("deploy_config.json").bufferedReader().use { it.readText() })
        mel = MelFrontEnd(am.open("mel_basis.bin").readBytes(), am.open("window.bin").readBytes())

        fun options() = OrtSession.SessionOptions().apply {
            addCPU(false)
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val tagsOnly = am.list("")?.contains("tags_int8.onnx") == true
        if (tagsOnly) {
            sessA = env.createSession(am.open("tags_int8.onnx").readBytes(), options())
            sessB = null
        } else {
            sessA = env.createSession(am.open("always_on_int8.onnx").readBytes(), options())
            sessB = env.createSession(am.open("speaker_int8.onnx").readBytes(), options())
        }

        outNamesA = sessA.outputNames.toList()
        iTag = outNamesA.indexOf("tag_probs")
        iOv = outNamesA.indexOf("overlap_prob")
        mapNames = outNamesA.filter { it.startsWith("map_") }
        check(iTag >= 0) { "graph has no 'tag_probs' output: $outNamesA" }
        sessB?.let { b ->
            check(b.inputNames.toSet() == (mapNames + "mel").toSet()) {
                "speaker graph inputs ${b.inputNames} do not match always_on maps $mapNames"
            }
        }
        val fps = 25                                    // overlap head output rate (40 ms frames)
        overlapFirst = ((config.windowS - config.hopS) / 2.0 * fps).roundToInt()
        overlapCount = (config.hopS * fps).roundToInt()
        Log.i(TAG, "Models loaded (${if (tagsOnly) "tags only" else "full"}): ${config.classes.size} tags, window ${config.windowS}s / hop ${config.hopS}s, " +
                "overlap=${iOv >= 0}, maps=$mapNames")
    }

    /** window: exactly config.windowSamples mono samples in [-1,1] at config.sampleRate. Call from a background thread. */
    @Synchronized
    fun process(window: FloatArray): WindowOutput {
        check(!closed) { "model processor is closed" }
        require(window.size == config.windowSamples) { "expected ${config.windowSamples} samples, got ${window.size}" }
        val melData = mel.compute(window)
        check(melData.size == 128 * config.melFrames) { "mel frames ${melData.size / 128} != ${config.melFrames}" }
        val shape = longArrayOf(1, 1, 128, config.melFrames.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(melData), shape).use { melT ->
            sessA.run(mapOf("mel" to melT)).use { ra ->
                @Suppress("UNCHECKED_CAST")
                val tag = (ra[iTag].value as Array<FloatArray>)[0]
                @Suppress("UNCHECKED_CAST")
                val ov = if (iOv >= 0) (ra[iOv].value as Array<FloatArray>)[0] else null

                var emb: FloatArray? = null
                if (sessB != null && config.speechIndex >= 0 && tag[config.speechIndex] >= config.speechThreshold) {
                    val feeds = HashMap<String, OnnxTensor>()
                    feeds["mel"] = melT
                    for (n in mapNames) feeds[n] = ra[outNamesA.indexOf(n)] as OnnxTensor
                    sessB!!.run(feeds).use { rb ->
                        @Suppress("UNCHECKED_CAST")
                        emb = (rb[0].value as Array<FloatArray>)[0]
                    }
                }
                return WindowOutput(tag.copyOf(), ov?.copyOf(), overlapFraction(ov), emb)
            }
        }
    }

    private fun overlapFraction(ov: FloatArray?): Float {
        val thr = config.overlapThreshold
        if (ov == null || thr == null || ov.size < overlapFirst + overlapCount) return 0f
        var c = 0
        for (i in 0 until overlapCount) if (ov[overlapFirst + i] >= thr) c++
        return c.toFloat() / overlapCount
    }

    @Synchronized
    fun close() {
        closed = true
        try { sessA.close(); sessB?.close(); env.close() } catch (e: Exception) { Log.e(TAG, "close failed: ${e.message}") }
    }

    private companion object { const val TAG = "JointModelProcessor" }
}
