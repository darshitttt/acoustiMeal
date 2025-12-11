// MainActivity.kt
package com.example.pannsonnx

import android.Manifest
import android.content.ComponentName
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.pannsonnx.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var audioService: AudioProcessingService? = null
    private var serviceBound = false

    private lateinit var tagAdapter: TagAdapter // Assuming TagAdapter is defined elsewhere
    // It should handle updating the RecyclerView.
    private val PREFS_NAME = "MealTrackerPrefs"
    private val KEY_SCHEDULED_HOUR = "scheduledHour"
    private val KEY_SCHEDULED_MINUTE = "scheduledMinute"
    private val DEFAULT_SCHEDULE_TIME = -1 // Used to indicate no time is saved

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Check if the RECORD_AUDIO permission is granted.
        //val writeStorageGranted = permissions[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: false
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false

        if (recordAudioGranted) {
            // All necessary permissions granted, proceed with setting up the time window and service.
            setupTimeWindow()
        } else {
            // Inform the user that permissions are essential.
            Toast.makeText(this, "RECORD_AUDIO permission is required for this app to function.", Toast.LENGTH_LONG).show()
            // Do NOT proceed with setupTimeWindow() if permissions are not granted.
        }
    }

    private val serviceConnection = object : ServiceConnection {
        @RequiresApi(Build.VERSION_CODES.O)
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // Get the service instance from the binder.
            val binder = service as AudioProcessingService.AudioServiceBinder
            audioService = binder.getService()
            serviceBound = true

            // Set up listeners for real-time updates from the service.
            // This ensures UI updates are driven by actual data changes from the service.
            audioService?.setOnTagsUpdatedListener { tags ->
                runOnUiThread {
                    tagAdapter.updateTags(tags)
                }
            }

            audioService?.setOnMealTimeUpdatedListener { startTime, endTime ->
                runOnUiThread {
                    updateMealTimeDisplay(startTime, endTime)
                }
            }

            // This is the crucial new listener to correctly update the UI status.
            audioService?.setOnRecordingStatusChangedListener { isNowRecording ->
                runOnUiThread {
                    val statusText = if (isNowRecording) {
                        "Recording and processing audio..."
                    } else {
                        val (savedHour, savedMinute) = loadScheduledTime()
                        if (savedHour != DEFAULT_SCHEDULE_TIME) {
                            binding.textStatus.text = "Awaiting scheduled start (${String.format("%02d:%02d", savedHour, savedMinute)})"
                            binding.buttonStart.isEnabled = true
                            binding.buttonStop.isEnabled = false
                        } else {
                            binding.textStatus.text = "Ready to start"
                            binding.buttonStart.isEnabled = true
                            binding.buttonStop.isEnabled = false
                        }
                        "Audio processing is inactive."
                    }
                    binding.textStatus.text = statusText
                }
            }
            autoStartProcessingIfScheduled()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Reset service reference and binding status when the service disconnects.
            audioService = null
            serviceBound = false
        }

    }

    @RequiresApi(Build.VERSION_CODES.O) // Annotation needed for features like Notification Channels,
    // though the fragment manager itself isn't restricted.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupClickListeners()
        checkPermissions() // Check permissions on app start.
    }

    private fun setupRecyclerView() {
        tagAdapter = TagAdapter() // Initialize your TagAdapter.
        binding.recyclerViewTags.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = tagAdapter
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupClickListeners() {
        binding.buttonStart.setOnClickListener {
            // Ensure the service is bound before attempting to show the time picker.
            if (serviceBound && audioService != null) {
                showTimePickerDialog()
            } else {
                // If service is not bound (e.g., due to permission denial), re-check permissions.
                Toast.makeText(this, "Service not ready. Checking permissions...", Toast.LENGTH_SHORT).show()
                checkPermissions()
            }
        }

        binding.buttonStop.setOnClickListener {
            stopAudioProcessing()
        }

        binding.switchSaveTags.setOnCheckedChangeListener { _, isChecked ->
            // Pass the state of the switch to the service.
            audioService?.setShouldSaveTags(isChecked)
            // Inform the user about the new state.
            val message = if (isChecked) "Saving audio tags enabled" else "Saving audio tags disabled"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissions() {
        // Only request RECORD_AUDIO as it's the primary permission for this app's core functionality.
        // WRITE_EXTERNAL_STORAGE and READ_EXTERNAL_STORAGE are generally not needed for
        // internal app data or processing, especially on modern Android versions.
        val permissions = arrayOf(
            //android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.RECORD_AUDIO
        )

        // Filter permissions that are not yet granted.
        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        Log.d(TAG, "Permissions: $permissionsToRequest")
        if (permissionsToRequest.isNotEmpty()) {
            // Request permissions if any are missing.
            requestPermissions.launch(permissionsToRequest.toTypedArray())
        } else {
            // If all permissions are already granted, proceed with time window setup.
            setupTimeWindow()
        }
    }

    private fun setupTimeWindow() {
        // Bind to the audio processing service. This will establish the connection
        // and allow interaction with the service.
        val intent = Intent(this, AudioProcessingService::class.java)
        // BIND_AUTO_CREATE ensures the service is created if it's not already running.
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun autoStartProcessingIfScheduled() {
        val (savedHour, savedMinute) = loadScheduledTime()

        if (savedHour != DEFAULT_SCHEDULE_TIME) {
            startAudioProcessing(savedHour, savedMinute)
            // Update status to reflect awaiting schedule based on saved time
            binding.textStatus.text = "Awaiting scheduled start (${String.format("%02d:%02d", savedHour, savedMinute)})"
            Toast.makeText(this, "Auto-scheduling with last saved time.", Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun showTimePickerDialog() {
        // Display the TimeWindowDialog to allow the user to select a start hour.
        val dialog = TimeWindowDialog { selectedHour, selectedMinute ->
            startAudioProcessing(selectedHour, selectedMinute)
        }
        dialog.show(supportFragmentManager, "TimeWindowDialog")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startAudioProcessing(selectedHour: Int, selectedMinute: Int) {
        saveScheduledTime(selectedHour, selectedMinute)

        // Call the service method to start audio processing.
        audioService?.startProcessing(selectedHour, selectedMinute)

        // Update UI state to reflect that processing has started.
        binding.buttonStart.isEnabled = false
        binding.buttonStop.isEnabled = true
        //binding.textStatus.text = "Recording and processing audio..."
        binding.textStatus.text = "Awaiting scheduled start..."

        // Provide immediate feedback to the user.
        // The service's internal scheduling will handle the actual start time.
        Toast.makeText(this, "Audio processing scheduled for ${selectedHour}:${selectedMinute}", Toast.LENGTH_LONG).show()
    }

    private fun stopAudioProcessing() {
        // Call the service method to stop audio processing.
        audioService?.stopProcessing()

        // Update UI state to reflect that processing has stopped.
        binding.buttonStart.isEnabled = true
        binding.buttonStop.isEnabled = false
        binding.textStatus.text = "Stopped"

        // Clear any displayed meal time information.
        binding.textMealStart.text = "Meal Start: Not detected"
        binding.textMealEnd.text = "Meal End: Not detected"
        binding.textMealDuration.text = "Duration: 0 minutes"

        Toast.makeText(this, "Audio processing stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateMealTimeDisplay(startTime: Long?, endTime: Long?) {
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        val startText = if (startTime != null) dateFormat.format(Date(startTime)) else "Not detected"
        // If end time is null, it means the meal is still ongoing.
        val endText = if (endTime != null) dateFormat.format(Date(endTime)) else "Ongoing"

        binding.textMealStart.text = "Meal Start: $startText"
        binding.textMealEnd.text = "Meal End: $endText"

        if (startTime != null && endTime != null) {
            // Calculate duration in minutes.
            val duration = (endTime - startTime) / 1000 / 60
            binding.textMealDuration.text = "Duration: $duration minutes"
        } else if (startTime != null && endTime == null) {
            // If meal is ongoing, display current duration.
            val currentDuration = (System.currentTimeMillis() - startTime) / 1000 / 60
            binding.textMealDuration.text = "Duration (Ongoing): $currentDuration minutes"
        } else {
            // No meal detected yet.
            binding.textMealDuration.text = "Duration: 0 minutes"
        }
    }

    private fun saveScheduledTime(hour: Int, minute: Int) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putInt(KEY_SCHEDULED_HOUR, hour)
            putInt(KEY_SCHEDULED_MINUTE, minute)
            apply()
        }
        Log.d(TAG, "Scheduled time saved: $hour:$minute")
    }

    private fun loadScheduledTime(): Pair<Int, Int> {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hour = prefs.getInt(KEY_SCHEDULED_HOUR, DEFAULT_SCHEDULE_TIME)
        val minute = prefs.getInt(KEY_SCHEDULED_MINUTE, DEFAULT_SCHEDULE_TIME)
        Log.d(TAG, "Scheduled time loaded: $hour:$minute")
        return Pair(hour, minute)
    }

    private fun clearScheduledTime() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            remove(KEY_SCHEDULED_HOUR)
            remove(KEY_SCHEDULED_MINUTE)
            apply()
        }
        Log.d(TAG, "Scheduled time cleared.")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unbind the service to prevent service leaks when the activity is destroyed.
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false // Update service bound status
        }
        // It's good practice to ensure the service is fully stopped if it's not needed
        // to run in the background without the activity.
        audioService?.stopProcessing()
    }
}