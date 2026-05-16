package com.rhythmix.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rhythmix.app.R
import com.rhythmix.app.model.SongStats
import java.util.concurrent.TimeUnit

class StatsAdapter : ListAdapter<SongStats, StatsAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<SongStats>() {
            override fun areItemsTheSame(a: SongStats, b: SongStats) = a.songId == b.songId
            override fun areContentsTheSame(a: SongStats, b: SongStats) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val rank:     TextView = view.findViewById(R.id.tv_rank)
        val title:    TextView = view.findViewById(R.id.tv_title)
        val artist:   TextView = view.findViewById(R.id.tv_artist)
        val plays:    TextView = view.findViewById(R.id.tv_plays)
        val listened: TextView = view.findViewById(R.id.tv_listened)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_stat, parent, false))

    override fun onBindViewHolder(h: VH, pos: Int) {
        val s = getItem(pos)
        // Use bindingAdapterPosition for rank so it always reflects the real position
        val rank = h.bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION } ?: pos
        h.rank.text     = "${rank + 1}"
        h.title.text    = s.title
        h.artist.text   = s.artist
        h.plays.text    = "${s.playCount} plays"
        val hrs = TimeUnit.MILLISECONDS.toHours(s.totalListenedMs)
        val min = TimeUnit.MILLISECONDS.toMinutes(s.totalListenedMs) % 60
        h.listened.text = if (hrs > 0) "${hrs}h ${min}m" else "${min}m"
    }
}
