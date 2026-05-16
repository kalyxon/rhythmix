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

        viewModel.scanProgress.observe(viewLifecycleOwner) { progress ->
            if (progress != null) {
                val pct = if (progress.total > 0) {
                    (progress.scanned * 100 / progress.total)
                } else 0
                binding.progressBar.progress = pct
                binding.tvProgress.text = "$pct%"
                binding.tvCount.text = "${progress.scanned} of ${progress.total} songs"
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
