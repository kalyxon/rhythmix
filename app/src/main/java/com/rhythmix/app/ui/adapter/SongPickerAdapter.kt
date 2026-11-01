package com.rhythmix.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rhythmix.app.R
import com.rhythmix.app.model.Song

class SongPickerAdapter(
    private val onSelectionChanged: (Set<String>) -> Unit
) : ListAdapter<Song, SongPickerAdapter.VH>(DIFF) {

    private val selectedIds = mutableSetOf<String>()

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Song>() {
            override fun areItemsTheSame(a: Song, b: Song) = a.id == b.id
            override fun areContentsTheSame(a: Song, b: Song) = a == b
        }
    }

    fun getSelectedSongIds(): List<String> = selectedIds.toList()

    fun setSelectedSongIds(ids: Collection<String>) {
        selectedIds.clear()
        selectedIds.addAll(ids)
        notifyDataSetChanged()
        onSelectionChanged(selectedIds)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val checkBox: CheckBox = view.findViewById(R.id.checkBox)
        val title: TextView = view.findViewById(R.id.tvTitle)
        val artist: TextView = view.findViewById(R.id.tvArtist)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_song_picker, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val song = getItem(position)
        holder.title.text = song.title
        holder.artist.text = song.artist

        val isSelected = selectedIds.contains(song.id)
        holder.checkBox.isChecked = isSelected

        val toggleAction = {
            if (selectedIds.contains(song.id)) {
                selectedIds.remove(song.id)
            } else {
                selectedIds.add(song.id)
            }
            holder.checkBox.isChecked = selectedIds.contains(song.id)
            onSelectionChanged(selectedIds)
        }

        holder.itemView.setOnClickListener { toggleAction() }
        holder.checkBox.setOnClickListener { toggleAction() }
    }
}
