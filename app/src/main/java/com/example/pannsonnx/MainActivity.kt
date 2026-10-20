package com.example.pannsonnx

import android.Manifest
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.pannsonnx.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UI only. Recording is driven by the saved daily window + AlarmManager + the foreground service, so nothing here is
 * needed for it to run. The activity shows state and (when the service is running) live tags.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: ScheduleStore
    private lateinit var tagAdapter: TagAdapter

    private var audioService: AudioProcessingService? = null
    private var bindRequested = false

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasMic()) onReady()
            else Toast.makeText(this, "Microphone permission is required for recording", Toast.LENGTH_LONG).show()
        }

    /** Service tells us when a session starts/stops (e.g. started by the alarm while the app is open). */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            bindIfRunning()
            refreshUi()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val svc = (service as AudioProcessingService.AudioServiceBinder).getService()
            audioService = svc
            svc.setOnTagsUpdatedListener { tags -> tagAdapter.updateTags(tags) }
            svc.setOnMealTimeUpdatedListener { s, e -> updateMealTimeDisplay(s, e) }
            svc.setOnRecordingStatusChangedListener { refreshUi() }
            svc.setShouldSaveTags(binding.switchSaveTags.isChecked)
            updateMealTimeDisplay(svc.currentMealStart(), null)
            refreshUi()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ScheduleStore(this)

        tagAdapter = TagAdapter()
        binding.recyclerViewTags.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewTags.adapter = tagAdapter

        binding.switchSaveTags.isChecked = store.saveTags
        binding.switchSaveTags.setOnCheckedChangeListener { _, checked ->
            store.saveTags = checked
            audioService?.setShouldSaveTags(checked)
        }

        supportFragmentManager.setFragmentResultListener(TimeWindowDialog.REQUEST_KEY, this) { _, b ->
            onWindowChosen(b.getInt(TimeWindowDialog.KEY_HOUR), b.getInt(TimeWindowDialog.KEY_MINUTE))
        }

        binding.buttonStart.setOnClickListener {
            if (!hasMic()) checkPermissions()
            else TimeWindowDialog.newInstance(store.hour, store.minute).show(supportFragmentManager, "TimeWindowDialog")
        }
        binding.buttonStop.setOnClickListener { stopForToday() }
        binding.textSetup.setOnClickListener { openSetupSettings() }

        updateMealTimeDisplay(null, null)
        checkPermissions()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter(AudioProcessingService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        bindIfRunning()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(stateReceiver)
        audioService?.clearListeners()
        audioService = null
        if (bindRequested) {
            unbindService(serviceConnection)   // the service itself keeps running; we only detach the UI
            bindRequested = false
        }
    }

    // ------------------------------------------------------------------------------------------------ actions
    private fun onWindowChosen(h: Int, m: Int) {
        if (!hasMic()) { checkPermissions(); return }
        store.save(h, m)   // persisted: survives app/process/reboot; also clears "stopped today"
        ScheduleManager.reschedule(this, startIfActive = true)
        ScheduleManager.arm(this)   // start the always-on service while the app is visible
        refreshUi()
        Toast.makeText(this, "Daily window set: ${windowText()}", Toast.LENGTH_LONG).show()
    }

    /** "Stop Recording" = stop now and skip the rest of today's window; tomorrow's window runs as usual. */
    private fun stopForToday() {
        ScheduleManager.stopForToday(this)
        if (AudioProcessingService.isRunning) {
            startService(Intent(this, AudioProcessingService::class.java).setAction(AudioProcessingService.ACTION_STOP))
        }
        updateMealTimeDisplay(null, null)
        refreshUi()
        Toast.makeText(this, "Stopped for today", Toast.LENGTH_SHORT).show()
    }

    private fun onReady() {
        ScheduleManager.reschedule(this, startIfActive = true)   // heals alarms, resumes if inside today's window
        ScheduleManager.arm(this)
        bindIfRunning()
        refreshUi()
    }

    // ------------------------------------------------------------------------------------------ service binding
    private fun bindIfRunning() {
        if (bindRequested || !AudioProcessingService.isRunning) return
        bindRequested = bindService(
            Intent(this, AudioProcessingService::class.java), serviceConnection, Context.BIND_AUTO_CREATE
        )
    }

    // ------------------------------------------------------------------------------------------------------ UI
    private fun refreshUi() {
        val now = System.currentTimeMillis()
        val running = AudioProcessingService.isRunning
        val next = ScheduleManager.nextWindow(store, now)
        val today = ScheduleManager.dayKey(now)
        val nextToday = next != null && ScheduleManager.dayKey(next.startMs) == today
        val stoppedToday = store.stoppedKey == today

        val status: String
        val detail: String
        val dot: Int
        when {
            running -> {
                status = "Recording and processing audio..."
                detail = next?.let { "Until ${timeFmt.format(Date(it.endMs))} · daily ${windowText()}" } ?: "Daily ${windowText()}"
                dot = R.color.rec
            }
            !store.isSet -> {
                status = "Ready to start"
                detail = "No daily window set"
                dot = R.color.ink_muted
            }
            next != null && next.startMs <= now -> {
                status = "Starting..."
                detail = "Daily ${windowText()}"
                dot = R.color.warn
            }
            stoppedToday -> {
                status = "Stopped for today"
                detail = "Next: tomorrow ${timeFmt.format(Date(next?.startMs ?: now))} · daily ${windowText()}"
                dot = R.color.ink_muted
            }
            nextToday -> {
                status = "Waiting for scheduled start"
                detail = "Today ${timeFmt.format(Date(next!!.startMs))} · daily ${windowText()}"
                dot = R.color.warn
            }
            else -> {
                status = "Idle"
                detail = "Next: tomorrow ${timeFmt.format(Date(next?.startMs ?: now))} · daily ${windowText()}"
                dot = R.color.ok
            }
        }
        binding.textStatus.text = status
        binding.textSchedule.text = detail
        binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, dot))

        binding.buttonStart.isEnabled = !running
        binding.buttonStop.isEnabled = running || nextToday

        updateSetupWarning()
    }

    private fun windowText(): String {
        if (!store.isSet) return "--:--"
        return String.format("%02d:%02d–%02d:%02d", store.hour, store.minute, (store.hour + 2) % 24, store.minute)
    }

    private fun updateMealTimeDisplay(startTime: Long?, endTime: Long?) {
        binding.textMealStart.text = startTime?.let { clockFmt.format(Date(it)) } ?: "--:--:--"
        binding.textMealEnd.text = when {
            startTime == null -> "--:--:--"
            endTime == null -> "ongoing"
            else -> clockFmt.format(Date(endTime))
        }
        binding.textMealDuration.text = if (startTime == null) "--" else {
            val minutes = ((endTime ?: System.currentTimeMillis()) - startTime) / 60000
            if (endTime == null) "$minutes min (ongoing)" else "$minutes min"
        }
    }

    // ------------------------------------------------------------------------ permissions / reliability checks
    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun checkPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) onReady() else requestPermissions.launch(missing.toTypedArray())
    }

    private fun exactAlarmMissing(): Boolean = false   // setAlarmClock needs no exact-alarm permission

    private fun batteryOptimized(): Boolean =
        !(getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    /** Only shown once a window is set. Tapping opens the matching system screen. */
    private fun updateSetupWarning() {
        val msg = when {
            !store.isSet -> null
            !Settings.canDrawOverlays(this) ->
                "Allow \"Display over other apps\": Android only lets the app record from the background with it. Tap to allow."
            batteryOptimized() -> "Battery optimization may stop background recording. Tap to exempt this app."
            else -> null
        }
        binding.textSetup.text = msg
        binding.textSetup.visibility = if (msg == null) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun openSetupSettings() {
        try {
            val uri = Uri.parse("package:$packageName")
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, uri))
            } else {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, uri))
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Cannot open settings: ${e.message}")
        }
    }
}
