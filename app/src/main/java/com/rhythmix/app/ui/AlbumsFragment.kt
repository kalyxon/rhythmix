package com.rhythmix.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.rhythmix.app.databinding.FragmentAlbumsBinding
import com.rhythmix.app.model.Album
import com.rhythmix.app.ui.adapter.AlbumAdapter
import com.rhythmix.app.ui.UiMotion

class AlbumsFragment : Fragment() {

    private var _binding: FragmentAlbumsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var smartAdapter: AlbumAdapter
    private lateinit var customAdapter: AlbumAdapter
    private var query = ""
    private var albumFilter = "all"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAlbumsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UiMotion.enter(binding.root)

        val songMap = { id: String -> viewModel.songs.value?.find { it.id == id } }

        smartAdapter = AlbumAdapter(
            getSongById = songMap,
            onAlbumClick = { album -> viewModel.navigate(AppScreen.AlbumDetail(album.id)) }
        )
        binding.rvSmartAlbums.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvSmartAlbums.adapter = smartAdapter

        customAdapter = AlbumAdapter(
            getSongById = songMap,
            onAlbumClick = { album -> viewModel.navigate(AppScreen.AlbumDetail(album.id)) }
        )
        binding.rvCustomAlbums.layoutManager = GridLayoutManager(requireContext(), 2)
        binding.rvCustomAlbums.adapter = customAdapter

        binding.fabCreateAlbum.setOnClickListener {
            CreateAlbumDialogFragment().show(childFragmentManager, "CreateAlbumDialog")
        }

        listOf(
            binding.chipAlbumsAll to "all",
            binding.chipAlbumsSmart to "smart",
            binding.chipAlbumsCustom to "custom"
        ).forEach { (chip, filter) ->
            chip.setOnClickListener {
                albumFilter = filter
                refresh()
            }
        }

        binding.etSearchAlbum.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                refresh()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        viewModel.songs.observe(viewLifecycleOwner) { refresh() }
        viewModel.analytics.observe(viewLifecycleOwner) { refresh() }
        viewModel.artworkUpdated.observe(viewLifecycleOwner) { songId ->
            if (songId != null) {
                smartAdapter.refreshArtwork()
                customAdapter.refreshArtwork()
            }
        }

        refresh()
    }

    private fun refresh() {
        val smartAlbums = viewModel.getSmartCategoryAlbums().filter(::matchesSearch)
        smartAdapter.submitList(smartAlbums)

        val customAlbums = viewModel.analytics.value?.albums.orEmpty().filter(::matchesSearch)
        customAdapter.submitList(customAlbums)

        val allCustomAlbums = viewModel.analytics.value?.albums.orEmpty()
        val showSmart = albumFilter != "custom"
        val showCustom = albumFilter != "smart"
        binding.smartAlbumHeader.visibility = if (showSmart) View.VISIBLE else View.GONE
        binding.rvSmartAlbums.visibility = if (showSmart && smartAlbums.isNotEmpty()) View.VISIBLE else View.GONE
        binding.customAlbumHeader.visibility = if (showCustom) View.VISIBLE else View.GONE
        binding.emptyCustomLayout.visibility = if (
            showCustom && allCustomAlbums.isEmpty() && query.isBlank()
        ) View.VISIBLE else View.GONE
        binding.rvCustomAlbums.visibility =
            if (showCustom && customAlbums.isNotEmpty()) View.VISIBLE else View.GONE

        val noMatches = query.isNotBlank() &&
            (albumFilter == "custom" && customAlbums.isEmpty() ||
                albumFilter == "smart" && smartAlbums.isEmpty() ||
                albumFilter == "all" && smartAlbums.isEmpty() && customAlbums.isEmpty())
        binding.tvNoAlbumResults.visibility = if (noMatches) View.VISIBLE else View.GONE
    }

    private fun matchesSearch(album: Album): Boolean {
        if (query.isBlank()) return true
        return album.title.contains(query, ignoreCase = true) ||
            album.category.contains(query, ignoreCase = true) ||
            album.description.contains(query, ignoreCase = true) ||
            album.songIds.any { id ->
                viewModel.songs.value.orEmpty().any { song ->
                    song.id == id && (song.title.contains(query, true) || song.artist.contains(query, true))
                }
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
