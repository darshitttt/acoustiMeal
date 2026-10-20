package com.example.pannsonnx

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
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
        val bar: ProgressBar = itemView.findViewById(R.id.bar_confidence)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TagViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_audio_tag, parent, false)
        return TagViewHolder(view)
    }

    override fun onBindViewHolder(holder: TagViewHolder, position: Int) {
        val tag = tags[position]
        holder.labelText.text = tag.label
        holder.confidenceText.text = String.format("%.2f", tag.confidence)
        holder.bar.progress = (tag.confidence * 100f).toInt().coerceIn(0, 100)

        val ctx = holder.itemView.context
        val isMeal = tag.confidence > 0.5f && kitchenRelatedTags.any { tag.label.contains(it, ignoreCase = true) }
        holder.labelText.setTextColor(ContextCompat.getColor(ctx, if (isMeal) R.color.accent else R.color.ink))
        holder.labelText.setTypeface(null, if (isMeal) Typeface.BOLD else Typeface.NORMAL)
    }

    override fun getItemCount(): Int = tags.size

    @Suppress("NotifyDataSetChanged")
    fun updateTags(newTags: List<AudioTag>) {
        tags.clear()
        tags.addAll(newTags)
        notifyDataSetChanged()
    }
}
