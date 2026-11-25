package com.example.pannsonnx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AudioTagSummary(val summary: String, val tags: List<AudioTag>)

/**
 * Foreground microphone service. It is started by ScheduleManager (alarm) at the daily window start and stops itself
 * at the window end, so it needs no UI. The activity may bind to it only to display live tags.
 *
 * THREADING RULE (this is what removes the race conditions): every piece of session state (meal state, tag buffer,
 * file writers, model, AudioRecord) is owned by ONE coroutine, [runSession]. Nobody else touches it. The UI only
 * receives immutable snapshots posted to the main thread; stopping is "cancel the job", and cleanup runs in `finally`.
 */
class AudioProcessingService : Service() {

    companion object {
        const val ACTION_START = "com.example.pannsonnx.action.START"
        const val ACTION_STOP = "com.example.pannsonnx.action.STOP"
        const val ACTION_STATE_CHANGED = "com.example.pannsonnx.action.STATE_CHANGED"
        private const val CHANNEL_ID = "AUDIO_PROCESSING"
        private const val NOTIFICATION_ID = 1
        private const val WAITING_TEXT = "Waiting for the next recording window"
        private const val RECORDING_TEXT = "Recording and analyzing audio..."
        private const val OUTPUT_DIR = "AcoustiMeal"

        /** True from session start until cleanup finished. Readable from any thread. */
        @Volatile
        var isRunning = false
            private set

        /** True while the service is alive and foreground (waiting or recording). It keeps the mic privilege it got when started from the UI. */
        @Volatile
        var isArmed = false
            private set
    }

    private data class TimedTags(val ts: Long, val tags: List<AudioTag>)

    private val binder = AudioServiceBinder()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // diarization results are written on this scope so they survive the service being destroyed right after stop
    private val finalizeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var recordingJob: Job? = null

    private val sampleRate = 32000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    // Listeners are always invoked on the main thread.
    @Volatile private var onTagsUpdatedListener: ((List<AudioTag>) -> Unit)? = null
    @Volatile private var onMealTimeUpdatedListener: ((Long?, Long?) -> Unit)? = null
    @Volatile private var onSummaryGeneratedListener: ((AudioTagSummary) -> Unit)? = null
    @Volatile private var onRecordingStatusChangedListener: ((Boolean) -> Unit)? = null
    @Volatile private var shouldSaveTags = false

    // ---- session state: written ONLY by the recording coroutine ----------------------------------------------
    @Volatile private var mealStart: Long? = null          // volatile only so the UI can read it on bind
    private var lastMealTagMs = 0L
    private val mealTags = ArrayList<TimedTags>()           // tags of the meal in progress (only used if tag-saving is off)
    private var tagWriter: BufferedWriter? = null
    private var mealWriter: BufferedWriter? = null
    private lateinit var cfg: DeployConfig
    private lateinit var diarAcc: DiarizationAccumulator
    private var mealIdx = IntArray(0)
    private var episodeDetector: MealEpisodeDetector? = null   // meal / no-meal episode model (assets/meal_model.json); null = old tag-timeout logic
    private var episodeLogged = 0L                              // episode windows already written to the log
    private var episodeWriter: BufferedWriter? = null           // episode_log_*.csv: raw / smoothed probability and state per window
    private val recentTags = ArrayDeque<TimedTags>()            // recent tags, to back-fill a meal whose start is found retroactively
    private var recStartMs = 0L                             // wall-clock time of the first recorded sample
    private val outputDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    // -------------------------------------------------------------------------------------------------------------

    private val MEAL_SESSION_TIMEOUT_MS = 5 * 60 * 1000L
    private val MIN_MEAL_DURATION_MS = 5 * 60 * 1000L
    private val EPISODE_TAG_TIMEOUT_MS = 8 * 60 * 1000L          // episode mode safety net: end a meal when no meal-group tag fired for this long
    private val RECENT_TAGS_MS = 15 * 60 * 1000L                // must exceed the detector's start lag (about window/2 + (min_on-0.5) * stride)

    private val kitchenRelatedTags = setOf(
        "cooking", "Dishes", "frying", "boiling", "chopping", "microwave",
        "blender", "mixer", "dishwasher", "cutlery", "plate", "glass",
        "water running", "sizzling", "bubbling", "eating", "chewing", "food"
    )

    private val TAG = "AudioProcessingService"

    inner class AudioServiceBinder : Binder() {
        fun getService(): AudioProcessingService = this@AudioProcessingService
    }

    // ------------------------------------------------------------------------------------------ public (UI) API
    override fun onBind(intent: Intent?): IBinder = binder

