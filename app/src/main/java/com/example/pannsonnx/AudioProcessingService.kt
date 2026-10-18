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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

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

    private lateinit var modelProcessor: JointModelProcessor
    private lateinit var diarAcc: DiarizationAccumulator
    private lateinit var cfg: DeployConfig
    private var mealIdx: IntArray = IntArray(0)
    private var recStartMs: Long = 0L           // wall-clock time of the first recorded sample of this recording
    // diarization results are written on this scope so they survive the service being destroyed right after stop
    private val finalizeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

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
        modelProcessor = JointModelProcessor(this)
        cfg = modelProcessor.config
        require(cfg.sampleRate == sampleRate) { "model expects ${cfg.sampleRate} Hz, service records at $sampleRate Hz" }
        diarAcc = DiarizationAccumulator(cfg.windowS, cfg.hopS)
        mealIdx = cfg.mealIndices.takeIf { it.isNotEmpty() }
            ?: cfg.classes.indices.filter { i -> kitchenRelatedTags.any { cfg.classes[i].contains(it, ignoreCase = true) } }.toIntArray()
        Log.i(TAG, "Meal tags: ${mealIdx.map { cfg.classes[it] }}")
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
        diarAcc.discardBefore(Double.MAX_VALUE)

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
            recStartMs = System.currentTimeMillis()
            Log.i(TAG, "AudioRecord running at ${audioRecord?.sampleRate} Hz (requested $sampleRate Hz)")
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
        val readBuffer = ShortArray(sampleRate / 10)                       // ~100 ms per blocking read
        val windower = AudioWindower(cfg.windowSamples, cfg.hopSamples, readBuffer.size)
        val window = FloatArray(cfg.windowSamples)

        while (isRecording && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            val n = audioRecord?.read(readBuffer, 0, readBuffer.size) ?: 0   // blocks until audio is available
            if (n < 0) {
                Log.e(TAG, "AudioRecord.read failed: $n")
                break
            }
            windower.push(readBuffer, n)
            // one window every hop (1 s): window k covers samples [k*hop, k*hop + windowSamples)
            while (windower.hasWindow()) {
                val k = windower.nextWindow(window)
                processWindow(window, k)
            }

            val currentTime = System.currentTimeMillis()
            if (mealStartTime != null && currentTime - lastKitchenTagTime > MEAL_SESSION_TIMEOUT_MS) {
                mealEndTime = lastKitchenTagTime

                if (!shouldSaveTags) {
                    writeMealTimesToFile(mealStartTime, mealEndTime)
                }
                writeTagsToFile(allTagsInSession)
                allTagsInSession.clear()
                val mealS = mealStartTime
                val mealE = mealEndTime
                if (mealS != null && mealE != null) finalizeDiarization(mealS, mealE)
                withContext(Dispatchers.Main) {
                    onMealTimeUpdatedListener?.invoke(mealStartTime, mealEndTime)
                }
                mealStartTime = null
                mealEndTime = null
                lastKitchenTagTime = 0L
            }
        }
    }

    /** Class indices whose probability reached their calibrated threshold, strongest first (max 10). */
    private fun detectedTags(p: FloatArray): List<AudioTag> {
        val r = p.indices.filter { cfg.groups.getOrNull(it) != "confuser" && p[it] >= cfg.tagThresholds[it] }
            .sortedByDescending { p[it] }.take(10).map { AudioTag(cfg.classes[it], p[it]) }
        return if (r.isEmpty()) listOf(AudioTag("Others", 1f)) else r
    }

    private suspend fun processWindow(window: FloatArray, windowIndex: Long) {
        val t0 = System.nanoTime()
        val out = try { modelProcessor.process(window) } catch (e: Exception) { Log.e(TAG, "Inference failed: ${e.message}", e); return }

        if (windowIndex % 10L == 0L) Log.i(TAG, "window $windowIndex inference ${(System.nanoTime() - t0) / 1e6} ms")

        if (out.embedding != null || out.overlapProb != null)
            diarAcc.add(windowIndex, out.embedding, out.overlapProb, cfg.overlapThreshold ?: 0.5f)

        val detected = detectedTags(out.tagProbs)
        val shown = ArrayList(detected)
        if (out.overlapFraction > 0f) shown.add(AudioTag("Overlapping speech (>=2 speakers)", out.overlapFraction))

        withContext(Dispatchers.Main) {
            currentTags.clear()
            currentTags.addAll(shown)
            onTagsUpdatedListener?.invoke(currentTags)

            if (shouldSaveTags) {
                writeTagsToFile(detected)
            }

            val hasMealTags = mealIdx.any { out.tagProbs[it] >= cfg.tagThresholds[it] }
            if (hasMealTags) {
                val currentTime = System.currentTimeMillis()
                lastKitchenTagTime = currentTime

                if (mealStartTime == null) {
                    mealStartTime = currentTime
                }
                mealEndTime = null

                onMealTimeUpdatedListener?.invoke(mealStartTime, mealEndTime)
            }
            allTagsInSession.addAll(detected)
        }
    }

    // ---------------------------------------------------------------- diarization output
    /** Clusters the speech windows of [fromMs, toMs] and writes speakers_*.csv and overlap_*.csv (Documents/AcoustiMeal). */
    private fun finalizeDiarization(fromMs: Long, toMs: Long) {
        val startMs = recStartMs
        val fromS = (fromMs - startMs) / 1000.0
        val toS = (toMs - startMs) / 1000.0
        finalizeScope.launch {
            try {
                val res = diarAcc.finalize(fromS, toS, cfg.clusterThreshold, cfg.mergeFraction)
                diarAcc.discardBefore(toS)
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(fromMs))
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                val abs = { sec: Double -> fmt.format(Date(startMs + (sec * 1000).toLong())) }
                if (res.speakers.isNotEmpty()) {
                    openDocWriter("speakers_$stamp.csv")?.use { w ->
                        w.write("start,end,speaker\n")
                        res.speakers.forEach { w.write("${abs(it.start)},${abs(it.end)},SPK${it.speaker}\n") }
                    }
                }
                if (res.overlap.isNotEmpty()) {
                    openDocWriter("overlap_$stamp.csv")?.use { w ->
                        w.write("start,end\n")
                        res.overlap.forEach { w.write("${abs(it.first)},${abs(it.second)}\n") }
                    }
                }
                Log.i(TAG, "Diarization written: ${res.speakers.size} speaker turns, " +
                        "${res.speakers.map { it.speaker }.toSet().size} speakers, ${res.overlap.size} overlap segments")
            } catch (e: Exception) {
                Log.e(TAG, "Diarization failed: ${e.message}", e)
            }
        }
    }

    private fun openDocWriter(fileName: String, dirName: String = "AcoustiMeal"): BufferedWriter? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = applicationContext.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + File.separator + dirName)
                }
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                if (uri == null) { Log.e(TAG, "MediaStore insert failed for $fileName"); null }
                else BufferedWriter(OutputStreamWriter(resolver.openOutputStream(uri)))
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), dirName)
                if (!dir.exists() && !dir.mkdirs()) { Log.e(TAG, "Cannot create $dir"); null }
                else BufferedWriter(FileWriter(File(dir, fileName), true))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open $fileName: ${e.message}", e)
            null
        }
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

    suspend fun triggerServerTaskNative(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://wifo5-30.informatik.uni-mannheim.de:7443/trigger")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json; utf-8")
                connection.setRequestProperty("Accept", "application/json")
                connection.doOutput = true

                val jsonInputString = """{"participant_code":"3iwqs0vo","meal_probability":0.87}"""

                // Write body data
                connection.outputStream.use { os ->
                    val input = jsonInputString.toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                }

                // Read response
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    "Error: $responseCode"
                }
            } catch (e: Exception) {
                "Exception: ${e.localizedMessage}"
            }
        }
    }

    fun stopProcessing() {
        if (!isRecording) return

        handler?.removeCallbacksAndMessages(null)

        if (mealStartTime != null){
            // Launch a coroutine using your serviceScope
            serviceScope.launch {
                val response = triggerServerTaskNative() // Replace with your actual server IP/port
                Log.d("GESIS_Server", "Server response: $response")
            }
        }

        if (mealStartTime != null && mealEndTime == null) {
            mealEndTime = System.currentTimeMillis()
            val mealS = mealStartTime
            val mealE = mealEndTime
            if (mealS != null && mealE != null) finalizeDiarization(mealS, mealE)
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
        if (::modelProcessor.isInitialized) modelProcessor.close()
    }
}
