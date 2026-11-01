package com.rhythmix.app.ui

import android.animation.ValueAnimator
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import androidx.appcompat.app.AlertDialog
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
    private var artworkRotation: ValueAnimator? = null
    private var lastRenderedSongId: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlayerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        UiMotion.enter(binding.root)

        val pc = viewModel.playerController

        binding.btnBack.setOnClickListener { viewModel.navigate(AppScreen.Home) }
        binding.btnPlay.setOnClickListener { pc.togglePlay() }
        binding.btnNext.setOnClickListener { pc.skipNext() }
        binding.btnPrev.setOnClickListener { pc.skipPrev() }
        binding.btnShuffle.setOnClickListener { pc.toggleShuffle() }
        binding.btnRepeat.setOnClickListener { pc.cycleRepeat() }
        binding.btnQueue.setOnClickListener { viewModel.navigate(AppScreen.Library) }
        binding.artContainer.setOnClickListener {
            viewModel.playerState.value?.currentSong?.let { song ->
                (activity as? MainActivity)?.chooseArtwork(song)
            }
        }
        binding.artContainer.contentDescription = "Change song artwork"

        // Favorites Heart Button
        binding.btnLike.setOnClickListener {
            val song = viewModel.playerState.value?.currentSong ?: return@setOnClickListener
            viewModel.toggleFavorite(song.id)
            updateFavoriteUI(song.id)
        }

        // Sleep Timer Dialog
        binding.btnSleep.setOnClickListener {
            val options = arrayOf("Off", "15 minutes", "30 minutes", "45 minutes", "60 minutes")
            val minutes = intArrayOf(0, 15, 30, 45, 60)
            AlertDialog.Builder(requireContext())
                .setTitle("Sleep Timer")
                .setItems(options) { _, which ->
                    pc.setSleepTimer(minutes[which])
                }
                .show()
        }

        binding.progressOrbit.onSeekStarted = { isSeeking = true }
        binding.progressOrbit.onSeekProgressChanged = { progress ->
            val duration = viewModel.playerState.value?.durationMs ?: 0L
            val position = (progress * duration).toLong()
            binding.progressOrbit.positionLabel = fmtMs(position)
            binding.progressOrbit.remainingLabel = "-${fmtMs((duration - position).coerceAtLeast(0L))}"
        }
        binding.progressOrbit.onSeekFinished = { progress ->
            isSeeking = false
            val duration = viewModel.playerState.value?.durationMs ?: 0L
            pc.seekTo((progress * duration).toLong())
        }
        binding.progressOrbit.onSeekCancelled = { isSeeking = false }

        val gest = GestureDetector(requireContext(), object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                binding.artContainer.performClick()
                return true
            }

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

            binding.tvTitle.text = song?.title ?: "—"
            binding.tvArtist.text = song?.artist ?: "—"
            binding.tvAlbum.text = song?.album.orEmpty()

            if (song != null) {
                updateFavoriteUI(song.id)
                if (song.id != lastRenderedSongId) {
                    lastRenderedSongId = song.id
                    updateArt(song)
                }
            }

            binding.btnPlay.setImageResource(
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )

            // Equalizer animation
            binding.equalizerView.isPlaying = state.isPlaying

            if (state.isPlaying) {
                if (artworkRotation?.isRunning != true) startArtworkRotation()
            } else {
                stopArtworkRotation()
            }

            binding.btnShuffle.alpha = if (state.shuffleOn) 1f else 0.3f
            binding.btnRepeat.setImageResource(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one
                else R.drawable.ic_repeat
            )
            binding.btnRepeat.alpha = if (state.repeatMode != Player.REPEAT_MODE_OFF) 1f else 0.3f

            if (!isSeeking) {
                binding.progressOrbit.progress =
                    if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
                binding.progressOrbit.positionLabel = fmtMs(state.positionMs)
                binding.progressOrbit.remainingLabel =
                    "-${fmtMs((state.durationMs - state.positionMs).coerceAtLeast(0L))}"
            }
        }

        viewModel.analytics.observe(viewLifecycleOwner) {
            viewModel.playerState.value?.currentSong?.let { song ->
                updateFavoriteUI(song.id)
            }
        }

        viewModel.artworkUpdated.observe(viewLifecycleOwner) { songId ->
            val song = viewModel.playerState.value?.currentSong
                ?.takeIf { it.id == songId } ?: return@observe
            updateArt(song)
        }
    }

    private fun updateFavoriteUI(songId: String) {
        val isFav = viewModel.isFavorite(songId)
        if (isFav) {
            binding.btnLike.setColorFilter(requireContext().getColor(R.color.heart_active))
            binding.btnLike.alpha = 1.0f
        } else {
            binding.btnLike.setColorFilter(requireContext().getColor(R.color.text_tertiary))
            binding.btnLike.alpha = 0.6f
        }
    }

    private fun updateArt(song: com.rhythmix.app.model.Song) {
        ArtworkLoader.load(song, binding.ivPlayerArt)
    }

    private fun startArtworkRotation() {
        if (UiMotion.isReduced(requireContext())) return
        val artwork = binding.ivPlayerArt
        artworkRotation = ValueAnimator.ofFloat(artwork.rotation, artwork.rotation + 360f).apply {
            duration = 24_000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { artwork.rotation = it.animatedValue as Float }
            start()
        }
    }

    private fun stopArtworkRotation() {
        artworkRotation?.cancel()
        artworkRotation = null
    }

    private fun fmtMs(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onDestroyView() {
        artworkRotation?.cancel()
        lastRenderedSongId = null
        super.onDestroyView()
        _binding = null
    }
}
