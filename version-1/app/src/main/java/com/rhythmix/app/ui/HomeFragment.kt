package com.rhythmix.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.rhythmix.app.databinding.FragmentHomeBinding
import com.rhythmix.app.ui.adapter.SongAdapter
import java.util.concurrent.TimeUnit

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Navigation buttons
        binding.btnLibrary.setOnClickListener { viewModel.navigate(AppScreen.Library) }
        binding.btnAnalytics.setOnClickListener { viewModel.navigate(AppScreen.Analytics) }
        binding.btnSettings.setOnClickListener { viewModel.navigate(AppScreen.Settings) }

        // Mini player
        binding.miniPlayer.root.setOnClickListener { viewModel.navigate(AppScreen.Player) }
        binding.miniPlayer.btnPlayPause.setOnClickListener { viewModel.playerController.togglePlay() }
        binding.miniPlayer.btnNext.setOnClickListener { viewModel.playerController.skipNext() }

        // Top songs list
        val adapter = SongAdapter { song ->
            val songs = viewModel.getSortedSongs()
            val idx = songs.indexOfFirst { it.id == song.id }
            viewModel.playerController.setQueue(songs, idx.coerceAtLeast(0))
        }
        binding.rvTopSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTopSongs.adapter = adapter

        // Observe player state
        viewModel.playerState.observe(viewLifecycleOwner) { state ->
            val song = state.currentSong
            binding.miniPlayer.root.visibility = if (song != null) View.VISIBLE else View.GONE
            binding.miniPlayer.tvTitle.text = song?.title ?: ""
            binding.miniPlayer.tvArtist.text = song?.artist ?: ""
            binding.miniPlayer.btnPlayPause.setImageResource(
                if (state.isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
        }

        // Observe analytics
        viewModel.analytics.observe(viewLifecycleOwner) { _ ->
            updateStats()
            updateTopSongs(adapter)
        }

        viewModel.songs.observe(viewLifecycleOwner) { songs ->
            binding.tvSongCount.text = "${songs.size} songs"
        }

        updateStats()
        updateTopSongs(adapter)
    }

    private fun updateStats() {
        val totalMs = viewModel.getTotalListeningTimeMs()
        val todayMs = viewModel.getTodayListeningTimeMs()

        binding.tvTotalTime.text = formatDuration(totalMs)
        binding.tvTodayTime.text = formatDuration(todayMs)

        val events = viewModel.analytics.value?.playEvents ?: emptyList()
        binding.tvTotalPlays.text = "${events.size} plays"
    }

    private fun updateTopSongs(adapter: SongAdapter) {
        val topStats = viewModel.computeSongStats(5)
        val songMap = viewModel.songs.value?.associateBy { it.id } ?: return
        val topSongs = topStats.mapNotNull { songMap[it.songId] }
        adapter.submitList(topSongs)
    }

    private fun formatDuration(ms: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
