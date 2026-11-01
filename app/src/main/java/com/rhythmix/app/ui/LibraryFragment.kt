package com.rhythmix.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.rhythmix.app.databinding.FragmentLibraryBinding
import com.rhythmix.app.model.Song
import com.rhythmix.app.ui.adapter.SongAdapter

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: SongAdapter
    private var visibleSongs = emptyList<Song>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UiMotion.enter(binding.root)

        adapter = SongAdapter(
            onClick = ::playSong,
            onArtworkClick = ::chooseArtwork,
            onLongClick = { song -> viewModel.navigate(AppScreen.SongDetail(song.id)) }
        )
        binding.rvSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSongs.adapter = adapter

        viewModel.playerState.observe(viewLifecycleOwner) { state ->
            val songId = state.currentSong?.id
            if (songId != adapter.currentSongId) {
                adapter.currentSongId = songId
                adapter.notifyDataSetChanged()
            }
        }
        viewModel.songs.observe(viewLifecycleOwner) { refresh() }
        viewModel.sortMode.observe(viewLifecycleOwner) { refresh() }
        viewModel.artworkUpdated.observe(viewLifecycleOwner) { songId ->
            if (songId != null) adapter.notifyArtworkChanged(songId)
        }
        refresh()
    }

    private fun refresh() {
        val songs = viewModel.getSortedSongs()
        visibleSongs = songs
        val playingSongId = viewModel.playerState.value?.currentSong?.id
        adapter.currentSongId = playingSongId
        adapter.submitList(songs)
        binding.emptyLibrary.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
        binding.rvSongs.visibility = if (songs.isEmpty()) View.GONE else View.VISIBLE
        if (songs.isEmpty()) {
            val noSongs = viewModel.songs.value.isNullOrEmpty()
            binding.tvEmptyTitle.text = if (noSongs) "Your library is empty" else "No songs found"
            binding.tvEmptyHint.text = if (noSongs) {
                "Choose a music folder in Settings to get started."
            } else {
                "Add music to your selected folder and rescan."
            }
        }
    }

    private fun playSong(song: Song) {
        val index = visibleSongs.indexOfFirst { it.id == song.id }
        if (index >= 0) {
            viewModel.playerController.playQueue(visibleSongs, index)
            viewModel.navigate(AppScreen.Player)
        }
    }

    private fun chooseArtwork(song: Song) {
        (activity as? MainActivity)?.chooseArtwork(song)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
