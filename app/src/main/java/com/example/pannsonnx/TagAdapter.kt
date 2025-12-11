package com.example.pannsonnx

import android.annotation.SuppressLint
import android.content.ContentValues.TAG
import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TagAdapter : RecyclerView.Adapter<TagAdapter.TagViewHolder>() {

    private val tags = mutableListOf<AudioTag>()

    private val kitchenRelatedTags = setOf(
        "cooking", "kitchen", "frying", "boiling", "chopping", "microwave",
        "blender", "mixer", "dishwasher", "cutlery", "plate", "glass",
        "water_running", "sizzling", "bubbling", "eating", "chewing",
        "dishes_pots_pans", "coffee_machine", "kettle_whistling", "toaster",
        "oven_timer", "drinking"
    )

    class TagViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val labelText: TextView = itemView.findViewById(R.id.text_label)
        val confidenceText: TextView = itemView.findViewById(R.id.text_confidence)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_tag, parent, false)
        return TagViewHolder(view)
    }

    override fun onBindViewHolder(holder: TagViewHolder, position: Int) {
        val tag = tags[position]
        //Log.d(TAG, "${tag.label}, ${tag.confidence}")
        holder.labelText.text = tag.label
        holder.confidenceText.text = String.format("%.2f", tag.confidence)

        // Highlight kitchen-related tags
        val isKitchenRelated = kitchenRelatedTags.any {
            tag.label.contains(it, ignoreCase = true)
        }

        if (isKitchenRelated && tag.confidence > 0.5f) {
            holder.itemView.setBackgroundColor(Color.parseColor("#E8F5E8"))
            holder.labelText.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.labelText.setTextColor(Color.WHITE)
        }
    }

    override fun getItemCount(): Int = tags.size

    //@SuppressLint("NotifyDataSetChanged")
    fun updateTags(newTags: List<AudioTag>) {
        tags.clear()
        tags.addAll(newTags)
        notifyDataSetChanged()
    }
}