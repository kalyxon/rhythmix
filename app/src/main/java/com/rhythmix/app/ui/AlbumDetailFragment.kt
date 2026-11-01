package com.rhythmix.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import android.net.Uri
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.rhythmix.app.databinding.FragmentAlbumDetailBinding
import com.rhythmix.app.model.Album
import com.rhythmix.app.model.Song
import com.rhythmix.app.ui.adapter.SongAdapter

class AlbumDetailFragment : Fragment() {

    companion object {
        private const val ARG_ALBUM_ID = "album_id"
        fun newInstance(albumId: String) = AlbumDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_ALBUM_ID, albumId) }
        }
    }

    private var _binding: FragmentAlbumDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private var albumId: String? = null
    private var album: Album? = null
    private var albumSongs: List<Song> = emptyList()
    private lateinit var adapter: SongAdapter
    private var lastHighlightedSongId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        albumId = arguments?.getString(ARG_ALBUM_ID)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAlbumDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UiMotion.enter(binding.root)

        binding.btnBack.setOnClickListener { viewModel.navigate(AppScreen.Albums) }

        adapter = SongAdapter(
            onClick = { song ->
                val idx = albumSongs.indexOfFirst { it.id == song.id }
                if (idx != -1) {
                    viewModel.playerController.playQueue(albumSongs, idx)
                }
                viewModel.navigate(AppScreen.Player)
            },
            onArtworkClick = { song -> (activity as? MainActivity)?.chooseArtwork(song) },
            onLongClick = { song -> viewModel.navigate(AppScreen.SongDetail(song.id)) }
        )
        binding.rvAlbumSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvAlbumSongs.adapter = adapter
        UiMotion.animateList(binding.rvAlbumSongs)

        binding.btnPlayAlbum.setOnClickListener {
            if (albumSongs.isNotEmpty()) {
                viewModel.playerController.playQueue(albumSongs, 0)
                viewModel.navigate(AppScreen.Player)
            }
        }

        binding.btnShuffleAlbum.setOnClickListener {
            if (albumSongs.isNotEmpty()) {
                val shuffled = albumSongs.shuffled()
                viewModel.playerController.playQueue(shuffled, 0)
                viewModel.navigate(AppScreen.Player)
            }
        }

        viewModel.playerState.observe(viewLifecycleOwner) { state ->
            val songId = state.currentSong?.id
            if (songId != lastHighlightedSongId) {
                lastHighlightedSongId = songId
                adapter.currentSongId = songId
                adapter.notifyDataSetChanged()
            }
        }

        viewModel.songs.observe(viewLifecycleOwner) { loadAlbumData() }
        viewModel.analytics.observe(viewLifecycleOwner) { loadAlbumData() }
        viewModel.artworkUpdated.observe(viewLifecycleOwner) { songId ->
            if (albumSongs.any { it.id == songId }) loadAlbumData()
        }

        loadAlbumData()
    }

    private fun loadAlbumData() {
        val targetId = albumId ?: return
        album = viewModel.getAlbumById(targetId)
        val currentAlbum = album ?: return

        binding.tvHeaderTitle.text = currentAlbum.title
        binding.tvAlbumTitle.text = currentAlbum.title
        binding.tvAlbumSubtitle.text = "${currentAlbum.category} · ${currentAlbum.songIds.size} tracks"

        if (currentAlbum.description.isNotBlank()) {
            binding.tvAlbumDesc.text = currentAlbum.description
            binding.tvAlbumDesc.visibility = View.VISIBLE
        } else {
            binding.tvAlbumDesc.visibility = View.GONE
        }

        binding.btnEditAlbum.visibility = View.VISIBLE
        binding.btnEditAlbum.contentDescription =
            if (currentAlbum.category == "Custom") "Edit album" else "Customize this mix"
        binding.btnEditAlbum.setOnClickListener {
            val editor = if (currentAlbum.category == "Custom") {
                CreateAlbumDialogFragment.newInstance(currentAlbum.id)
            } else {
                CreateAlbumDialogFragment.newFromMix(currentAlbum.id)
            }
            editor.show(childFragmentManager, "EditAlbumDialog")
        }

        if (currentAlbum.category == "Custom") {
            binding.btnDeleteAlbum.visibility = View.VISIBLE
            binding.btnDeleteAlbum.setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("Delete Album?")
                    .setMessage("Are you sure you want to delete '${currentAlbum.title}'? Music files won't be deleted.")
                    .setPositiveButton("Delete") { _, _ -> viewModel.deleteAlbum(currentAlbum.id) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        } else {
            binding.btnDeleteAlbum.visibility = View.GONE
        }

        val allSongs = viewModel.songs.value ?: emptyList()
        albumSongs = currentAlbum.songIds.mapNotNull { id -> allSongs.find { it.id == id } }

        binding.albumCollageBanner.albumTitle = currentAlbum.title
        binding.albumCollageBanner.collageStyle = currentAlbum.collageStyle
        binding.albumCollageBanner.songs = albumSongs
        val coverUri = currentAlbum.coverUri?.let(Uri::parse)
        if (coverUri != null) {
            binding.ivAlbumCover.visibility = View.VISIBLE
            Glide.with(this)
                .load(coverUri)
                .centerCrop()
                .placeholder(com.rhythmix.app.R.drawable.ic_artwork_default)
                .error(com.rhythmix.app.R.drawable.ic_artwork_default)
                .into(binding.ivAlbumCover)
        } else {
            binding.ivAlbumCover.visibility = View.GONE
            Glide.with(this).clear(binding.ivAlbumCover)
        }

        adapter.submitList(albumSongs)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