    fun setOnTagsUpdatedListener(l: (List<AudioTag>) -> Unit) { onTagsUpdatedListener = l }
    fun setOnMealTimeUpdatedListener(l: (Long?, Long?) -> Unit) { onMealTimeUpdatedListener = l }
    fun setOnSummaryGeneratedListener(l: (AudioTagSummary) -> Unit) { onSummaryGeneratedListener = l }
    fun setOnRecordingStatusChangedListener(l: (Boolean) -> Unit) { onRecordingStatusChangedListener = l }
    fun setShouldSaveTags(save: Boolean) { shouldSaveTags = save }
    fun currentMealStart(): Long? = mealStart

    /** Call when the UI goes away so the service does not keep the activity alive. */
    fun clearListeners() {
        onTagsUpdatedListener = null
        onMealTimeUpdatedListener = null
        onSummaryGeneratedListener = null
        onRecordingStatusChangedListener = null
    }

    // ------------------------------------------------------------------------------------------------ lifecycle
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // UI already marked today as stopped. Stop recording but STAY ALIVE (armed) so tomorrow's window still has mic access.
            if (recordingJob?.isActive == true) recordingJob?.cancel()
            else if (!isArmed) stopSelf()
            return if (isArmed) START_STICKY else START_NOT_STICKY
        }

        // ACTION_START: from the UI (app visible -> Android grants mic access to this service instance) or from the alarm.
        // The service then stays alive; the alarm only sends it a new ACTION_START at the next window.
        startForeground(NOTIFICATION_ID, createNotification(WAITING_TEXT))
        isArmed = true

        val now = System.currentTimeMillis()
        val window = ScheduleManager.nextWindow(ScheduleStore(this), now)?.takeIf { it.startMs <= now }
        if (window != null && recordingJob?.isActive != true) beginRecording(window.endMs)
        return START_STICKY
    }

    private fun beginRecording(endMs: Long) {
        shouldSaveTags = ScheduleStore(this).saveTags
        isRunning = true
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(RECORDING_TEXT))
        notifyState(true)
        recordingJob = serviceScope.launch { runSession(endMs) }
    }

    /** The single owner of all session state. Cancelling this job (user stop) lands in `finally`. */
    private suspend fun runSession(endMs: Long) {
        var record: AudioRecord? = null
        var model: JointModelProcessor? = null
        try {
            model = JointModelProcessor(this)
            cfg = model.config
            require(cfg.sampleRate == sampleRate) { "model expects ${cfg.sampleRate} Hz, service records at $sampleRate Hz" }
            diarAcc = DiarizationAccumulator(cfg.windowS, cfg.hopS)
            mealIdx = cfg.mealIndices.takeIf { it.isNotEmpty() }
                ?: cfg.classes.indices.filter { i -> kitchenRelatedTags.any { cfg.classes[i].contains(it, ignoreCase = true) } }.toIntArray()
            Log.i(TAG, "Meal tags: ${mealIdx.map { cfg.classes[it] }}")
            episodeDetector = MealModelConfig.load(this, cfg)?.let { MealEpisodeDetector(it) }
            recentTags.clear()
            episodeLogged = 0L
            Log.i(TAG, if (episodeDetector != null) "Meal detection: episode model" else "Meal detection: legacy tag timeout")

            mealStart = null
            lastMealTagMs = 0L
            mealTags.clear()

            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)
            record = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                toast("AudioRecord initialization failed. Ensure permissions.")
                return
            }
            rec.startRecording()
            recStartMs = System.currentTimeMillis()
            Log.i(TAG, "AudioRecord running at ${rec.sampleRate} Hz (requested $sampleRate Hz), until ${Date(endMs)}")

            readLoop(rec, model, endMs)
            if (System.currentTimeMillis() >= endMs) toast("Recording window finished.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission denied", e)
            toast("Permission denied for audio recording. Please grant it in app settings.")
        } catch (e: Exception) {
            Log.e(TAG, "Session failed: ${e.message}", e)
            toast("Error during audio processing: ${e.message}")
        } finally {
            withContext(NonCancellable) { finishSession(record, model, endMs) }
        }
    }

    private suspend fun readLoop(rec: AudioRecord, model: JointModelProcessor, endMs: Long) {
        val readBuffer = ShortArray(sampleRate / 10)                       // ~100 ms per blocking read
        val windower = AudioWindower(cfg.windowSamples, cfg.hopSamples, readBuffer.size)
        val window = FloatArray(cfg.windowSamples)
        var checkUntil = System.currentTimeMillis() + 5_000L   // is Android feeding us real audio or silence?
        var peak = 0

        while (System.currentTimeMillis() < endMs && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            currentCoroutineContext().ensureActive()                       // user pressed Stop -> CancellationException
            val n = rec.read(readBuffer, 0, readBuffer.size)               // blocks until audio is available
            if (n < 0) {
                Log.e(TAG, "AudioRecord.read failed: $n")
                break
            }
            if (checkUntil > 0) {
                for (i in 0 until n) peak = maxOf(peak, kotlin.math.abs(readBuffer[i].toInt()))
                if (System.currentTimeMillis() > checkUntil) {
                    if (peak == 0) {
                        Log.e(TAG, "Microphone delivers only silence (Android blocked background mic access)")
                        toast("Microphone is silenced by Android. Exempt the app from battery optimization and re-open it.")
                    }
                    checkUntil = 0
                }
            }
            windower.push(readBuffer, n)
            // one window every hop: window k covers samples [k*hop, k*hop + windowSamples)
            while (windower.hasWindow()) {
                val k = windower.nextWindow(window)
                processWindow(model, window, k)
            }
            checkMealTimeout()
        }
    }

    // ------------------------------------------------------------------------------------- per-window processing
    /** Class indices whose probability reached their calibrated threshold, strongest first (max 10). */
    private fun detectedTags(p: FloatArray): List<AudioTag> {
        val r = p.indices.filter { cfg.groups.getOrNull(it) != "confuser" && p[it] >= cfg.tagThresholds[it] }
            .sortedByDescending { p[it] }.take(10).map { AudioTag(cfg.classes[it], p[it]) }
        return if (r.isEmpty()) listOf(AudioTag("Others", 1f)) else r
    }

    private fun processWindow(model: JointModelProcessor, window: FloatArray, windowIndex: Long) {
        val t0 = System.nanoTime()
        val out = try { model.process(window) } catch (e: Exception) { Log.e(TAG, "Inference failed: ${e.message}", e); return }
        if (windowIndex % 10L == 0L) Log.i(TAG, "window $windowIndex inference ${(System.nanoTime() - t0) / 1e6} ms")

        if (out.embedding != null || out.overlapProb != null)
            diarAcc.add(windowIndex, out.embedding, out.overlapProb, cfg.overlapThreshold ?: 0.5f)

        val now = System.currentTimeMillis()
        val detected = detectedTags(out.tagProbs)

        val det = episodeDetector
        val episodeEvents = det?.push(out.tagProbs) ?: emptyList()

        // meal state machine
        if (det == null) {
            // legacy: any meal-group tag keeps the meal alive, a timeout ends it (see checkMealTimeout)
            if (mealIdx.any { out.tagProbs[it] >= cfg.tagThresholds[it] }) {
                lastMealTagMs = now
                if (mealStart == null) mealStart = now
                postMeal(mealStart, null)
            }
        } else {
            // evidence of eating-related sounds (same tags as the legacy logic): only used as a safety net, see checkMealTimeout
            if (mealIdx.any { out.tagProbs[it] >= cfg.tagThresholds[it] }) lastMealTagMs = now
            // episode model: start / end times arrive retroactively (seconds since the recording started)
            for (e in episodeEvents) {
                val tMs = recStartMs + (e.timeS * 1000).toLong()
                if (e.type == MealEventType.START) {
                    if (mealStart == null) {
                        mealStart = tMs
                        lastMealTagMs = now          // grace period for the safety-net timeout
                        mealTags.clear()
                        if (!shouldSaveTags) recentTags.filterTo(mealTags) { it.ts >= tMs }   // tags since the real start
                        postMeal(mealStart, null)
                    }
                } else {
                    val s = mealStart
                    if (s != null) {
                        closeMeal(s, tMs)
                        mealStart = null
                        lastMealTagMs = 0L
                    }
                }
            }
            // the UI computes the running duration only when it receives an update, so refresh it on every window
            mealStart?.let { postMeal(it, null) }
            logEpisodeWindow(det, now)
        }

        // tags: stream to file if the switch is on, otherwise keep only the current meal's tags (bounded memory)
        if (shouldSaveTags) writeTags(now, detected)
        else {
            val timed = TimedTags(now, detected)
            if (mealStart != null) mealTags.add(timed)
            if (det != null) {   // bounded history so that a retroactive meal start can be back-filled
                recentTags.addLast(timed)
                while (recentTags.isNotEmpty() && now - recentTags.first().ts > RECENT_TAGS_MS) recentTags.removeFirst()
            }
        }

        // immutable snapshot for the UI
        val shown = ArrayList(detected)
        if (out.overlapFraction > 0f) shown.add(AudioTag("Overlapping speech (>=2 speakers)", out.overlapFraction))
        if (det != null) det.lastProbability?.let { p -> shown.add(AudioTag(if (det.isOn) "Meal episode: ON" else "Meal episode: off", p)) }
        uiHandler.post { onTagsUpdatedListener?.invoke(shown) }
    }

    private fun checkMealTimeout() {
        val s = mealStart ?: return
        val det = episodeDetector
        if (det != null) {
            // The episode model needs several minutes of low scores to end a meal. Safety net: if no meal-group tag has
            // fired for EPISODE_TAG_TIMEOUT_MS, end the meal at the last such tag and put the detector back to OFF.
            if (System.currentTimeMillis() - lastMealTagMs > EPISODE_TAG_TIMEOUT_MS) {
                Log.i(TAG, "Meal ended by safety-net timeout (no meal tag for ${EPISODE_TAG_TIMEOUT_MS / 60000} min)")
                closeMeal(s, lastMealTagMs)
                mealStart = null
                lastMealTagMs = 0L
                det.forceOff()
            }
            return
        }
        if (System.currentTimeMillis() - lastMealTagMs > MEAL_SESSION_TIMEOUT_MS) {
            closeMeal(s, lastMealTagMs)
            mealStart = null
            lastMealTagMs = 0L
        }
    }

    /** One line per analysis window (about once a minute): lets you see offline why the state is ON or OFF. */
    private fun logEpisodeWindow(det: MealEpisodeDetector, now: Long) {
        if (det.windowCount == episodeLogged) return
        episodeLogged = det.windowCount
        val raw = det.rawProbability ?: return
        val smooth = det.lastProbability ?: return
        Log.i(TAG, "episode window ${det.windowCount}: raw=%.2f smooth=%.2f on=${det.isOn} ${det.debugState}".format(raw, smooth))
        val w = episodeWriter ?: openDocWriter("episode_log_${stamp()}.csv")?.also { episodeWriter = it; it.write("time,raw,smooth,on\n") } ?: return
        try {
            w.write("${outputDateFormat.format(Date(now))},%.4f,%.4f,${if (det.isOn) 1 else 0}\n".format(raw, smooth))
            w.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Error writing episode log: ${e.message}", e)
            episodeWriter.closeQuietly(); episodeWriter = null
        }
    }

    /** Persists one finished meal (timeout, user stop or window end all go through here). */
    private fun closeMeal(startMs: Long, endMs: Long) {
        writeMealTime(startMs, endMs)
        if (!shouldSaveTags) {
            val keep = if (episodeDetector != null) mealTags.filter { it.ts <= endMs } else mealTags
            keep.forEach { writeTags(it.ts, it.tags) }
        }
        mealTags.clear()
        finalizeDiarization(startMs, endMs)
        postMeal(startMs, endMs)
    }

    // ----------------------------------------------------------------------------------------------- end of session
    private fun finishSession(rec: AudioRecord?, model: JointModelProcessor?, endMs: Long) {
        try { rec?.stop() } catch (e: Exception) { Log.w(TAG, "AudioRecord.stop: ${e.message}") }
        try { rec?.release() } catch (e: Exception) { Log.w(TAG, "AudioRecord.release: ${e.message}") }

        val s = mealStart
        if (s != null) {   // meal still running when the session ended (Stop button or window end): keep it
            closeMeal(s, System.currentTimeMillis())
            finalizeScope.launch { Log.d("GESIS_Server", "Server response: ${triggerServerTaskNative()}") }  // testing phase
        }
        mealStart = null
        lastMealTagMs = 0L

        tagWriter.closeQuietly(); tagWriter = null
        mealWriter.closeQuietly(); mealWriter = null
        episodeWriter.closeQuietly(); episodeWriter = null
        try { model?.close() } catch (e: Exception) { Log.w(TAG, "model.close: ${e.message}") }

        isRunning = false
        uiHandler.post {
            onMealTimeUpdatedListener?.invoke(null, null)
            onTagsUpdatedListener?.invoke(emptyList())
        }
        notifyState(false)

        // Arm tomorrow's window (afterMs avoids an instant restart loop if this session failed mid-window).
        ScheduleManager.reschedule(applicationContext, startIfActive = false, afterMs = endMs)

        // Do NOT stop the service: staying alive keeps the mic privilege for tomorrow's window.
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification(WAITING_TEXT))
    }

    override fun onDestroy() {
        super.onDestroy()
        isArmed = false
        recordingJob?.cancel()   // cleanup still runs in finally{NonCancellable}; does NOT mark the day as stopped
        serviceScope.cancel()
    }

    // ----------------------------------------------------------------------------------------------- UI helpers
    private fun postMeal(s: Long?, e: Long?) { uiHandler.post { onMealTimeUpdatedListener?.invoke(s, e) } }

    private fun notifyState(running: Boolean) {
        uiHandler.post { onRecordingStatusChangedListener?.invoke(running) }
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun toast(msg: String) {
        uiHandler.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    // ---------------------------------------------------------------------------------------------- file output
    /** Opens a new text file in Documents/AcoustiMeal (MediaStore on Q+, plain file below). */
    private fun openDocWriter(fileName: String): BufferedWriter? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = applicationContext.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + File.separator + OUTPUT_DIR)
                }
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                val os = uri?.let { resolver.openOutputStream(it) }
                if (os == null) { Log.e(TAG, "MediaStore insert failed for $fileName"); null }
                else BufferedWriter(OutputStreamWriter(os))
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), OUTPUT_DIR)
                if (!dir.exists() && !dir.mkdirs()) { Log.e(TAG, "Cannot create $dir"); null }
                else BufferedWriter(FileWriter(File(dir, fileName), true))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open $fileName: ${e.message}", e)
            null
        }
    }

    private fun stamp(ms: Long = System.currentTimeMillis()) =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(ms))

    private fun BufferedWriter?.closeQuietly() {
        try { this?.close() } catch (e: IOException) { Log.e(TAG, "Error closing writer: ${e.message}") }
    }

    private fun writeTags(ts: Long, tags: List<AudioTag>) {
        val w = tagWriter ?: openDocWriter("audio_tags_${stamp()}.txt")?.also { tagWriter = it } ?: return
        try {
            val t = outputDateFormat.format(Date(ts))
            tags.forEach { w.write("$t, ${it.label}, ${it.confidence}\n") }
            w.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Error writing tags: ${e.message}", e)
            tagWriter.closeQuietly(); tagWriter = null
        }
    }

    private fun writeMealTime(startMs: Long, endMs: Long) {
        if (endMs - startMs < MIN_MEAL_DURATION_MS) return
        val w = mealWriter ?: openDocWriter("meal_times_${stamp()}.txt")?.also { mealWriter = it } ?: return
        try {
            w.write("Meal Start: ${outputDateFormat.format(Date(startMs))}, Meal End: ${outputDateFormat.format(Date(endMs))}\n")
            w.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Error writing meal times: ${e.message}", e)
            mealWriter.closeQuietly(); mealWriter = null
        }
    }

    /** Clusters the speech windows of [fromMs, toMs] and writes speakers_*.csv and overlap_*.csv (Documents/AcoustiMeal). */
    private fun finalizeDiarization(fromMs: Long, toMs: Long) {
        val acc = diarAcc
        val c = cfg
        val startMs = recStartMs
        val fromS = (fromMs - startMs) / 1000.0
        val toS = (toMs - startMs) / 1000.0
        finalizeScope.launch {
            try {
                val res = acc.finalize(fromS, toS, c.clusterThreshold, c.mergeFraction)
                acc.discardBefore(toS)
                val fileStamp = stamp(fromMs)
                val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                val abs = { sec: Double -> fmt.format(Date(startMs + (sec * 1000).toLong())) }
                if (res.speakers.isNotEmpty()) {
                    openDocWriter("speakers_$fileStamp.csv")?.use { w ->
                        w.write("start,end,speaker\n")
                        res.speakers.forEach { w.write("${abs(it.start)},${abs(it.end)},SPK${it.speaker}\n") }
                    }
                }
                if (res.overlap.isNotEmpty()) {
                    openDocWriter("overlap_$fileStamp.csv")?.use { w ->
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

    // ---------------------------------------------------------------------------------------- server (testing)
    suspend fun triggerServerTaskNative(): String? = withContext(Dispatchers.IO) {
        try {
            val connection = URL("https://wifo5-30.informatik.uni-mannheim.de:7443/trigger").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Content-Type", "application/json; utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            val body = """{"participant_code":"3fsbjhr3","meal_probability":0.87}"""
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) connection.inputStream.bufferedReader().use { it.readText() }
            else "Error: ${connection.responseCode}"
        } catch (e: Exception) {
            "Exception: ${e.localizedMessage}"
        }
    }

    // ------------------------------------------------------------------------------------------- notification
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Audio Processing", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AcoustiMeal")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }
}