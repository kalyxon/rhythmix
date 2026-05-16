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

class StatsAdapter : ListAdapter<SongStats, StatsAdapter.ViewHolder>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<SongStats>() {
            override fun areItemsTheSame(a: SongStats, b: SongStats) = a.songId == b.songId
            override fun areContentsTheSame(a: SongStats, b: SongStats) = a == b
        }
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRank: TextView = view.findViewById(R.id.tv_rank)
        val tvTitle: TextView = view.findViewById(R.id.tv_title)
        val tvArtist: TextView = view.findViewById(R.id.tv_artist)
        val tvPlays: TextView = view.findViewById(R.id.tv_plays)
        val tvListened: TextView = view.findViewById(R.id.tv_listened)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_stat, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val stat = getItem(position)
        holder.tvRank.text = "#${position + 1}"
        holder.tvTitle.text = stat.title
        holder.tvArtist.text = stat.artist
        holder.tvPlays.text = "${stat.playCount} plays"
        val hours = TimeUnit.MILLISECONDS.toHours(stat.totalListenedMs)
        val mins = TimeUnit.MILLISECONDS.toMinutes(stat.totalListenedMs) % 60
        holder.tvListened.text = if (hours > 0) "${hours}h ${mins}m" else "${mins}m listened"
    }
}
