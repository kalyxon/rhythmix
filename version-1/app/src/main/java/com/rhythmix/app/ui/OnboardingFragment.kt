package com.rhythmix.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.rhythmix.app.databinding.FragmentOnboardingBinding

class OnboardingFragment : Fragment() {

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val mainActivity = requireActivity() as MainActivity

        binding.btnPickFolder.setOnClickListener {
            // Launch the SAF folder picker — this is what gives us the persistent URI
            mainActivity.folderPickerLauncher.launch(null)
        }

        // Show saved folder name if returning user
        val savedName = viewModel.storage.getSavedFolderName()
        if (viewModel.storage.hasSavedFolder()) {
            binding.tvSavedFolder.visibility = View.VISIBLE
            binding.tvSavedFolder.text = "Last folder: $savedName"
            binding.btnPickFolder.text = "Choose Music Folder"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
