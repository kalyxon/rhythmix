package com.rhythmix.app.ui

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.media3.common.Player
import com.rhythmix.app.R
import com.rhythmix.app.databinding.FragmentPlayerBinding
import kotlin.math.abs

class PlayerFragment : Fragment() {

    private var _binding: FragmentPlayerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private var isSeeking = false
    private var pulseAnim: ValueAnimator? = null

    // Track last rendered song id — detect actual song changes
    private var lastRenderedSongId: String? = null

    private val artPalette = listOf(
        intArrayOf(0xC8, 0xFF, 0x00),
        intArrayOf(0x9B, 0x6D, 0xFF),
        intArrayOf(0xFF, 0x4D, 0x8F),
        intArrayOf(0x00, 0xD4, 0xFF),
        intArrayOf(0xFF, 0x8C, 0x42),
        intArrayOf(0x1D, 0xB9, 0x54),
        intArrayOf(0xFF, 0xB8, 0x30),
        intArrayOf(0xFF, 0x44, 0x44),
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val pc = viewModel.playerController

        binding.btnBack.setOnClickListener { viewModel.navigate(AppScreen.Home) }
        binding.btnPlay.setOnClickListener { pc.togglePlay() }
        binding.btnNext.setOnClickListener { pc.skipNext() }
        binding.btnPrev.setOnClickListener { pc.skipPrev() }
        binding.btnShuffle.setOnClickListener { pc.toggleShuffle() }
        binding.btnRepeat.setOnClickListener { pc.cycleRepeat() }
        binding.btnQueue.setOnClickListener { viewModel.navigate(AppScreen.Library) }

        binding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = viewModel.playerState.value?.durationMs ?: 0L
                    binding.tvPosition.text = fmtMs((p / 1000f * dur).toLong())
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                isSeeking = false
                val dur = viewModel.playerState.value?.durationMs ?: 0L
                pc.seekTo(((sb?.progress ?: 0) / 1000f * dur).toLong())
            }
        })

        val gest = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                if (abs(dx) > 100 && abs(vx) > abs(vy)) {
                    if (dx < 0) pc.skipNext() else pc.skipPrev()
                    return true
                }
                return false
            }
        })
        binding.artContainer.setOnTouchListener { _, ev -> gest.onTouchEvent(ev); true }

        viewModel.playerState.observe(viewLifecycleOwner) { state ->
            val song = state.currentSong

            // ── Text: always update ───────────────────────────────────────────
            binding.tvTitle.text  = song?.title  ?: "—"
            binding.tvArtist.text = song?.artist ?: "—"

            // ── Album art: update whenever song ID changes ────────────────────
            // BUG FIX: don't gate art update on pulse running state.
            // Always call updateArt() when the song changes, regardless of
            // whether we're already playing/animating.
            if (song != null && song.id != lastRenderedSongId) {
                lastRenderedSongId = song.id
                updateArt(song.id, song.title)
                // Reset and restart pulse if playing, so it starts fresh for new song
                if (state.isPlaying) {
                    restartPulse()
                }
            }

            // ── Play button icon ──────────────────────────────────────────────
            binding.btnPlay.setImageResource(
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )

            // ── Pulse: manage based on play state ─────────────────────────────
            // Only start/stop pulse — don't let this block art updates
            if (state.isPlaying) {
                if (pulseAnim?.isRunning != true) startPulse()
            } else {
                stopPulse()
            }

            // ── Shuffle / repeat ──────────────────────────────────────────────
            binding.btnShuffle.alpha = if (state.shuffleOn) 1f else 0.3f
            binding.btnRepeat.setImageResource(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one
                else R.drawable.ic_repeat
            )
            binding.btnRepeat.alpha = if (state.repeatMode != Player.REPEAT_MODE_OFF) 1f else 0.3f

            // ── Seek bar ──────────────────────────────────────────────────────
            if (!isSeeking && state.durationMs > 0) {
                binding.seekBar.progress = (state.positionMs * 1000 / state.durationMs).toInt()
            }
            binding.tvPosition.text = fmtMs(state.positionMs)
            binding.tvDuration.text = fmtMs(state.durationMs)
        }
    }

    private fun updateArt(songId: String, title: String) {
        var hash = 0
        for (c in songId) hash = (hash * 31 + c.code) or 0
        val rgb = artPalette[Math.abs(hash) % artPalette.size]
        val bg  = Color.rgb(rgb[0] / 4, rgb[1] / 4, rgb[2] / 4)
        binding.artContainer.setBackgroundColor(bg)
        binding.tvArtLetter.text = title.firstOrNull()?.uppercase() ?: "♪"
        binding.tvArtLetter.setTextColor(Color.argb(80, rgb[0], rgb[1], rgb[2]))
        binding.tvArtNote.visibility = View.GONE
    }

    private fun startPulse() {
        pulseAnim = ValueAnimator.ofFloat(1f, 1.04f, 1f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                val s = a.animatedValue as Float
                binding.artContainer.scaleX = s
                binding.artContainer.scaleY = s
            }
            start()
        }
    }

    private fun restartPulse() {
        pulseAnim?.cancel()
        startPulse()
    }

    private fun stopPulse() {
        pulseAnim?.cancel()
        pulseAnim = null
        binding.artContainer.scaleX = 1f
        binding.artContainer.scaleY = 1f
    }

    private fun fmtMs(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onDestroyView() {
        pulseAnim?.cancel()
        super.onDestroyView()
        _binding = null
    }
}
