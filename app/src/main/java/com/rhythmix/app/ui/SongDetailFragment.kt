package com.rhythmix.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.rhythmix.app.databinding.FragmentSongDetailBinding
import com.rhythmix.app.model.Song

class SongDetailFragment : Fragment() {

    companion object {
        private const val ARG_SONG_ID = "song_id"

        fun newInstance(songId: String) = SongDetailFragment().apply {
            arguments = Bundle().apply { putString(ARG_SONG_ID, songId) }
        }
    }

    private var _binding: FragmentSongDetailBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private var songId: String? = null
    private var renderedSong: Song? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        songId = arguments?.getString(ARG_SONG_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSongDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UiMotion.enter(binding.root)
        binding.btnBack.setOnClickListener { viewModel.navigate(AppScreen.Library) }
        binding.btnChangeArtwork.setOnClickListener {
            currentSong()?.let { (activity as? MainActivity)?.chooseArtwork(it) }
        }
        binding.btnRestoreArtwork.setOnClickListener {
            val song = currentSong() ?: return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("Restore original artwork?")
                .setMessage("This removes your custom image and shows the song's embedded artwork, or its fallback cover if it has no embedded artwork.")
                .setPositiveButton("Restore") { _, _ ->
                    viewModel.restoreSongArtwork(song.id)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnSaveSong.setOnClickListener {
            val id = songId ?: return@setOnClickListener
            viewModel.updateSongMetadata(
                id,
                binding.etSongTitle.text?.toString().orEmpty(),
                binding.etSongArtist.text?.toString().orEmpty(),
                binding.etSongAlbum.text?.toString().orEmpty()
            )
            if (binding.etSongTitle.text.isNullOrBlank() || binding.etSongArtist.text.isNullOrBlank()) {
                return@setOnClickListener
            }
            viewModel.navigate(AppScreen.Library)
        }
        binding.btnRemoveSong.setOnClickListener {
            val song = currentSong() ?: return@setOnClickListener
            AlertDialog.Builder(requireContext())
                .setTitle("Remove from library?")
                .setMessage(
                    "'${song.title}' will be hidden from Rhythmix, but the file stays in your music folder. " +
                        "Restore it later from Settings > Your Music > Restore removed songs."
                )
                .setPositiveButton("Remove") { _, _ -> viewModel.removeSongFromLibrary(song.id) }
                .setNegativeButton("Cancel", null)
                .show()
        }

        viewModel.songs.observe(viewLifecycleOwner) { render() }
        viewModel.analytics.observe(viewLifecycleOwner) { render() }
        viewModel.artworkUpdated.observe(viewLifecycleOwner) { updatedId ->
            if (updatedId == songId) currentSong()?.let {
                ArtworkLoader.load(it, binding.ivSongArtwork)
                updateArtworkActions(it)
            }
        }
        render()
    }

    private fun currentSong(): Song? =
        songId?.let { id -> viewModel.songs.value.orEmpty().find { it.id == id } }

    private fun render() {
        val song = currentSong() ?: run {
            viewModel.navigate(AppScreen.Library)
            return
        }
        val previousSong = renderedSong
        if (previousSong?.id != song.id ||
            previousSong.title != song.title ||
            previousSong.artist != song.artist ||
            previousSong.album != song.album
        ) {
            binding.etSongTitle.setText(song.title)
            binding.etSongArtist.setText(song.artist)
            binding.etSongAlbum.setText(song.album)
            renderedSong = song
        }
        ArtworkLoader.load(song, binding.ivSongArtwork)
        updateArtworkActions(song)
        val playCount = viewModel.getSongPlayCount(song.id)
        binding.tvSongStats.text =
            "${song.durationMs / 60_000}:${"%02d".format((song.durationMs / 1_000) % 60)} · Played $playCount ${if (playCount == 1) "time" else "times"}"
        val collections = viewModel.analytics.value?.albums.orEmpty()
            .filter { song.id in it.songIds }
            .map { it.title }
        val membership = buildList {
            if (song.album.isNotBlank() && song.album != "Unknown Album") add("Album: ${song.album}")
            addAll(collections)
        }
        binding.tvAlbumMembership.text = if (membership.isEmpty()) {
            "Not assigned to an album or custom collection."
        } else {
            "Appears in: ${membership.joinToString(", ")}"
        }
    }

    private fun updateArtworkActions(song: Song) {
        binding.btnRestoreArtwork.visibility =
            if (viewModel.storage.getSongArtworkUri(song.id) != null) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
