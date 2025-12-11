package com.example.pannsonnx

import android.app.Dialog
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.pannsonnx.databinding.DialogTimeWindowBinding
import java.util.Calendar

class TimeWindowDialog(
    // The callback now takes the selected hour and minute.
    private val onTimeSelected: (Int, Int) -> Unit
) : DialogFragment() {

    private lateinit var binding: DialogTimeWindowBinding

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogTimeWindowBinding.inflate(layoutInflater)

        // Generate a list of time options in 15-minute intervals.
        val timeOptions = mutableListOf<String>()
        for (hour in 0..23) {
            for (minute in 0..45 step 15) {
                timeOptions.add(String.format("%02d:%02d", hour, minute))
            }
        }

        // Set up spinner with the new, more granular time options.
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, timeOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerHour.adapter = adapter

        // Set the default selection to the closest 15-minute interval from the current time.
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)

        // Find the index of the closest 15-minute interval.
        val closestMinuteInterval = (currentMinute / 15) * 15
        val selectionIndex = (currentHour * 4) + (closestMinuteInterval / 15)
        binding.spinnerHour.setSelection(selectionIndex)

        return AlertDialog.Builder(requireContext())
            .setTitle("Select Recording Window")
            .setMessage("Choose the starting time for a 2-hour audio recording session")
            .setView(binding.root)
            .setPositiveButton("Start") { _, _ ->
                val selectedIndex = binding.spinnerHour.selectedItemPosition
                val selectedHour = selectedIndex / 4 // Divide by 4 because there are 4 15-minute slots per hour.
                val selectedMinute = (selectedIndex % 4) * 15 // Get the minute from the remainder.
                onTimeSelected(selectedHour, selectedMinute)
            }
            .setNegativeButton("Cancel", null)
            .create()
    }
}