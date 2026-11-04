package com.rhythmix.app.ui

import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rhythmix.app.databinding.DialogCreateAlbumBinding
import com.rhythmix.app.model.Song
import com.rhythmix.app.ui.adapter.SongPickerAdapter

class CreateAlbumDialogFragment : DialogFragment() {

    companion object {
        private const val TAG = "CreateAlbumDialog"
        private const val ARG_ALBUM_ID = "album_id"
        private const val ARG_TEMPLATE_ALBUM_ID = "template_album_id"

        fun newInstance(albumId: String) = CreateAlbumDialogFragment().apply {
            arguments = Bundle().apply { putString(ARG_ALBUM_ID, albumId) }
        }

        fun newFromMix(albumId: String) = CreateAlbumDialogFragment().apply {
            arguments = Bundle().apply { putString(ARG_TEMPLATE_ALBUM_ID, albumId) }
        }
    }

    private var _binding: DialogCreateAlbumBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var pickerAdapter: SongPickerAdapter
    private var allSongs: List<Song> = emptyList()
    private var selectedStyle = "grid2x2"
    private var selectedCoverUri: Uri? = null
    private val coverPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) displaySelectedCover(uri)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogCreateAlbumBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        allSongs = viewModel.songs.value ?: emptyList()
        val editingAlbum = arguments?.getString(ARG_ALBUM_ID)?.let(viewModel::getAlbumById)
        val templateAlbum = arguments?.getString(ARG_TEMPLATE_ALBUM_ID)?.let(viewModel::getAlbumById)
        val sourceAlbum = editingAlbum ?: templateAlbum

        pickerAdapter = SongPickerAdapter { selectedIds ->
            binding.tvSelectedCount.text = "${selectedIds.size} selected"
            val selectedSongs = selectedIds.mapNotNull { id -> allSongs.find { it.id == id } }
            binding.collagePreview.songs = selectedSongs
        }

        binding.rvPicker.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPicker.adapter = pickerAdapter
        pickerAdapter.submitList(allSongs)
        if (sourceAlbum != null) {
            binding.tvDialogTitle.text = if (editingAlbum != null) "Edit Album" else "Customize Smart Mix"
            binding.btnCreate.text = if (editingAlbum != null) "Save Changes" else "Save My Album"
            binding.btnDeleteAlbum.visibility =
                if (viewModel.canDeleteAlbum(sourceAlbum)) View.VISIBLE else View.GONE
            binding.btnDeleteAlbum.contentDescription =
                if (sourceAlbum.category == "Custom") "Delete album" else "Delete smart mix"
            binding.btnDeleteAlbum.setOnClickListener {
                confirmDeleteAlbum(sourceAlbum.title, sourceAlbum.category == "Custom") {
                    viewModel.deleteAlbum(sourceAlbum.id)
                    dismiss()
                }
            }
            binding.etAlbumTitle.setText(sourceAlbum.title)
            binding.etAlbumDesc.setText(sourceAlbum.description)
            selectedStyle = sourceAlbum.collageStyle
            binding.collagePreview.albumTitle = sourceAlbum.title
            binding.collagePreview.collageStyle = selectedStyle
            binding.chipGrid.isChecked = selectedStyle == "grid2x2"
            binding.chipGradient.isChecked = selectedStyle == "gradient"
            pickerAdapter.setSelectedSongIds(sourceAlbum.songIds)
            selectedCoverUri = sourceAlbum.coverUri?.let(Uri::parse)
            selectedCoverUri?.let(::displaySelectedCover)
        }

        binding.etAlbumTitle.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                binding.collagePreview.albumTitle = s?.toString()?.takeIf { it.isNotBlank() } ?: "Album"
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.etSearchSong.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                val filtered = if (q.isEmpty()) allSongs else allSongs.filter {
                    it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
                }
                pickerAdapter.submitList(filtered)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.chipGrid.setOnClickListener {
            selectedStyle = "grid2x2"
            binding.collagePreview.collageStyle = selectedStyle
        }
        binding.chipGradient.setOnClickListener {
            selectedStyle = "gradient"
            binding.collagePreview.collageStyle = selectedStyle
        }

        binding.btnChooseCover.setOnClickListener { coverPicker.launch(arrayOf("image/*")) }
        binding.btnUseGeneratedCover.setOnClickListener {
            selectedCoverUri = null
            binding.ivCoverPreview.visibility = View.GONE
            binding.collagePreview.visibility = View.VISIBLE
            binding.btnUseGeneratedCover.visibility = View.GONE
            Glide.with(this).clear(binding.ivCoverPreview)
        }

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnCreate.setOnClickListener {
            val title = binding.etAlbumTitle.text?.toString() ?: ""
            val desc = binding.etAlbumDesc.text?.toString() ?: ""
            val selectedIds = pickerAdapter.getSelectedSongIds()
            fun saveChanges() {
                when {
                    templateAlbum != null -> viewModel.updateSmartMix(
                        templateAlbum.id,
                        title,
                        desc,
                        selectedIds,
                        selectedStyle,
                        selectedCoverUri?.toString()
                    )
                    editingAlbum != null -> viewModel.updateAlbum(
                        editingAlbum.id,
                        title,
                        desc,
                        selectedIds,
                        selectedStyle,
                        selectedCoverUri?.toString()
                    )
                    else -> viewModel.createAlbum(
                        title,
                        desc,
                        selectedIds,
                        selectedStyle,
                        selectedCoverUri?.toString()
                    )
                }
            }

            if (title.isBlank() || (selectedIds.isEmpty() && templateAlbum == null)) {
                saveChanges()
                return@setOnClickListener
            }
            selectedCoverUri?.let { uri ->
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (error: SecurityException) {
                    Log.e(TAG, "Unable to keep access to the selected album cover", error)
                    Toast.makeText(requireContext(), "Unable to save access to that image.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
            }

            saveChanges()
            dismiss()
        }
    }

    private fun displaySelectedCover(uri: Uri) {
        selectedCoverUri = uri
        binding.collagePreview.visibility = View.GONE
        binding.ivCoverPreview.visibility = View.VISIBLE
        binding.btnUseGeneratedCover.visibility = View.VISIBLE
        Glide.with(this)
            .load(uri)
            .centerCrop()
            .placeholder(com.rhythmix.app.R.drawable.ic_artwork_default)
            .error(com.rhythmix.app.R.drawable.ic_artwork_default)
            .into(binding.ivCoverPreview)
    }

    private fun confirmDeleteAlbum(title: String, isCustomAlbum: Boolean, onDelete: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isCustomAlbum) "Delete album?" else "Delete smart mix?")
            .setMessage("'$title' will be removed from your collection. Your music files won't be affected.")
            .setNegativeButton("Keep it", null)
            .setPositiveButton("Delete", null)
            .show()
            .apply {
                getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(requireContext().getColor(com.rhythmix.app.R.color.red))
                getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    dismiss()
                    onDelete()
                }
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
