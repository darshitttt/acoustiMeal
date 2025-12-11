// There are still some problems with the app
/*
1. Even if the app is not currently recording, the UI says it is recording
2. The meal times are saved if we close the app mid meal
3. might need to come up with an efficient way to save all_tags
4. will have to check real-time, having a meal.
    4a. in fact, will have to check at least twice

    meal detected, stop button, not storing the start and end of the meal!!!
 */


package com.example.pannsonnx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.example.pannsonnx.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList

data class AudioTagSummary(val summary: String, val tags: List<AudioTag>)

class AudioProcessingService : Service() {

    private val binder = AudioServiceBinder()
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null

    private var handler: Handler? = null

    private val stopServiceRunnable = Runnable {
        stopProcessing()
        Toast.makeText(this, "Audio Processing automatically stopped after 2 hours.", Toast.LENGTH_LONG).show()
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sampleRate = 32000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    private var onTagsUpdatedListener: ((List<AudioTag>) -> Unit)? = null
    private var onMealTimeUpdatedListener: ((Long?, Long?) -> Unit)? = null
    private var onSummaryGeneratedListener: ((AudioTagSummary) -> Unit)? = null // New listener for the summary
    private var onRecordingStatusChangedListener: ((Boolean) -> Unit)? = null // New listener for recording status

    private var mealStartTime: Long? = null
    private var mealEndTime: Long? = null
    private val allTagsInSession = mutableListOf<AudioTag>()
    private val currentTags = mutableListOf<AudioTag>()

    private val MEAL_SESSION_TIMEOUT_MS = 5 * 60 * 1000L
    private var lastKitchenTagTime: Long = 0L

    private val kitchenRelatedTags = setOf(
        "cooking", "Dishes", "frying", "boiling", "chopping", "microwave",
        "blender", "mixer", "dishwasher", "cutlery", "plate", "glass",
        "water running", "sizzling", "bubbling", "eating", "chewing", "food"
    )

    private lateinit var onnxModelProcessor: ONNXModelProcessor

    private var shouldSaveTags = false
    private var tagFileWriter: BufferedWriter? = null
    private var mealTimeFileWriter: BufferedWriter? = null

    private val outputDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val TAG = "AudioProcessingService"

    inner class AudioServiceBinder : Binder() {
        fun getService(): AudioProcessingService = this@AudioProcessingService
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        onnxModelProcessor = ONNXModelProcessor(this)
        handler = Handler(Looper.getMainLooper())
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setOnTagsUpdatedListener(listener: (List<AudioTag>) -> Unit) {
        onTagsUpdatedListener = listener
    }

    fun setOnMealTimeUpdatedListener(listener: (Long?, Long?) -> Unit) {
        onMealTimeUpdatedListener = listener
    }

    fun setOnSummaryGeneratedListener(listener: (AudioTagSummary) -> Unit) {
        onSummaryGeneratedListener = listener
    }

    // New function to set the recording status listener
    fun setOnRecordingStatusChangedListener(listener: (Boolean) -> Unit) {
        onRecordingStatusChangedListener = listener
    }

    fun setShouldSaveTags(save: Boolean) {
        shouldSaveTags = save
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun startProcessing(startHour: Int, startMinute: Int) {
        if (isRecording) {
            Toast.makeText(this, "Audio processing is already active.", Toast.LENGTH_SHORT).show()
            return
        }

        val calendar = Calendar.getInstance()
        val currentTime = System.currentTimeMillis()

        calendar.set(Calendar.HOUR_OF_DAY, startHour)
        calendar.set(Calendar.MINUTE, startMinute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        val targetTime = calendar.timeInMillis
        val endTime = targetTime + 2 * 60 * 60 * 1000L // 2 hours in milliseconds

        // Check if current time is within the two-hour window
        if (currentTime >= targetTime && currentTime <= endTime) {
            val delayToEnd = endTime - currentTime
            startRecording()
            handler?.postDelayed(stopServiceRunnable, delayToEnd)
            Toast.makeText(this, "Recording started immediately and will stop at ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(endTime))}.", Toast.LENGTH_LONG).show()
            Log.d(TAG, "Recording started immediately. Will stop in $delayToEnd ms.")
        } else {
            // Schedule the service to start at the target time.
            val delayToStart = if (targetTime <= currentTime) {
                // If the target time has passed today, schedule for tomorrow.
                targetTime + 24 * 60 * 60 * 1000L - currentTime
            } else {
                // If the target time is in the future today, schedule for today.
                targetTime - currentTime
            }

            // Create a Runnable that will start the recording and schedule the stop.
            val startServiceRunnable = Runnable {
                startRecording()
                handler?.postDelayed(stopServiceRunnable, 2 * 60 * 60 * 1000L)
                Toast.makeText(this, "Recording started as scheduled.", Toast.LENGTH_LONG).show()
            }

            Log.d(TAG, "Scheduled to start in $delayToStart ms.")
            handler?.postDelayed(startServiceRunnable, delayToStart)
            Toast.makeText(this, "Scheduled to start at ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(targetTime))}.", Toast.LENGTH_LONG).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startRecording() {
        isRecording = true
        mealStartTime = null
        mealEndTime = null
        currentTags.clear()
        allTagsInSession.clear()
        lastKitchenTagTime = 0L

        startForeground(1, createNotification())

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Toast.makeText(this, "AudioRecord initialization failed. Ensure permissions.", Toast.LENGTH_LONG).show()
                stopProcessing()
                return
            }

            audioRecord?.startRecording()
            // Notify the UI that recording has actually started.

            serviceScope.launch(Dispatchers.Main) {
                onRecordingStatusChangedListener?.invoke(true)
            }

            recordingJob = serviceScope.launch {
                processAudioStream()
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
            Toast.makeText(this, "Permission denied for audio recording. Please grant it in app settings.", Toast.LENGTH_LONG).show()
            stopProcessing()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error starting audio recording: ${e.message}", Toast.LENGTH_LONG).show()
            stopProcessing()
        }
    }

    private suspend fun processAudioStream() = withContext(Dispatchers.IO) {
        val audioBuffer = ShortArray(sampleRate * 2)
        var bufferIndex = 0
        val readBuffer = ShortArray(bufferSize)

        while (isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val bytesRead = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: 0

            if (bytesRead > 0) {
                for (i in 0 until minOf(bytesRead, audioBuffer.size - bufferIndex)) {
                    audioBuffer[bufferIndex + i] = readBuffer[i]
                }
                bufferIndex += bytesRead

                if (bufferIndex >= audioBuffer.size) {
                    processAudioChunk(audioBuffer.clone())
                    bufferIndex = 0
                }
            }

            val currentTime = System.currentTimeMillis()
            if (mealStartTime != null && currentTime - lastKitchenTagTime > MEAL_SESSION_TIMEOUT_MS) {
                mealEndTime = lastKitchenTagTime

                if (!shouldSaveTags) {
                    writeMealTimesToFile(mealStartTime, mealEndTime)
                }
                writeTagsToFile(allTagsInSession)
                allTagsInSession.clear()
                withContext(Dispatchers.Main) {
                    onMealTimeUpdatedListener?.invoke(mealStartTime, mealEndTime)
                }
                mealStartTime = null
                mealEndTime = null
                lastKitchenTagTime = 0L
            }

            delay(100)
        }
    }

    private suspend fun processAudioChunk(audioData: ShortArray) = try {
        val predictions = onnxModelProcessor.predict(audioData)
        withContext(Dispatchers.Main) {
            currentTags.clear()
            currentTags.addAll(predictions)
            onTagsUpdatedListener?.invoke(currentTags)

            if (shouldSaveTags) {
                writeTagsToFile(predictions)
            }

            val hasKitchenTags = predictions.any { tag ->
                kitchenRelatedTags.any { kitchenTag ->
                    tag.label.contains(kitchenTag, ignoreCase = true)
                } && tag.confidence > 0.02f
            }

            if (hasKitchenTags) {
                val currentTime = System.currentTimeMillis()
                lastKitchenTagTime = currentTime

                if (mealStartTime == null) {
                    mealStartTime = currentTime
                }
                mealEndTime = null

                onMealTimeUpdatedListener?.invoke(mealStartTime, mealEndTime)
            }
            allTagsInSession.addAll(predictions)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        Toast.makeText(this@AudioProcessingService, "Error processing audio chunk: ${e.message}", Toast.LENGTH_SHORT).show()
    }

    private fun writeTagsToFile(tags: List<AudioTag>) {
        if (tagFileWriter == null) {
            try {
                val fileName = "audio_tags_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = applicationContext.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + File.separator + "AcoustiMeal")
                    }

                    val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                    if (uri != null) {
                        tagFileWriter = BufferedWriter(OutputStreamWriter(resolver.openOutputStream(uri)))
                    } else {
                        Log.e(TAG, "Failed to create new MediaStore entry.")
                        return
                    }
                } else {
                    val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    val appDocsDir = File(documentsDir, "AcoustiMeal")

                    if (!appDocsDir.exists()) {
                        if (!appDocsDir.mkdirs()) {
                            Log.e(TAG, "Failed to create directory for saving tags.")
                            return
                        }
                    }

                    val file = File(appDocsDir, fileName)
                    tagFileWriter = BufferedWriter(FileWriter(file, true))
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error creating tag file writer: ${e.message}", e)
                return
            }
        }

        try {
            val timestamp = outputDateFormat.format(Date())
            tags.forEach { tag ->
                tagFileWriter?.write("$timestamp, ${tag.label}, ${tag.confidence}\n")
            }
            tagFileWriter?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Error writing tags to file: ${e.message}", e)
            try {
                tagFileWriter?.close()
                tagFileWriter = null
            } catch (closeEx: IOException) {
                Log.e(TAG, "Error closing file writer after write error: ${closeEx.message}")
            }
        }
    }

    private fun writeMealTimesToFile(startTime: Long?, endTime: Long?) {
        if (startTime == null || endTime == null) return

        val durationMs = endTime - startTime
        val minDuratinMs = 5*60*1000L

        if (durationMs < minDuratinMs) return

        if (mealTimeFileWriter == null) {
            try {
                val fileName = "meal_times_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.txt"

                if (Build.VERSION_CODES.Q <= Build.VERSION.SDK_INT) {
                    val resolver = applicationContext.contentResolver
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + File.separator + "PannsOnnx")
                    }

                    val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
                    if (uri != null) {
                        mealTimeFileWriter = BufferedWriter(OutputStreamWriter(resolver.openOutputStream(uri)))
                    } else {
                        Log.e(TAG, "Failed to create new MediaStore entry for meal times.")
                        return
                    }
                } else {
                    val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    val appDocsDir = File(documentsDir, "PannsOnnx")

                    if (!appDocsDir.exists()) {
                        if (!appDocsDir.mkdirs()) {
                            Log.e(TAG, "Failed to create directory for saving meal times.")
                            return
                        }
                    }

                    val file = File(appDocsDir, fileName)
                    mealTimeFileWriter = BufferedWriter(FileWriter(file, true))
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error creating meal time file writer: ${e.message}", e)
                return
            }
        }

        try {
            val startFormatted = outputDateFormat.format(Date(startTime))
            val endFormatted = outputDateFormat.format(Date(endTime))
            val line = "Meal Start: $startFormatted, Meal End: $endFormatted\n"
            mealTimeFileWriter?.write(line)
            mealTimeFileWriter?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Error writing meal times to file: ${e.message}", e)
            try {
                mealTimeFileWriter?.close()
                mealTimeFileWriter = null
            } catch (closeEx: IOException) {
                Log.e(TAG, "Error closing meal time writer after write error: ${closeEx.message}")
            }
        }
    }


    fun stopProcessing() {
        if (!isRecording) return

        handler?.removeCallbacksAndMessages(null)

        // This is the fix for problems 2 and 4.
        if (mealStartTime != null && mealEndTime == null) {
            mealEndTime = System.currentTimeMillis()
            if (shouldSaveTags) {
                writeMealTimesToFile(mealStartTime, mealEndTime)
                writeTagsToFile(allTagsInSession)
            }
            else {
                writeMealTimesToFile(mealStartTime, mealEndTime)
            }
        }

        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        tagFileWriter?.let {
            try {
                it.close()
                tagFileWriter = null
            } catch (e: IOException) {
                Log.e(TAG, "Error closing tag file writer: ${e.message}")
            }
        }

        mealTimeFileWriter?.let {
            try {
                it.close()
                mealTimeFileWriter = null
            } catch (e: IOException) {
                Log.e(TAG, "Error closing meal time file writer: ${e.message}")
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        mealStartTime = null
        mealEndTime = null
        lastKitchenTagTime = 0L
        onMealTimeUpdatedListener?.invoke(null, null)
        onTagsUpdatedListener?.invoke(emptyList())
        shouldSaveTags = false

        // This is the fix for problem 1.
        serviceScope.launch(Dispatchers.Main) {
            onRecordingStatusChangedListener?.invoke(false)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "AUDIO_PROCESSING",
            "Audio Processing",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotification(): Notification {
        return Notification.Builder(this, "AUDIO_PROCESSING")
            .setContentTitle("Meal Tracker")
            .setContentText("Recording and analyzing audio...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProcessing()
        serviceScope.cancel()
    }
}
