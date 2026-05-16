package com.rhythmix.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rhythmix.app.R
import com.rhythmix.app.model.Song

class SongAdapter(
    private val onClick: (Song) -> Unit
) : ListAdapter<Song, SongAdapter.ViewHolder>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Song>() {
            override fun areItemsTheSame(a: Song, b: Song) = a.id == b.id
            override fun areContentsTheSame(a: Song, b: Song) = a == b
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_title)
        val tvArtist: TextView = view.findViewById(R.id.tv_artist)
        val tvDuration: TextView = view.findViewById(R.id.tv_duration)
        val colorBar: View = view.findViewById(R.id.color_bar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = getItem(position)
        holder.tvTitle.text = song.title
        holder.tvArtist.text = song.artist
        holder.tvDuration.text = formatMs(song.durationMs)

        // Deterministic color bar (same algorithm as web app)
        var hash = 0
        for (c in song.id) hash = (hash * 31 + c.code) or 0
        val hue = Math.abs(hash) % 360
        holder.colorBar.setBackgroundColor(
            android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.6f, 0.65f))
        )

        holder.itemView.setOnClickListener { onClick(song) }
    }

    private fun formatMs(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }
}
