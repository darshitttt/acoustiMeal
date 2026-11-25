package com.example.pannsonnx

import android.app.Dialog
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.pannsonnx.databinding.DialogTimeWindowBinding
import java.util.Calendar

/** Result is delivered via the Fragment Result API, so the dialog survives rotation (no lambda constructor). */
class TimeWindowDialog : DialogFragment() {

    private lateinit var binding: DialogTimeWindowBinding

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogTimeWindowBinding.inflate(layoutInflater)

        val timeOptions = mutableListOf<String>()
        for (hour in 0..23) for (minute in 0..45 step 15) timeOptions.add(String.format("%02d:%02d", hour, minute))

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, timeOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerHour.adapter = adapter

        // Preselect the saved window; otherwise the closest 15-minute slot to now.
        val savedH = arguments?.getInt(KEY_HOUR, -1) ?: -1
        val savedM = arguments?.getInt(KEY_MINUTE, -1) ?: -1
        val (h, m) = if (savedH >= 0 && savedM >= 0) Pair(savedH, savedM) else {
            val c = Calendar.getInstance()
            Pair(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        }
        binding.spinnerHour.setSelection(h * 4 + (m / 15))

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.dialog_title)
            .setView(binding.root)
            .setPositiveButton(R.string.dialog_ok) { _, _ ->
                val idx = binding.spinnerHour.selectedItemPosition
                parentFragmentManager.setFragmentResult(REQUEST_KEY, Bundle().apply {
                    putInt(KEY_HOUR, idx / 4)
                    putInt(KEY_MINUTE, (idx % 4) * 15)
                })
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .create()
    }

    companion object {
        const val REQUEST_KEY = "time_window"
        const val KEY_HOUR = "hour"
        const val KEY_MINUTE = "minute"

        fun newInstance(hour: Int, minute: Int) = TimeWindowDialog().apply {
            arguments = Bundle().apply { putInt(KEY_HOUR, hour); putInt(KEY_MINUTE, minute) }
        }
    }
}
