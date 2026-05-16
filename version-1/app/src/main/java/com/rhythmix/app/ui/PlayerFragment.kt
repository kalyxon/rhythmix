package com.rhythmix.app.ui

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

        // Seek bar
        binding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val dur = viewModel.playerState.value?.durationMs ?: 0L
                    val pos = (progress / 1000f * dur).toLong()
                    binding.tvPosition.text = formatMs(pos)
                }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) { isSeeking = true }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                isSeeking = false
                val dur = viewModel.playerState.value?.durationMs ?: 0L
                val pos = ((sb?.progress ?: 0) / 1000f * dur).toLong()
                pc.seekTo(pos)
            }
        })

        // Swipe to skip
        val gestureDetector = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = (e2.x - (e1?.x ?: e2.x))
                if (abs(dx) > 100) {
                    if (dx < 0) pc.skipNext() else pc.skipPrev()
                    return true
                }
                return false
            }
        })
        binding.artContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        // Observe player state
        viewModel.playerState.observe(viewLifecycleOwner) { state ->
            val song = state.currentSong
            binding.tvTitle.text = song?.title ?: "—"
            binding.tvArtist.text = song?.artist ?: "—"
            binding.tvAlbum.text = song?.album ?: "—"

            binding.btnPlay.setImageResource(
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )

            // Shuffle button
            binding.btnShuffle.alpha = if (state.shuffleOn) 1f else 0.4f

            // Repeat button
            binding.btnRepeat.setImageResource(
                when (state.repeatMode) {
                    Player.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
                    else -> R.drawable.ic_repeat
                }
            )
            binding.btnRepeat.alpha = if (state.repeatMode != Player.REPEAT_MODE_OFF) 1f else 0.4f

            // Seek bar (don't update if user is seeking)
            if (!isSeeking && state.durationMs > 0) {
                val progress = (state.positionMs.toFloat() / state.durationMs * 1000).toInt()
                binding.seekBar.progress = progress
                binding.seekBar.max = 1000
            }

            binding.tvPosition.text = formatMs(state.positionMs)
            binding.tvDuration.text = formatMs(state.durationMs)

            // Generative album art background
            song?.let { drawArtBackground(it.id) }
        }
    }

    private fun drawArtBackground(songId: String) {
        // Generate a deterministic color from song ID (same algorithm as web app)
        var hash = 0
        for (c in songId) hash = (hash * 31 + c.code) or 0
        val hue = Math.abs(hash) % 360
        binding.artContainer.setBackgroundColor(
            android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.5f, 0.25f))
        )
        binding.tvArtNote.text = "♪"
    }

    private fun formatMs(ms: Long): String {
        val total = ms / 1000
        val min = total / 60
        val sec = total % 60
        return "%d:%02d".format(min, sec)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
