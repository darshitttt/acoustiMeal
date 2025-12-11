package com.example.pannsonnx

import android.content.Context
import android.util.Log // Import Log for logging
import ai.onnxruntime.*
import android.content.ContentValues.TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.io.IOException // Import IOException for specific catch
import org.apache.commons.math3.transform.DftNormalization
import org.apache.commons.math3.transform.FastFourierTransformer
import org.apache.commons.math3.transform.TransformType
import org.apache.commons.math3.complex.Complex
import kotlin.math.*


class ONNXModelProcessor(private val context: Context) {

    private lateinit var ortSession: OrtSession // Changed to lateinit to enforce non-null after init
    private lateinit var ortEnvironment: OrtEnvironment // Changed to lateinit


    init {
        initializeModel()
    }

    private fun initializeModel() {
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()

            // Load model from assets folder
            val modelBytes = context.assets.open("epanns_Cnn14_pruned.onnx").readBytes()

            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.addCPU(false) // Use CPU

            ortSession = ortEnvironment.createSession(modelBytes, sessionOptions) // No longer nullable call
            Log.i("ONNXModelProcessor", "ONNX model loaded successfully from assets.")
        } catch (e: IOException) {
            // Specific catch for file not found or asset stream issues
            Log.e("ONNXModelProcessor", "Failed to load ONNX model from assets. Make sure 'pann_onnx.onnx' is in src/main/assets/: ${e.message}", e)
            throw RuntimeException("Failed to load ONNX model: ${e.message}", e) // Critical error, re-throw
        } catch (e: OrtException) {
            // Specific catch for ONNX Runtime errors (e.g., model parsing, unsupported ops)
            Log.e("ONNXModelProcessor", "ONNX Runtime error during model initialization: ${e.message}", e)
            throw RuntimeException("ONNX Runtime initialization error: ${e.message}", e) // Critical error, re-throw
        } catch (e: Exception) {
            // General catch for any other unexpected errors
            Log.e("ONNXModelProcessor", "Unknown error initializing ONNX model: ${e.message}", e)
            throw RuntimeException("Unknown error during ONNX model initialization: ${e.message}", e) // Critical error, re-throw
        }
    }

    suspend fun predict(audioData: ShortArray): List<AudioTag> = withContext(Dispatchers.IO) {
        // Since ortSession is now lateinit and initialized in init, it should not be null here.
        // If it was null, initializeModel would have thrown an exception.
        try {
            // Convert audio data to float array and normalize
            val floatAudio = audioData.map { it.toFloat() / Short.MAX_VALUE }.toFloatArray()
            val numMelBins = 128
            val targetLength = 1024
            //val features = makeFeatures(floatAudio, 16000, numMelBins, targetLength)

            // Create input tensor (adjust shape based on your model requirements)
            val inputName = ortSession.inputNames.iterator().next()
            val shape = longArrayOf(1, floatAudio.size.toLong()) // Batch size 1

            val inputTensor = OnnxTensor.createTensor(
                ortEnvironment,
                FloatBuffer.wrap(floatAudio),
                //FloatBuffer.wrap(features),
                shape
            )

            // Run inference
            val inputs = mapOf(inputName to inputTensor)
            val results = ortSession.run(inputs) // No longer nullable call

            //Log.d(TAG, "Predictions01 ${results[1]}")
            //
            // Process output
            val outputTensorValue = results[0].value // This will be float[][]
            val output2DArray = outputTensorValue as Array<FloatArray> // Cast to 2D array

            val predictions = output2DArray[0]


            // Convert to AudioTag list with labels
            val tags = mutableListOf<AudioTag>()
            val labels = getAudioLabels() // You'll need to implement this based on your model

            predictions.forEachIndexed { index, confidence ->
                if (index < labels.size && confidence > 0.02f) { // Threshold
                    tags.add(AudioTag(labels[index], confidence))
                    //Log.d(TAG, "Predictions $index")
                }
            }

            // Sort by confidence and return top results
            tags.sortedByDescending { it.confidence }.take(10)

        } catch (e: Exception) {
            // Log the error for debugging
            Log.e("ONNXModelProcessor", "Error during ONNX prediction: ${e.message}", e)
            emptyList()
        }
    }


    fun cleanup() {
        try {
            // These are lateinit, so no need for '?' operator, but call only if initialized
            if (::ortSession.isInitialized) ortSession.close()
            if (::ortEnvironment.isInitialized) ortEnvironment.close()
            Log.i("ONNXModelProcessor", "ONNX session and environment closed.")
        } catch (e: Exception) {
            Log.e("ONNXModelProcessor", "Error closing ONNX resources: ${e.message}", e)
        }
    }
}
