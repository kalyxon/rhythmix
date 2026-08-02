package com.rhythmix.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.rhythmix.app.R
import com.rhythmix.app.databinding.FragmentHomeBinding
import com.rhythmix.app.ui.adapter.SongAdapter
import java.util.concurrent.TimeUnit

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: SongAdapter

    // The exact list shown in the top-songs RecyclerView
    private var currentTopSongs = listOf<com.rhythmix.app.model.Song>()
    private var lastHighlightedSongId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSearch.setOnClickListener { viewModel.navigate(AppScreen.Library) }
        binding.tvSeeAll.setOnClickListener { viewModel.navigate(AppScreen.Library) }

        adapter = SongAdapter { song ->
            // FIX: find index within the exact top-songs list shown on screen,
            // then set that as the queue so indices are always in sync.
            val idx = currentTopSongs.indexOfFirst { it.id == song.id }
            if (idx != -1) {
                viewModel.playerController.playQueue(currentTopSongs, idx)
            }
            viewModel.navigate(AppScreen.Player)
        }
        binding.rvTopSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTopSongs.adapter = adapter

        viewModel.playerState.observe(viewLifecycleOwner) { state ->
            val songId = state.currentSong?.id
            if (songId != lastHighlightedSongId) {
                lastHighlightedSongId = songId
                adapter.currentSongId = songId
                adapter.notifyDataSetChanged()
            } else {
                adapter.currentSongId = songId
            }
            // Refresh time counters live while playing — positionMs ticks every 200ms
            if (state.isPlaying) refreshStats()
        }

        viewModel.analytics.observe(viewLifecycleOwner) { refreshStats() }
        viewModel.songs.observe(viewLifecycleOwner) { songs ->
            binding.tvSongCount.text = "${songs.size} songs"
            refreshTopSongs()
        }

        refreshStats()
        refreshTopSongs()
    }

    private fun refreshStats() {
        binding.tvTodayTime.text  = fmt(viewModel.getTodayListeningTimeMs())
        binding.tvTotalTime.text  = fmt(viewModel.getTotalListeningTimeMs())
        binding.tvTotalPlays.text = "${viewModel.analytics.value?.playEvents?.size ?: 0}"
    }

    private fun refreshTopSongs() {
        val stats  = viewModel.computeSongStats(8)
        val map    = viewModel.songs.value?.associateBy { it.id } ?: return
        currentTopSongs = stats.mapNotNull { map[it.songId] }  // keep in sync with adapter
        adapter.submitList(currentTopSongs)
    }

    private fun fmt(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
