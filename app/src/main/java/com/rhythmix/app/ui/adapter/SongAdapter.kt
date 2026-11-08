package com.rhythmix.app.ui.adapter

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rhythmix.app.R
import com.rhythmix.app.model.Song
import com.rhythmix.app.ui.ArtworkLoader

class SongAdapter(
    private val onClick: (Song) -> Unit,
    private val onArtworkClick: (Song) -> Unit = {},
    private val onLongClick: (Song) -> Unit = {}
) : ListAdapter<Song, SongAdapter.VH>(DIFF) {

    var currentSongId: String? = null

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Song>() {
            override fun areItemsTheSame(a: Song, b: Song) = a.id == b.id
            override fun areContentsTheSame(a: Song, b: Song) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val artwork: ImageView = view.findViewById(R.id.iv_artwork)
        val title: TextView    = view.findViewById(R.id.tv_title)
        val artist: TextView   = view.findViewById(R.id.tv_artist)
        val duration: TextView = view.findViewById(R.id.tv_duration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val song = getItem(pos)

        h.title.text    = song.title
        h.artist.text   = song.artist
        h.duration.text = fmtMs(song.durationMs)
        ArtworkLoader.load(song, h.artwork)
        h.artwork.contentDescription = "Change artwork for ${song.title}"
        h.artwork.setOnClickListener { onArtworkClick(song) }
        h.artwork.setOnLongClickListener {
            val safePos = h.bindingAdapterPosition
            if (safePos == RecyclerView.NO_POSITION) false else {
                onLongClick(getItem(safePos))
                true
            }
        }
        h.artwork.isClickable = true
        h.artwork.isFocusable = true

        val isPlaying = song.id == currentSongId
        if (isPlaying) {
            val tv = TypedValue()
            h.itemView.context.theme.resolveAttribute(androidx.appcompat.R.attr.colorPrimary, tv, true)
            h.title.setTextColor(tv.data)
        } else {
            h.title.setTextColor(h.itemView.context.getColor(R.color.text_primary))
        }

        h.itemView.alpha = if (currentSongId != null && !isPlaying) 0.75f else 1f

        h.itemView.setOnClickListener {
            val safePos = h.bindingAdapterPosition
            if (safePos != RecyclerView.NO_POSITION) {
                onClick(getItem(safePos))
            }
        }
        h.itemView.setOnLongClickListener {
            val safePos = h.bindingAdapterPosition
            if (safePos == RecyclerView.NO_POSITION) {
                false
            } else {
                onLongClick(getItem(safePos))
                true
            }
        }
    }

    fun notifyArtworkChanged(songId: String) {
        val index = currentList.indexOfFirst { it.id == songId }
        if (index >= 0) notifyItemChanged(index)
    }

    private fun fmtMs(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}
