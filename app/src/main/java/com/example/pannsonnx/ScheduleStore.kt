package com.example.pannsonnx

import android.content.Context

/**
 * Persistent daily-window schedule. Same prefs file and keys as before, so a previously saved time carries over.
 * Everything the background service needs lives here, so it works without the UI ever being opened.
 */
class ScheduleStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val hour: Int get() = prefs.getInt(KEY_HOUR, NONE)
    val minute: Int get() = prefs.getInt(KEY_MINUTE, NONE)
    val isSet: Boolean get() = hour != NONE && minute != NONE

    /** yyyyMMdd of the window the user pressed "Stop" on; that day's window is skipped. */
    var stoppedKey: String?
        get() = prefs.getString(KEY_STOPPED, null)
        set(v) {
            val e = prefs.edit()
            if (v == null) e.remove(KEY_STOPPED) else e.putString(KEY_STOPPED, v)
            e.apply()
        }

    /** "Save Audio Tags" switch; persisted so unattended daily sessions honour it. */
    var saveTags: Boolean
        get() = prefs.getBoolean(KEY_SAVE_TAGS, false)
        set(v) { prefs.edit().putBoolean(KEY_SAVE_TAGS, v).apply() }

    /** Choosing a window is an explicit "start": it also clears any earlier "stopped today". */
    fun save(h: Int, m: Int) {
        prefs.edit().putInt(KEY_HOUR, h).putInt(KEY_MINUTE, m).remove(KEY_STOPPED).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_HOUR).remove(KEY_MINUTE).remove(KEY_STOPPED).apply()
    }

    companion object {
        const val NONE = -1
        private const val PREFS_NAME = "MealTrackerPrefs"
        private const val KEY_HOUR = "scheduledHour"
        private const val KEY_MINUTE = "scheduledMinute"
        private const val KEY_STOPPED = "stoppedDayKey"
        private const val KEY_SAVE_TAGS = "saveTags"
    }
}
