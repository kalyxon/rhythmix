package com.rhythmix.app.ui

import android.animation.ValueAnimator
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.core.graphics.ColorUtils
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
    private var selectedPeriod = "week"
    private var latestEvents: List<PlayEvent> = emptyList()
    private var totalListeningAnimator: ValueAnimator? = null
    private var displayedTotalListeningMinutes: Int? = null
    private var lastTotalListeningTargetMinutes: Int? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UiMotion.enter(binding.root)

        val adapter = StatsAdapter { stats ->
            val songs = viewModel.getSortedSongs()
            val index = songs.indexOfFirst { it.id == stats.songId }
            if (index >= 0) {
                viewModel.playerController.playQueue(songs, index)
                viewModel.navigate(AppScreen.Player)
            }
        }
        binding.rvTopSongs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvTopSongs.adapter = adapter
        binding.rvTopSongs.setHasFixedSize(false)
        // isNestedScrollingEnabled=false lets the parent ScrollView handle scrolling
        // while still measuring the RecyclerView to its FULL height (all items visible)
        binding.rvTopSongs.isNestedScrollingEnabled = false
        UiMotion.animateList(binding.rvTopSongs)

        listOf(
            binding.periodWeek to "week",
            binding.periodMonth to "month",
            binding.periodYear to "year"
        ).forEach { (chip, period) ->
            chip.setOnClickListener {
                selectedPeriod = period
                updatePeriodControl()
                refreshDashboard(adapter, latestEvents)
            }
        }
        updatePeriodControl()

        viewModel.analytics.observe(viewLifecycleOwner) { data ->
            latestEvents = data.playEvents
            refreshDashboard(adapter, data.playEvents)
        }
        viewModel.songs.observe(viewLifecycleOwner) {
            refreshDashboard(adapter, latestEvents)
        }
        viewModel.reduceMotion.observe(viewLifecycleOwner) { enabled ->
            binding.listeningRing.setMotionEnabled(!enabled)
        }
        // Update both time counters live while a song is playing (positionMs ticks every 200ms)
        viewModel.playerState.observe(viewLifecycleOwner) { state ->
            if (state.isPlaying) {
                binding.tvTodayListening.text = fmtDur(viewModel.getTodayListeningTimeMs())
                renderTotalListeningTime(viewModel.getTotalListeningTimeMs())
            }
        }

        refreshDashboard(adapter, viewModel.analytics.value?.playEvents ?: emptyList())
    }

    // ── Dashboard ─────────────────────────────────────────────────────────────

    private fun refreshDashboard(adapter: StatsAdapter, events: List<PlayEvent>) {
        val cutoff = periodStart()
        val periodEvents = events.filter { cutoff == null || it.startedAt >= cutoff }
        binding.tvTodayListening.text  = fmtDur(viewModel.getTodayListeningTimeMs())
        renderTotalListeningTime(viewModel.getTotalListeningTimeMs())
        val activeDayStarts = events.asSequence()
            .filter { it.listenedMs > 0L }
            .map { event ->
                Calendar.getInstance().apply {
                    timeInMillis = event.startedAt
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
            }
            .toSet()
        val activeDays = activeDayStarts.size
        val averageActiveDay = if (activeDays > 0) {
            events.sumOf { it.listenedMs } / activeDays
        } else 0L
        binding.tvActiveDayAverage.text = fmtDur(averageActiveDay)
        val streak = currentListeningStreak(activeDayStarts)
        binding.tvCurrentStreak.text = if (streak > 0) {
            "🔥 $streak ${if (streak == 1) "day" else "days"} streak"
        } else {
            "🔥 Start a new streak"
        }
        binding.tvTotalPlays.text      = "${periodEvents.size}"
        binding.tvUniqueSongs.text     = "${periodEvents.map { it.songId }.distinct().size}"

        val done = periodEvents.count { it.completed }
        binding.tvCompletionRate.text  =
            if (periodEvents.isNotEmpty()) "${done * 100 / periodEvents.size}%" else "—"

        val avg = if (periodEvents.isNotEmpty()) {
            periodEvents.sumOf { it.listenedMs } / periodEvents.size
        } else 0L
        binding.tvAvgSession.text      = fmtShort(avg)

        val peak = periodEvents
            .groupBy {
                Calendar.getInstance().also { c -> c.timeInMillis = it.startedAt }
                    .get(Calendar.HOUR_OF_DAY)
            }
            .maxByOrNull { it.value.size }?.key
        binding.tvPeakHour.text = if (peak != null) {
            "${if (peak % 12 == 0) 12 else peak % 12} ${if (peak < 12) "AM" else "PM"}"
        } else "—"

        buildHeatmap(events)
        binding.tvStatsPeriod.text = when (selectedPeriod) {
            "week" -> "Most played · Last 7 days"
            "month" -> "Most played · Last 30 days"
            else -> "Most played · Last year"
        }
        adapter.submitList(viewModel.computeSongStats(10, cutoff))
    }

    private fun renderTotalListeningTime(totalMs: Long) {
        val targetMinutes = TimeUnit.MILLISECONDS.toMinutes(totalMs).toInt().coerceAtLeast(0)
        if (targetMinutes == lastTotalListeningTargetMinutes) return

        val startMinutes = totalListeningAnimator?.animatedValue as? Int
            ?: displayedTotalListeningMinutes
            ?: 0
        totalListeningAnimator?.cancel()
        lastTotalListeningTargetMinutes = targetMinutes

        if (UiMotion.isReduced(requireContext()) || startMinutes == targetMinutes) {
            displayedTotalListeningMinutes = targetMinutes
            binding.tvTotalListening.text = fmtDur(TimeUnit.MINUTES.toMillis(targetMinutes.toLong()))
            totalListeningAnimator = null
            return
        }

        totalListeningAnimator = ValueAnimator.ofInt(startMinutes, targetMinutes).apply {
            duration = if (displayedTotalListeningMinutes == null) 900L else 650L
            addUpdateListener { animator ->
                val minutes = animator.animatedValue as Int
                displayedTotalListeningMinutes = minutes
                binding.tvTotalListening.text = fmtDur(TimeUnit.MINUTES.toMillis(minutes.toLong()))
            }
            start()
        }
    }

    private fun currentListeningStreak(activeDayStarts: Set<Long>): Int {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val streakDay = when {
            activeDayStarts.contains(today.timeInMillis) -> today
            activeDayStarts.contains(
                (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
            ) -> (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
            else -> return 0
        }

        var days = 0
        while (activeDayStarts.contains(streakDay.timeInMillis)) {
            days++
            streakDay.add(Calendar.DAY_OF_YEAR, -1)
        }
        return days
    }

    private fun updatePeriodControl() {
        listOf(
            binding.periodWeek to "week",
            binding.periodMonth to "month",
            binding.periodYear to "year"
        ).forEach { (option, period) ->
            val selected = selectedPeriod == period
            option.setBackgroundResource(
                if (selected) R.drawable.bg_period_selected else R.drawable.bg_period_unselected
            )
            option.setTextColor(
                if (selected) resolveThemeColor(com.google.android.material.R.attr.colorOnPrimary)
                else requireContext().getColor(R.color.text_secondary)
            )
            option.isSelected = selected
        }
    }

    private fun periodStart(): Long? {
        val days = when (selectedPeriod) {
            "week" -> 7
            "month" -> 30
            "year" -> 365
            else -> return null
        }
        return Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }.timeInMillis
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
        val heatmapBase = resolveThemeColor(com.google.android.material.R.attr.colorSurfaceVariant)
        val heatmapAccent = resolveThemeColor(androidx.appcompat.R.attr.colorPrimary)
        listOf(
            binding.heatmapLegend0,
            binding.heatmapLegend1,
            binding.heatmapLegend2,
            binding.heatmapLegend3,
            binding.heatmapLegend4
        ).forEachIndexed { index, legend ->
            val blend = when (index) {
                0 -> 0f
                1 -> 0.18f
                2 -> 0.38f
                3 -> 0.62f
                else -> 0.88f
            }
            legend.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 3f * den
                setColor(ColorUtils.blendARGB(heatmapBase, heatmapAccent, blend))
            }
        }

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
                setTextColor(ctx.getColor(R.color.text_tertiary))
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
                setTextColor(ctx.getColor(R.color.text_tertiary))
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
                val blend = when (intensity) {
                    0 -> 0f
                    1 -> 0.18f
                    2 -> 0.38f
                    3 -> 0.62f
                    else -> 0.88f
                }

                val mins  = TimeUnit.MILLISECONDS.toMinutes(listenMs).toInt()
                val label = if (listenMs > 0) "$mins min · $dateKey" else "No listening · $dateKey"

                val cellView = View(ctx).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 3f * den
                        setColor(ColorUtils.blendARGB(heatmapBase, heatmapAccent, blend))
                    }
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

    private fun resolveThemeColor(attribute: Int): Int {
        val value = TypedValue()
        requireContext().theme.resolveAttribute(attribute, value, true)
        return value.data
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

    override fun onDestroyView() {
        totalListeningAnimator?.cancel()
        totalListeningAnimator = null
        displayedTotalListeningMinutes = null
        lastTotalListeningTargetMinutes = null
        super.onDestroyView()
        _binding = null
    }
}
