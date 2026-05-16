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
        val main = requireActivity() as MainActivity

        binding.tvFolderPath.text = viewModel.storage.getSavedFolderName()
        binding.tvFolderUri.text = viewModel.storage.getSavedFolderUri()?.toString() ?: "—"
        binding.tvDataFile.text = "rhythmix_data.json"
        binding.tvDataNote.text =
            "Analytics are saved directly inside your music folder. " +
            "Uninstalling and reinstalling the app won't delete them — " +
            "just pick the same folder again to restore everything."

        viewModel.songs.observe(viewLifecycleOwner) { binding.tvSongCount.text = "${it.size}" }
        viewModel.analytics.observe(viewLifecycleOwner) { binding.tvEventCount.text = "${it.playEvents.size}" }

        binding.btnChangeFolder.setOnClickListener { main.folderPickerLauncher.launch(null) }
        binding.btnRescan.setOnClickListener {
            viewModel.rescan()
            viewModel.navigate(AppScreen.Scanning)
        }
        binding.btnClearData.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Clear History?")
                .setMessage("All play history will be removed from rhythmix_data.json. Music files are untouched.")
                .setPositiveButton("Clear") { _, _ -> viewModel.clearAnalytics() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
