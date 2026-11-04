package com.rhythmix.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.net.Uri
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.rhythmix.app.R
import com.rhythmix.app.model.Album
import com.rhythmix.app.model.Song
import com.rhythmix.app.ui.UiMotion
import com.rhythmix.app.ui.view.AlbumCollageView

class AlbumAdapter(
    private val getSongById: (String) -> Song?,
    private val onAlbumClick: (Album) -> Unit
) : ListAdapter<Album, AlbumAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Album>() {
            override fun areItemsTheSame(a: Album, b: Album) = a.id == b.id
            override fun areContentsTheSame(a: Album, b: Album) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val collageView: AlbumCollageView = view.findViewById(R.id.albumCollageView)
        val cover: ImageView = view.findViewById(R.id.albumCover)
        val title: TextView = view.findViewById(R.id.tvAlbumTitle)
        val subtitle: TextView = view.findViewById(R.id.tvAlbumSub)
        var hasAnimatedIn = false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_album, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val album = getItem(position)
        holder.title.text = album.title
        val songCount = album.songIds.size
        holder.subtitle.text = "$songCount ${if (songCount == 1) "track" else "tracks"}"
        val albumSongs = album.songIds.mapNotNull { getSongById(it) }
        holder.collageView.albumTitle = album.title
        holder.collageView.collageStyle = album.collageStyle
        holder.collageView.songs = albumSongs
        val coverUri = album.coverUri?.let(Uri::parse)
        if (coverUri != null) {
            holder.cover.visibility = View.VISIBLE
            Glide.with(holder.itemView)
                .load(coverUri)
                .centerCrop()
                .placeholder(R.drawable.ic_artwork_default)
                .error(R.drawable.ic_artwork_default)
                .into(holder.cover)
        } else {
            holder.cover.visibility = View.GONE
            Glide.with(holder.itemView).clear(holder.cover)
        }

        holder.itemView.setOnClickListener {
            val safePos = holder.bindingAdapterPosition
            if (safePos != RecyclerView.NO_POSITION) {
                onAlbumClick(getItem(safePos))
            }
        }
        if (!holder.hasAnimatedIn) {
            holder.hasAnimatedIn = true
            if (UiMotion.isReduced(holder.itemView.context)) return

            val view = holder.itemView
            val itemPosition = holder.bindingAdapterPosition.coerceAtLeast(0)
            view.alpha = 0f
            view.translationY = view.resources.displayMetrics.density * 12f
            view.scaleX = 0.97f
            view.scaleY = 0.97f
            view.rotation = if (itemPosition % 2 == 0) -1.2f else 1.2f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .rotation(0f)
                .setStartDelay((itemPosition % 2) * 45L)
                .setDuration(320)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    fun refreshArtwork() {
        notifyDataSetChanged()
    }
}
