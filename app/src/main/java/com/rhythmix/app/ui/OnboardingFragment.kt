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
        (requireActivity() as MainActivity).also { main ->
            binding.btnPickFolder.setOnClickListener { main.folderPickerLauncher.launch(null) }
        }
        if (viewModel.storage.hasSavedFolder()) {
            binding.tvSavedFolder.visibility = View.VISIBLE
            binding.tvSavedFolder.text = "Last: ${viewModel.storage.getSavedFolderName()}"
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
