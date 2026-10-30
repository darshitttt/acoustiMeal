package com.example.pannsonnx

import android.content.Context
import android.util.Log
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Loads assets/meal_model.json (written by mealaudio.episode.train_episode) and binds it to the tag model's classes. */
object MealModelConfig {
    private const val TAG = "MealModelConfig"

    /** Returns null if the app ships no meal model (the service then keeps its old tag-timeout logic). */
    fun load(context: Context, cfg: DeployConfig): MealEpisodeModel? {
        if (context.assets.list("")?.contains("meal_model.json") != true) return null
        val o = JSONObject(context.assets.open("meal_model.json").bufferedReader().use { it.readText() })
        val hop = o.getDouble("frame_hop_s")
        check(abs(hop - cfg.hopS) < 1e-6) { "meal model frame hop $hop s != tag model hop ${cfg.hopS} s" }
        val thr = o.getJSONObject("thresholds")
        val fa = o.getJSONArray("features")
        val features = List(fa.length()) { i ->
            val f = fa.getJSONObject(i)
            val name = f.getString("class")
            val idx = cfg.classes.indexOf(name)
            check(idx >= 0) { "meal model uses class '$name' which the tag model does not have: ${cfg.classes}" }
            MealFeature(FeatureKind.valueOf(f.getString("kind").uppercase()), idx,
                thr.optDouble(name, 0.5).toFloat(), f.getDouble("mu").toFloat(), f.getDouble("sd").toFloat(), f.getDouble("coef").toFloat())
        }
        val post = o.getJSONObject("post")
        Log.i(TAG, "Meal episode model: ${features.size} features, window ${o.getDouble("window_s")} s every ${o.getDouble("stride_s")} s")
        return MealEpisodeModel(
            frameHopS = hop,
            winFrames = (o.getDouble("window_s") / hop).roundToInt(),
            strideFrames = (o.getDouble("stride_s") / hop).roundToInt(),
            features = features, intercept = o.getDouble("intercept").toFloat(),
            smoothN = post.getInt("smooth_n"), onThr = post.getDouble("on_thr").toFloat(), offThr = post.getDouble("off_thr").toFloat(),
            minOn = post.getInt("min_on"), minOff = post.getInt("min_off")
        )
    }
}
