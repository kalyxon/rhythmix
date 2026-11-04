package com.rhythmix.app.ui

import android.os.Bundle
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rhythmix.app.databinding.FragmentLibraryBinding
import com.rhythmix.app.databinding.ItemLibraryHeaderBinding
import com.rhythmix.app.model.Song
import com.rhythmix.app.ui.adapter.SongAdapter

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: SongAdapter
    private lateinit var headerAdapter: LibraryHeaderAdapter
    private var visibleSongs = emptyList<Song>()
    private var searchQuery = ""

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
        headerAdapter = LibraryHeaderAdapter(
            onSearchChanged = { query ->
                searchQuery = query
                refresh()
            },
            onSortClicked = ::showSortOptions,
            onSleepClicked = ::showSleepTimerOptions
        )
        binding.rvSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSongs.adapter = ConcatAdapter(headerAdapter, adapter)

        viewModel.playerState.observe(viewLifecycleOwner) { state ->
            val songId = state.currentSong?.id
            if (songId != adapter.currentSongId) {
                adapter.currentSongId = songId
                adapter.notifyDataSetChanged()
            }
            headerAdapter.setSleepTimerActive(state.sleepTimerRemainingSec > 0)
        }
        viewModel.songs.observe(viewLifecycleOwner) { refresh() }
        viewModel.sortMode.observe(viewLifecycleOwner) { refresh() }
        viewModel.artworkUpdated.observe(viewLifecycleOwner) { songId ->
            if (songId != null) adapter.notifyArtworkChanged(songId)
        }
        refresh()
    }

    private fun refresh() {
        val allSongs = viewModel.getSortedSongs()
        val query = searchQuery.trim()
        val songs = if (query.isBlank()) {
            allSongs
        } else {
            allSongs.filter { song ->
                song.title.contains(query, ignoreCase = true) ||
                    song.artist.contains(query, ignoreCase = true) ||
                    song.album.contains(query, ignoreCase = true)
            }
        }
        visibleSongs = songs
        headerAdapter.setCount(
            if (query.isBlank()) "${allSongs.size} tracks"
            else "${songs.size} of ${allSongs.size} tracks"
        )
        val playingSongId = viewModel.playerState.value?.currentSong?.id
        adapter.currentSongId = playingSongId
        adapter.submitList(songs)
        binding.emptyLibrary.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
        binding.rvSongs.visibility = View.VISIBLE
        if (songs.isEmpty()) {
            val noSongs = allSongs.isEmpty()
            binding.tvEmptyTitle.text = when {
                noSongs -> "Your library is empty"
                query.isNotBlank() -> "No matching songs"
                else -> "No songs found"
            }
            binding.tvEmptyHint.text = when {
                noSongs -> "Choose a music folder in Settings to get started."
                query.isNotBlank() -> "Try another title, artist, or album."
                else -> "Add music to your selected folder and rescan."
            }
        }
    }

    private fun showSortOptions() {
        val modes = listOf("title", "artist", "album", "recent")
        val labels = arrayOf("Title", "Artist", "Album", "Recently added")
        val checkedIndex = modes.indexOf(viewModel.sortMode.value).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Sort songs")
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                viewModel.setSortMode(modes[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSleepTimerOptions() {
        val labels = arrayOf("Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes")
        val minutes = intArrayOf(0, 15, 30, 45, 60)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Sleep timer")
            .setItems(labels) { _, which ->
                viewModel.playerController.setSleepTimer(minutes[which])
            }
            .show()
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

    private class LibraryHeaderAdapter(
        private val onSearchChanged: (String) -> Unit,
        private val onSortClicked: () -> Unit,
        private val onSleepClicked: () -> Unit
    ) : RecyclerView.Adapter<LibraryHeaderAdapter.HeaderHolder>() {

        private var trackCountText = "0 tracks"
        private var sleepTimerActive = false
        private var boundHolder: HeaderHolder? = null

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeaderHolder {
            val binding = ItemLibraryHeaderBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return HeaderHolder(
                binding,
                onSearchChanged,
                onSortClicked,
                onSleepClicked
            )
        }

        override fun onBindViewHolder(holder: HeaderHolder, position: Int) {
            boundHolder = holder
            holder.binding.tvLibraryCount.text = trackCountText
            holder.binding.btnLibrarySleep.contentDescription =
                if (sleepTimerActive) "Sleep timer active" else "Set sleep timer"
        }

        override fun onViewRecycled(holder: HeaderHolder) {
            if (boundHolder === holder) boundHolder = null
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = 1

        fun setCount(text: String) {
            if (trackCountText == text) return
            trackCountText = text
            boundHolder?.binding?.tvLibraryCount?.text = text
        }

        fun setSleepTimerActive(active: Boolean) {
            if (sleepTimerActive == active) return
            sleepTimerActive = active
            boundHolder?.binding?.btnLibrarySleep?.contentDescription =
                if (active) "Sleep timer active" else "Set sleep timer"
        }

        class HeaderHolder(
            val binding: ItemLibraryHeaderBinding,
            onSearchChanged: (String) -> Unit,
            onSortClicked: () -> Unit,
            onSleepClicked: () -> Unit
        ) : RecyclerView.ViewHolder(binding.root) {
            init {
                binding.btnLibrarySort.setOnClickListener { onSortClicked() }
                binding.btnLibrarySleep.setOnClickListener { onSleepClicked() }
                binding.btnLibrarySearch.setOnClickListener {
                    val search = binding.etLibrarySearch
                    val inputMethod = search.context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as InputMethodManager
                    if (search.visibility == View.GONE) {
                        search.visibility = View.VISIBLE
                        search.requestFocus()
                        inputMethod.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
                    } else {
                        search.text.clear()
                        search.visibility = View.GONE
                        inputMethod.hideSoftInputFromWindow(search.windowToken, 0)
                    }
                }
                binding.etLibrarySearch.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) = Unit

                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) {
                        onSearchChanged(s?.toString().orEmpty())
                    }

                    override fun afterTextChanged(s: android.text.Editable?) = Unit
                })
            }
        }
    }
}
