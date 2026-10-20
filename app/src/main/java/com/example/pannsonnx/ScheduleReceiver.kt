package com.example.pannsonnx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Alarm fired / device rebooted / app updated / clock or timezone changed / exact-alarm permission changed. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ScheduleManager.reschedule(context, startIfActive = intent.action == ScheduleManager.ACTION_ALARM)
    }
}
