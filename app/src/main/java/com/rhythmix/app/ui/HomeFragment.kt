package com.rhythmix.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.rhythmix.app.R
import com.rhythmix.app.databinding.FragmentHomeBinding
import com.rhythmix.app.model.Song
import com.rhythmix.app.ui.adapter.SongAdapter
import java.util.concurrent.TimeUnit

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: SongAdapter
    private var currentSongs = emptyList<Song>()
    private var featuredSong: Song? = null
    private var selectedFilter = "all"
    private var lastFeaturedSongId: String? = null
    private var lastHighlightedSongId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UiMotion.enter(binding.root)

        binding.btnSearch.setOnClickListener { viewModel.navigate(AppScreen.Library) }
        binding.btnFavorites.setOnClickListener { viewModel.navigate(AppScreen.Albums) }
        binding.tvSeeAll.setOnClickListener { viewModel.navigate(AppScreen.Library) }
        binding.tvArtistSeeAll.setOnClickListener { viewModel.navigate(AppScreen.Library) }
        binding.btnFeaturePlay.setOnClickListener { playFeaturedSong() }
        binding.featureCard.setOnClickListener { featuredSong?.let(::chooseArtwork) }

        listOf(
            binding.chipAll to "all",
            binding.chipTrending to "trending",
            binding.chipRecent to "recent",
            binding.chipTop to "top"
        ).forEach { (chip, filter) ->
            chip.setOnClickListener {
                selectedFilter = filter
                refreshHome()
            }
        }

        adapter = SongAdapter(
            onClick = { song ->
                val index = currentSongs.indexOfFirst { it.id == song.id }
                if (index >= 0) viewModel.playerController.playQueue(currentSongs, index)
                viewModel.navigate(AppScreen.Player)
            },
            onArtworkClick = ::chooseArtwork
        )
        binding.rvTopSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTopSongs.adapter = adapter
        UiMotion.animateList(binding.rvTopSongs)

        viewModel.playerState.observe(viewLifecycleOwner) { state ->
            val songId = state.currentSong?.id
            if (songId != lastHighlightedSongId) {
                lastHighlightedSongId = songId
                adapter.currentSongId = songId
                adapter.notifyDataSetChanged()
                bindFeaturedSong()
            } else {
                adapter.currentSongId = songId
            }
            if (state.isPlaying) refreshStats()
        }

        viewModel.analytics.observe(viewLifecycleOwner) {
            refreshHome()
            refreshStats()
        }
        viewModel.songs.observe(viewLifecycleOwner) { songs ->
            binding.tvSongCount.text = "${songs.size} songs on this device"
            bindArtists(songs)
            refreshHome()
        }
        viewModel.artworkUpdated.observe(viewLifecycleOwner) { songId ->
            if (songId != null) {
                adapter.notifyDataSetChanged()
                bindArtists(viewModel.songs.value.orEmpty())
                if (featuredSong?.id == songId) {
                    lastFeaturedSongId = null
                    bindFeaturedSong()
                }
            }
        }

        refreshStats()
        refreshHome()
    }

    private fun refreshHome() {
        val songs = viewModel.songs.value.orEmpty()
        val recent = songs.sortedByDescending { it.addedAt }
        val top = viewModel.computeSongStats(8)
            .mapNotNull { stat -> songs.find { it.id == stat.songId } }
        val trendingCutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        val trending = viewModel.analytics.value?.playEvents.orEmpty()
            .filter { it.startedAt >= trendingCutoff }
            .groupingBy { it.songId }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .mapNotNull { (songId, _) -> songs.find { it.id == songId } }
        currentSongs = when (selectedFilter) {
            "trending" -> trending.ifEmpty { recent }.take(8)
            "recent" -> recent.take(8)
            "top" -> top
            else -> recent.take(8)
        }
        binding.tvSongListCount.text = "${currentSongs.size} tracks"
        adapter.submitList(currentSongs)
        lastFeaturedSongId = null
        bindFeaturedSong()
    }

    private fun bindFeaturedSong() {
        val song = viewModel.playerState.value?.currentSong
            ?: currentSongs.firstOrNull()
            ?: viewModel.songs.value?.firstOrNull()
        featuredSong = song
        if (song == null) {
            lastFeaturedSongId = null
            binding.tvFeatureTitle.text = "Ready when you are"
            binding.tvFeatureArtist.text = "Your music, on this device"
            binding.ivFeatureArt.setImageResource(R.drawable.ic_artwork_default)
            return
        }
        if (lastFeaturedSongId == song.id) return
        lastFeaturedSongId = song.id
        binding.tvFeatureTitle.text = song.title
        binding.tvFeatureArtist.text = song.artist
        ArtworkLoader.load(song, binding.ivFeatureArt)
    }

    private fun playFeaturedSong() {
        val song = featuredSong ?: return
        val queue = if (currentSongs.any { it.id == song.id }) currentSongs else viewModel.songs.value.orEmpty()
        val index = queue.indexOfFirst { it.id == song.id }
        if (index >= 0) {
            viewModel.playerController.playQueue(queue, index)
            viewModel.navigate(AppScreen.Player)
        }
    }

    private fun chooseArtwork(song: Song) {
        (activity as? MainActivity)?.chooseArtwork(song)
    }

    private fun bindArtists(songs: List<Song>) {
        binding.artistContainer.removeAllViews()
        songs.groupBy { it.artist.trim().ifBlank { "Unknown Artist" } }
            .entries
            .sortedByDescending { it.value.size }
            .take(10)
            .forEach { (artist, artistSongs) ->
                val context = requireContext()
                val item = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    setPadding(dp(4), 0, dp(12), 0)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { viewModel.navigate(AppScreen.Library) }
                }
                val artwork = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(60), dp(60))
                    contentDescription = "$artist artwork"
                    clipToOutline = true
                }
                ArtworkLoader.load(artistSongs.first(), artwork, circular = true)
                val name = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.WRAP_CONTENT)
                    text = artist
                    textSize = 10f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = android.view.Gravity.CENTER
                    setTextColor(requireContext().getColor(R.color.text_secondary))
                    setPadding(0, dp(7), 0, 0)
                }
                item.addView(artwork)
                item.addView(name)
                binding.artistContainer.addView(item)
            }
    }

    private fun refreshStats() {
        binding.tvTodayTime.text = fmt(viewModel.getTodayListeningTimeMs())
        binding.tvTotalTime.text = fmt(viewModel.getTotalListeningTimeMs())
        binding.tvTotalPlays.text = "${viewModel.analytics.value?.playEvents?.size ?: 0}"
    }

    private fun fmt(ms: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
