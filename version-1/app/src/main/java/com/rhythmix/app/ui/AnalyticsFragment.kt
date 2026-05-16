package com.rhythmix.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.rhythmix.app.databinding.FragmentAnalyticsBinding
import com.rhythmix.app.ui.adapter.StatsAdapter
import java.util.concurrent.TimeUnit

class AnalyticsFragment : Fragment() {

    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnBack.setOnClickListener { viewModel.navigate(AppScreen.Home) }

        val adapter = StatsAdapter()
        binding.rvTopSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTopSongs.adapter = adapter

        viewModel.analytics.observe(viewLifecycleOwner) { data ->
            val events = data.playEvents
            val totalMs = viewModel.getTotalListeningTimeMs()
            val todayMs = viewModel.getTodayListeningTimeMs()

            binding.tvTotalListening.text = formatDuration(totalMs)
            binding.tvTodayListening.text = formatDuration(todayMs)
            binding.tvTotalPlays.text = "${events.size}"
            binding.tvUniqueSongs.text = "${events.map { it.songId }.distinct().size}"

            // Completion rate
            val completed = events.count { it.completed }
            val completionRate = if (events.isNotEmpty()) (completed * 100 / events.size) else 0
            binding.tvCompletionRate.text = "$completionRate%"

            // Avg session
            val avgMs = if (events.isNotEmpty()) events.sumOf { it.listenedMs } / events.size else 0L
            binding.tvAvgSession.text = formatDurationShort(avgMs)

            // Peak hour
            val peakHour = events
                .groupBy { java.util.Calendar.getInstance().also { c -> c.timeInMillis = it.startedAt }.get(java.util.Calendar.HOUR_OF_DAY) }
                .maxByOrNull { it.value.size }?.key
            binding.tvPeakHour.text = if (peakHour != null) {
                val amPm = if (peakHour < 12) "AM" else "PM"
                val h = if (peakHour % 12 == 0) 12 else peakHour % 12
                "$h $amPm"
            } else "—"

            // Top songs
            val stats = viewModel.computeSongStats(10)
            adapter.submitList(stats)
        }
    }

    private fun formatDuration(ms: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    private fun formatDurationShort(ms: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
