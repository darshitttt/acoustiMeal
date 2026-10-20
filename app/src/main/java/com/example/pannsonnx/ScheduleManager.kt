package com.example.pannsonnx

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Daily 2-hour recording window, driven by AlarmManager so it survives the app being closed, the process being
 * killed and reboots (see ScheduleReceiver).
 *
 *  - Window  = [start, start + 2 h) at the saved HH:mm, every day.
 *  - "Stop"  = stoppedKey is set to that window's day -> that window is skipped, tomorrow's runs normally.
 */
object ScheduleManager {
    private const val TAG = "ScheduleManager"
    const val ACTION_ALARM = "com.example.pannsonnx.action.ALARM"
    const val WINDOW_MS = 2 * 60 * 60 * 1000L

    data class Window(val startMs: Long, val endMs: Long)

    fun dayKey(ms: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ms))

    /**
     * First window that has not ended yet and was not stopped by the user (may already be active).
     * [afterMs]: only windows starting at/after this instant (used to avoid restart loops after a failed session).
     */
    fun nextWindow(store: ScheduleStore, now: Long = System.currentTimeMillis(), afterMs: Long? = null): Window? {
        if (!store.isSet) return null
        for (d in -1..2) {   // -1: a window that started yesterday evening may still be running
            val c = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, d)
                set(Calendar.HOUR_OF_DAY, store.hour)
                set(Calendar.MINUTE, store.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val start = c.timeInMillis
            val end = start + WINDOW_MS
            if (end > now && dayKey(start) != store.stoppedKey && (afterMs == null || start >= afterMs)) {
                return Window(start, end)
            }
        }
        return null
    }

    /** User pressed Stop: skip the current/next window of today, re-arm for the following day. */
    fun stopForToday(ctx: Context) {
        val store = ScheduleStore(ctx)
        val w = nextWindow(store) ?: return
        store.stoppedKey = dayKey(w.startMs)
        reschedule(ctx, startIfActive = false)
    }

    /**
     * (Re)arms the next alarm. If a window is active right now: starts the service directly ([startIfActive], used from
     * the UI and from the alarm itself) or via a short alarm (boot / time change: gives the exact-alarm exemption
     * for starting a microphone foreground service from the background).
     */
    fun reschedule(ctx: Context, startIfActive: Boolean, afterMs: Long? = null) {
        val app = ctx.applicationContext
        val store = ScheduleStore(app)
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(app)
        am.cancel(pi)

        val now = System.currentTimeMillis()
        val w = nextWindow(store, now, afterMs) ?: return
        when {
            w.startMs > now -> setAlarm(app, am, pi, w.startMs)
            startIfActive -> startService(app)
            else -> setAlarm(app, am, pi, now + 2_000L)
        }
    }

    /** Call ONLY while the app is visible (Start button / app open): starts the long-lived service that keeps mic access. */
    fun arm(ctx: Context) {
        val app = ctx.applicationContext
        if (ScheduleStore(app).isSet && !AudioProcessingService.isArmed) startService(app)
    }

    private fun startService(app: Context) {
        if (AudioProcessingService.isRunning) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO not granted; cannot start window")
            return
        }
        try {
            val i = Intent(app, AudioProcessingService::class.java).setAction(AudioProcessingService.ACTION_START)
            if (AudioProcessingService.isArmed) app.startService(i) else ContextCompat.startForegroundService(app, i)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start recording service from background: ${e.message}", e)
        }
    }

    /**
     * setAlarmClock: exact, works in Doze, needs no extra permission, and Android lets an app started by it
     * open the microphone from the background (plain exact alarms do not). Side effect: a small alarm icon in the status bar.
     */
    private fun setAlarm(app: Context, am: AlarmManager, pi: PendingIntent, at: Long) {
        val show = PendingIntent.getActivity(
            app, 1, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
    }

    private fun alarmIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app, 0,
        Intent(app, ScheduleReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
