package com.rhythmix.app.ui

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.rhythmix.app.R
import com.rhythmix.app.databinding.FragmentAnalyticsBinding
import com.rhythmix.app.model.PlayEvent
import com.rhythmix.app.ui.adapter.StatsAdapter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class AnalyticsFragment : Fragment() {

    private var _binding: FragmentAnalyticsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    // Cell dimensions — computed once in dp→px, reused everywhere
    private val CELL_DP   = 11
    private val GAP_DP    = 2
    private val WEEKS     = 52
    private val DAYS      = 7
    // Day-label column width in dp
    private val DAY_COL_DP = 16

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = StatsAdapter()
        binding.rvTopSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTopSongs.adapter = adapter
        binding.rvTopSongs.setHasFixedSize(false)
        // isNestedScrollingEnabled=false lets the parent ScrollView handle scrolling
        // while still measuring the RecyclerView to its FULL height (all items visible)
        binding.rvTopSongs.isNestedScrollingEnabled = false

        viewModel.analytics.observe(viewLifecycleOwner) { data ->
            refreshDashboard(adapter, data.playEvents)
        }
        // Update both time counters live while a song is playing (positionMs ticks every 200ms)
        viewModel.playerState.observe(viewLifecycleOwner) { state ->
            if (state.isPlaying) {
                binding.tvTodayListening.text = fmtDur(viewModel.getTodayListeningTimeMs())
                binding.tvTotalListening.text = fmtDur(viewModel.getTotalListeningTimeMs())
            }
        }

        refreshDashboard(adapter, viewModel.analytics.value?.playEvents ?: emptyList())
    }

    // ── Dashboard ─────────────────────────────────────────────────────────────

    private fun refreshDashboard(adapter: StatsAdapter, events: List<PlayEvent>) {
        binding.tvTodayListening.text  = fmtDur(viewModel.getTodayListeningTimeMs())
        binding.tvTotalListening.text  = fmtDur(viewModel.getTotalListeningTimeMs())
        binding.tvTotalPlays.text      = "${events.size}"
        binding.tvUniqueSongs.text     = "${events.map { it.songId }.distinct().size}"

        val done = events.count { it.completed }
        binding.tvCompletionRate.text  = if (events.isNotEmpty()) "${done * 100 / events.size}%" else "—"

        val avg = if (events.isNotEmpty()) events.sumOf { it.listenedMs } / events.size else 0L
        binding.tvAvgSession.text      = fmtShort(avg)

        val peak = events
            .groupBy {
                Calendar.getInstance().also { c -> c.timeInMillis = it.startedAt }
                    .get(Calendar.HOUR_OF_DAY)
            }
            .maxByOrNull { it.value.size }?.key
        binding.tvPeakHour.text = if (peak != null) {
            "${if (peak % 12 == 0) 12 else peak % 12} ${if (peak < 12) "AM" else "PM"}"
        } else "—"

        buildHeatmap(events)
        adapter.submitList(viewModel.computeSongStats(10))
    }

    // ── Heatmap ───────────────────────────────────────────────────────────────

    /**
     * GitHub-style 52-week listening heatmap.
     *
     * Structure (all inside one HorizontalScrollView so everything scrolls together):
     *
     *   [day-label col] | [month-label row]
     *                   | [week columns grid]
     *
     * Day labels are built programmatically using the SAME cell/gap pixel values
     * as the grid cells, so they always align perfectly regardless of screen density.
     *
     * Month labels are placed as spacer+text inside the same horizontal LinearLayout
     * that contains the week columns, so they scroll with the grid 1:1.
     */
    private fun buildHeatmap(events: List<PlayEvent>) {
        val ctx  = requireContext()
        val den  = ctx.resources.displayMetrics.density
        val cell = (CELL_DP * den).toInt()   // cell width and height in px
        val gap  = (GAP_DP  * den).toInt()   // gap between cells in px
        val dayColW = (DAY_COL_DP * den).toInt()

        // ── 1. Build day buckets ─────────────────────────────────────────────
        val fmt      = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val msPerDay = mutableMapOf<String, Long>()
        for (e in events) {
            val key = fmt.format(Date(e.startedAt))
            msPerDay[key] = (msPerDay[key] ?: 0L) + e.listenedMs
        }
        val maxMs = msPerDay.values.maxOrNull()?.coerceAtLeast(1L) ?: 1L

        // ── 2. Compute start day (Monday of week 52 weeks ago) ───────────────
        val today    = Calendar.getInstance()
        val startCal = (today.clone() as Calendar).also {
            it.add(Calendar.WEEK_OF_YEAR, -(WEEKS - 1))
            // Roll back to Monday of that week
            val dow = it.get(Calendar.DAY_OF_WEEK)
            val toMon = if (dow == Calendar.SUNDAY) -6 else Calendar.MONDAY - dow
            it.add(Calendar.DAY_OF_MONTH, toMon)
        }

        // ── 3. Build day-label column ────────────────────────────────────────
        val dayLabelsCol = binding.heatmapDayLabels
        dayLabelsCol.removeAllViews()
        dayLabelsCol.orientation = LinearLayout.VERTICAL

        // Top spacer = height of month-label row (same as one cell row)
        dayLabelsCol.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dayColW, cell + gap)
        })

        // Mon / Tue / Wed / Thu / Fri / Sat / Sun
        val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        // Only show Mon, Wed, Fri to avoid crowding (indices 0, 2, 4)
        for (d in 0 until DAYS) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(dayColW, cell).also {
                    it.bottomMargin = gap
                }
            }
            val showLabel = d % 2 == 0   // Mon(0), Wed(2), Fri(4), Sun(6)
            val tv = TextView(ctx).apply {
                text = if (showLabel) dayNames[d] else ""
                textSize = 7f
                setTextColor(Color.parseColor("#555555"))
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                setPadding(0, 0, (4 * den).toInt(), 0)
            }
            row.addView(tv)
            dayLabelsCol.addView(row)
        }

        // ── 4. Build month-label row + grid together ─────────────────────────
        val monthLabels = binding.heatmapMonthLabels
        val grid        = binding.heatmapGrid
        monthLabels.removeAllViews()
        grid.removeAllViews()

        val monthNames     = arrayOf("Jan","Feb","Mar","Apr","May","Jun",
                                     "Jul","Aug","Sep","Oct","Nov","Dec")
        val iterCal        = startCal.clone() as Calendar
        var lastMonthAdded = -1

        for (week in 0 until WEEKS) {
            val weekStartMonth = iterCal.get(Calendar.MONTH)

            // ── Month label cell (same width as week column) ─────────────────
            // We add one label per column; only show text when month changes
            val showMonth = weekStartMonth != lastMonthAdded
            val monthCell = TextView(ctx).apply {
                text = if (showMonth) monthNames[weekStartMonth] else ""
                textSize = 8f
                setTextColor(Color.parseColor("#666666"))
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(cell, cell).also {
                    it.rightMargin = gap
                }
                if (showMonth) setTypeface(null, Typeface.BOLD)
            }
            monthLabels.addView(monthCell)
            if (showMonth) lastMonthAdded = weekStartMonth

            // ── Week column (7 day cells) ────────────────────────────────────
            val weekCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(cell, LinearLayout.LayoutParams.WRAP_CONTENT).also {
                    it.rightMargin = gap
                }
            }

            for (day in 0 until DAYS) {
                val dateKey  = fmt.format(iterCal.time)
                val listenMs = msPerDay[dateKey] ?: 0L
                val intensity = when {
                    listenMs <= 0           -> 0
                    listenMs < maxMs * 0.25 -> 1
                    listenMs < maxMs * 0.50 -> 2
                    listenMs < maxMs * 0.75 -> 3
                    else                    -> 4
                }
                val bgRes = when (intensity) {
                    0    -> R.drawable.bg_heatmap_0
                    1    -> R.drawable.bg_heatmap_1
                    2    -> R.drawable.bg_heatmap_2
                    3    -> R.drawable.bg_heatmap_3
                    else -> R.drawable.bg_heatmap_4
                }

                val mins  = TimeUnit.MILLISECONDS.toMinutes(listenMs).toInt()
                val label = if (listenMs > 0) "$mins min · $dateKey" else "No listening · $dateKey"

                val cellView = View(ctx).apply {
                    setBackgroundResource(bgRes)
                    layoutParams = LinearLayout.LayoutParams(cell, cell).also {
                        it.bottomMargin = gap
                    }
                    setOnClickListener {
                        Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
                    }
                    // Accessibility
                    contentDescription = label
                }
                weekCol.addView(cellView)
                iterCal.add(Calendar.DAY_OF_MONTH, 1)
            }
            grid.addView(weekCol)
        }

        // ── 5. Subtitle ──────────────────────────────────────────────────────
        val activeDays = msPerDay.values.count { it > 0 }
        binding.tvHeatmapSubtitle.text = "$activeDays active days in the past year"
    }

    // ── Formatters ────────────────────────────────────────────────────────────

    private fun fmtDur(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun fmtShort(ms: Long): String {
        val m = TimeUnit.MILLISECONDS.toMinutes(ms)
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return if (m > 0) "${m}m" else "${s}s"
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
