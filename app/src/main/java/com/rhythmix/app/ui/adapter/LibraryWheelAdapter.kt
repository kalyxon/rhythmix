package com.rhythmix.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.rhythmix.app.R
import com.rhythmix.app.model.Song
import com.rhythmix.app.ui.ArtworkLoader

class LibraryWheelAdapter(
    private val onClick: (Song) -> Unit,
    private val onArtworkClick: (Song) -> Unit = {}
) : RecyclerView.Adapter<LibraryWheelAdapter.ViewHolder>() {

    private var songs: List<Song> = emptyList()
    private var selectedSongId: String? = null

    override fun getItemCount(): Int = songs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_library_arc_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        holder.title.text = song.title
        holder.artist.text = song.artist
        ArtworkLoader.load(song, holder.artwork, circular = true)
        holder.artwork.contentDescription = "${song.title} artwork"
        holder.artwork.setOnClickListener { onArtworkClick(song) }
        val selected = song.id == selectedSongId
        holder.card.setBackgroundResource(
            if (selected) R.drawable.bg_library_arc_selected else R.drawable.bg_library_arc_idle
        )
        holder.playButton.visibility = if (selected) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick(song) }
        holder.playButton.setOnClickListener { onClick(song) }
    }

    fun submitSongs(newSongs: List<Song>) {
        songs = newSongs
        if (songs.none { it.id == selectedSongId }) selectedSongId = null
        notifyDataSetChanged()
    }

    fun songAt(position: Int): Song? = songs.getOrNull(position)

    fun indexOfSong(songId: String?): Int =
        if (songId == null) -1 else songs.indexOfFirst { it.id == songId }

    fun notifyArtworkChanged(songId: String) {
        val index = indexOfSong(songId)
        if (index >= 0) notifyItemChanged(index)
    }

    fun setSelectedSong(songId: String?) {
        if (songId == selectedSongId) return
        val previous = indexOfSong(selectedSongId)
        selectedSongId = songId
        val current = indexOfSong(selectedSongId)
        if (previous >= 0) notifyItemChanged(previous)
        if (current >= 0) notifyItemChanged(current)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: View = view.findViewById(R.id.arc_card)
        val artwork: ImageView = view.findViewById(R.id.iv_wheel_artwork)
        val title: TextView = view.findViewById(R.id.tv_wheel_title)
        val artist: TextView = view.findViewById(R.id.tv_wheel_artist)
        val playButton: ImageButton = view.findViewById(R.id.btn_wheel_play)
    }
}
