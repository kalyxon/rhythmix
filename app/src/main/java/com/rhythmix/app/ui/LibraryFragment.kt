package com.rhythmix.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
    private var searchQuery = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { viewModel.navigate(AppScreen.Home) }

        // Sort chips
        val chips = listOf(
            binding.chipTitle to "title",
            binding.chipArtist to "artist",
            binding.chipRecent to "recent"
        )
        chips.forEach { (chip, mode) ->
            chip.setOnClickListener {
                viewModel.setSortMode(mode)
                chips.forEach { (c, _) -> c.isChecked = false }
                chip.isChecked = true
            }
        }

        // Search
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString() ?: ""
                refreshList()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        adapter = SongAdapter { song ->
            val songs = getFilteredSongs()
            val idx = songs.indexOfFirst { it.id == song.id }
            viewModel.playerController.setQueue(songs, idx.coerceAtLeast(0))
            viewModel.navigate(AppScreen.Player)
        }
        binding.rvSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSongs.adapter = adapter

        viewModel.songs.observe(viewLifecycleOwner) { refreshList() }
        viewModel.sortMode.observe(viewLifecycleOwner) { refreshList() }
        refreshList()
    }

    private fun getFilteredSongs(): List<Song> {
        val sorted = viewModel.getSortedSongs()
        return if (searchQuery.isBlank()) sorted
        else sorted.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.artist.contains(searchQuery, ignoreCase = true) ||
            it.album.contains(searchQuery, ignoreCase = true)
        }
    }

    private fun refreshList() {
        val filtered = getFilteredSongs()
        adapter.submitList(filtered)
        binding.tvCount.text = "${filtered.size} songs"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
