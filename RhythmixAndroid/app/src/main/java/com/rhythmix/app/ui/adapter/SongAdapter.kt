package com.rhythmix.app.ui.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rhythmix.app.R
import com.rhythmix.app.model.Song

class SongAdapter(
    private val onClick: (Song) -> Unit
) : ListAdapter<Song, SongAdapter.VH>(DIFF) {

    var currentSongId: String? = null

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Song>() {
            override fun areItemsTheSame(a: Song, b: Song) = a.id == b.id
            override fun areContentsTheSame(a: Song, b: Song) = a == b
        }
        private val PALETTE = listOf(
            0xC8FF00, 0x9B6DFF, 0xFF4D8F, 0x00D4FF,
            0xFF8C42, 0x1DB954, 0xFFB830, 0xFF4444,
            0x00BCD4, 0xE040FB, 0xFF7043, 0x26C6DA
        )
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val artBg: FrameLayout = view.findViewById(R.id.art_bg)
        val letter: TextView   = view.findViewById(R.id.tv_art_letter)
        val title: TextView    = view.findViewById(R.id.tv_title)
        val artist: TextView   = view.findViewById(R.id.tv_artist)
        val duration: TextView = view.findViewById(R.id.tv_duration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        // BUG FIX 1: capture song at bind time, use bindingAdapterPosition in click listener
        // to avoid stale position from RecyclerView recycling
        val song = getItem(pos)

        h.title.text    = song.title
        h.artist.text   = song.artist
        h.duration.text = fmtMs(song.durationMs)
        h.letter.text   = song.title.firstOrNull()?.uppercase() ?: "♪"

        var hash = 0
        for (c in song.id) hash = (hash * 31 + c.code) or 0
        val rgb = PALETTE[Math.abs(hash) % PALETTE.size]
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8)  and 0xFF
        val b =  rgb         and 0xFF
        h.artBg.setBackgroundColor(Color.rgb(r / 3, g / 3, b / 3))
        h.letter.setTextColor(Color.rgb(r, g, b))

        val isPlaying = song.id == currentSongId
        h.title.setTextColor(
            if (isPlaying) h.itemView.context.getColor(R.color.accent)
            else           h.itemView.context.getColor(R.color.text_primary)
        )
        h.itemView.alpha = if (currentSongId != null && !isPlaying) 0.7f else 1f

        // BUG FIX: use bindingAdapterPosition inside click listener, not captured `pos`
        h.itemView.setOnClickListener {
            val safePos = h.bindingAdapterPosition
            if (safePos != RecyclerView.NO_POSITION) {
                onClick(getItem(safePos))
            }
        }
    }

    private fun fmtMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
