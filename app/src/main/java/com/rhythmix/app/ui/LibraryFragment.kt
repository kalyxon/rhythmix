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
    private var query = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listOf(
            binding.chipTitle  to "title",
            binding.chipArtist to "artist",
            binding.chipRecent to "recent"
        ).forEach { (chip, mode) ->
            chip.setOnClickListener {
                viewModel.setSortMode(mode)
                binding.chipTitle.isChecked  = mode == "title"
                binding.chipArtist.isChecked = mode == "artist"
                binding.chipRecent.isChecked = mode == "recent"
            }
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; refresh() }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })

        adapter = SongAdapter { song ->
            // THE FIX: build the queue from exactly the list the user sees right now,
            // then seek to the tapped song's position within that same list.
            // Never mix indices from different list snapshots.
            val currentList = filtered()
            val idx = currentList.indexOfFirst { it.id == song.id }
            if (idx != -1) {
                viewModel.playerController.playQueue(currentList, idx)
            }
            viewModel.navigate(AppScreen.Player)
        }
        binding.rvSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSongs.adapter = adapter

        viewModel.playerState.observe(viewLifecycleOwner) { state ->
            adapter.currentSongId = state.currentSong?.id
            adapter.notifyDataSetChanged()
        }

        viewModel.songs.observe(viewLifecycleOwner) { refresh() }
        viewModel.sortMode.observe(viewLifecycleOwner) { refresh() }
        refresh()
    }

    private fun filtered(): List<Song> {
        val base = viewModel.getSortedSongs()
        return if (query.isBlank()) base
        else base.filter {
            it.title.contains(query, true) || it.artist.contains(query, true)
        }
    }

    private fun refresh() {
        val list = filtered()
        adapter.submitList(list)
        binding.tvCount.text = "${list.size} songs"
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
