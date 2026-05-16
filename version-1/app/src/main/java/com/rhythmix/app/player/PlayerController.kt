package com.rhythmix.app.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.rhythmix.app.model.PlayEvent
import com.rhythmix.app.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class PlayerState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleOn: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF   // OFF / ALL / ONE
)

/**
 * Manages ExoPlayer via MediaController.
 * Exposes LiveData for UI and emits PlayEvent records for analytics.
 */
class PlayerController(private val context: Context) {

    companion object {
        private const val TAG = "PlayerController"
        private const val MIN_LISTEN_MS = 2000L
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _state = MutableLiveData(PlayerState())
    val state: LiveData<PlayerState> = _state

    // Called when a play event is ready to record
    var onPlayEvent: ((PlayEvent) -> Unit)? = null

    // Analytics tracking
    private var trackingStartMs = 0L
    private var accListenedMs = 0L
    private var currentSongForTracking: Song? = null

    private var queue: List<Song> = emptyList()
    private var queueIndex: Int = 0

    // ── Init ─────────────────────────────────────────────────────────────────

    fun connect() {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                setupListeners()
                Log.i(TAG, "MediaController connected")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect: ${e.message}")
            }
        }, MoreExecutors.directExecutor())

        // Progress polling
        scope.launch {
            while (true) {
                delay(500)
                controller?.let { c ->
                    if (c.isPlaying) {
                        _state.postValue(
                            _state.value?.copy(
                                positionMs = c.currentPosition,
                                durationMs = c.duration.coerceAtLeast(1)
                            )
                        )
                    }
                }
            }
        }
    }

    fun disconnect() {
        recordPlayEvent(completed = false)
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }

    private fun setupListeners() {
        controller?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    trackingStartMs = System.currentTimeMillis()
                } else {
                    accListenedMs += System.currentTimeMillis() - trackingStartMs
                }
                _state.postValue(_state.value?.copy(isPlaying = isPlaying))
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val newIndex = controller?.currentMediaItemIndex ?: return
                if (newIndex != queueIndex && queue.isNotEmpty()) {
                    // Song changed via skip/auto-advance
                    recordPlayEvent(completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
                    queueIndex = newIndex
                    currentSongForTracking = queue.getOrNull(queueIndex)
                    startTracking()
                }
                updateStateFromController()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    recordPlayEvent(completed = true)
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                _state.postValue(_state.value?.copy(shuffleOn = shuffleModeEnabled))
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                _state.postValue(_state.value?.copy(repeatMode = repeatMode))
            }
        })
    }

    // ── Playback commands ─────────────────────────────────────────────────────

    fun setQueue(songs: List<Song>, startIndex: Int = 0) {
        queue = songs
        queueIndex = startIndex

        val mediaItems = songs.map { song ->
            MediaItem.Builder()
                .setUri(song.uri)
                .setMediaId(song.id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.album)
                        .build()
                )
                .build()
        }

        controller?.run {
            setMediaItems(mediaItems, startIndex, 0)
            prepare()
            play()
        }

        currentSongForTracking = songs.getOrNull(startIndex)
        startTracking()
        updateStateFromController()
    }

    fun playSongAt(index: Int) {
        recordPlayEvent(completed = false)
        queueIndex = index
        currentSongForTracking = queue.getOrNull(index)
        controller?.seekToDefaultPosition(index)
        controller?.play()
        startTracking()
        updateStateFromController()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun skipNext() {
        recordPlayEvent(completed = false)
        controller?.seekToNextMediaItem()
    }

    fun skipPrev() {
        val pos = controller?.currentPosition ?: 0L
        if (pos > 3000) {
            controller?.seekTo(0)
        } else {
            recordPlayEvent(completed = false)
            controller?.seekToPreviousMediaItem()
        }
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    // ── Analytics tracking ────────────────────────────────────────────────────

    private fun startTracking() {
        trackingStartMs = System.currentTimeMillis()
        accListenedMs = 0
    }

    private fun recordPlayEvent(completed: Boolean) {
        val song = currentSongForTracking ?: return
        if (trackingStartMs == 0L) return

        val listened = accListenedMs + if (controller?.isPlaying == true) {
            System.currentTimeMillis() - trackingStartMs
        } else 0L

        if (listened < MIN_LISTEN_MS) return

        val durationMs = song.durationMs.coerceAtLeast(1)
        val event = PlayEvent(
            songId = song.id,
            startedAt = trackingStartMs,
            listenedMs = listened,
            completed = completed || (listened.toFloat() / durationMs > 0.8f)
        )
        onPlayEvent?.invoke(event)
        trackingStartMs = 0L
        accListenedMs = 0
    }

    private fun updateStateFromController() {
        val c = controller ?: return
        val song = queue.getOrNull(c.currentMediaItemIndex ?: queueIndex)
        _state.postValue(
            _state.value?.copy(
                currentSong = song,
                isPlaying = c.isPlaying,
                positionMs = c.currentPosition,
                durationMs = c.duration.coerceAtLeast(1),
                shuffleOn = c.shuffleModeEnabled,
                repeatMode = c.repeatMode
            )
        )
    }

    fun getCurrentSong(): Song? = queue.getOrNull(queueIndex)
    fun getQueue(): List<Song> = queue
    fun getQueueIndex(): Int = queueIndex
}
