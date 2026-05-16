package com.rhythmix.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.rhythmix.app.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = requireActivity() as MainActivity

        binding.btnBack.setOnClickListener { viewModel.navigate(AppScreen.Home) }

        binding.tvFolderPath.text = viewModel.storage.getSavedFolderName()
        binding.tvFolderUri.text = viewModel.storage.getSavedFolderUri()?.toString() ?: "—"

        viewModel.songs.observe(viewLifecycleOwner) { songs ->
            binding.tvSongCount.text = "${songs.size} songs"
        }
        viewModel.analytics.observe(viewLifecycleOwner) { data ->
            binding.tvEventCount.text = "${data.playEvents.size} play events"
        }

        binding.btnChangeFolder.setOnClickListener {
            mainActivity.folderPickerLauncher.launch(null)
        }

        binding.btnRescan.setOnClickListener {
            viewModel.rescan()
            viewModel.navigate(AppScreen.Scanning)
        }

        binding.btnClearData.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Clear Listening History")
                .setMessage("This will delete all analytics data from rhythmix_data.json in your music folder. Your music files will not be affected.\n\nThis cannot be undone.")
                .setPositiveButton("Clear") { _, _ -> viewModel.clearAnalytics() }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Data file info
        binding.tvDataFile.text = "rhythmix_data.json (in your music folder)"
        binding.tvDataNote.text =
            "Analytics are stored directly in your music folder as a JSON file. " +
            "This means data persists even if you uninstall and reinstall this app — " +
            "just choose the same folder again."
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
