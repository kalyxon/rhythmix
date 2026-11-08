package com.rhythmix.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.rhythmix.app.databinding.FragmentScanningBinding

class ScanningFragment : Fragment() {

    private var _binding: FragmentScanningBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentScanningBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UiMotion.enter(binding.root)
        viewModel.scanProgress.observe(viewLifecycleOwner) { p ->
            if (p != null && p.total > 0) {
                val pct = p.scanned * 100 / p.total
                binding.progressBar.progress = pct
                binding.tvProgress.text = "$pct%"
                binding.tvCount.text = "${p.scanned} of ${p.total} songs"
            }
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
