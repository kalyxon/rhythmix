package com.rhythmix.app.player

import android.content.ComponentName
import android.content.Context
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class PlayerState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleOn: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queueIndex: Int = 0,
    val sleepTimerMinutes: Int = 0,
    val sleepTimerRemainingSec: Int = 0
)

class PlayerController(private val context: Context) {

    companion object {
        private const val TAG = "PlayerController"
        private const val MIN_LISTEN_MS = 2000L
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var progressJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _state = MutableLiveData(PlayerState())
    val state: LiveData<PlayerState> = _state

    var onPlayEvent: ((PlayEvent) -> Unit)? = null

    private var sessionStartMs: Long? = null
    private var accumulatedMs: Long = 0L
    private var trackingSong: Song? = null

    private var songList: List<Song> = emptyList()
    var currentIndex: Int = 0
        private set
    val queue: List<Song> get() = songList

    fun getLiveUnflushedMs(): Long {
        var total = accumulatedMs
        sessionStartMs?.let { start ->
            total += (System.currentTimeMillis() - start)
        }
        return total.coerceAtLeast(0L)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, token).buildAsync()
        controllerFuture?.addListener({
            try {
                val c = controllerFuture?.get() ?: return@addListener
                controller = c
                attachListeners()

                _state.postValue(_state.value?.copy(
                    isPlaying = c.isPlaying,
                    shuffleOn = c.shuffleModeEnabled,
                    repeatMode = c.repeatMode
                ))

                startPolling()
                Log.i(TAG, "MediaController connected")
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}")
            }
        }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        flushCurrent(completed = false)
        progressJob?.cancel()
        sleepTimerJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startPolling() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                delay(250)
                val c = controller ?: continue
                val cur = _state.value ?: continue

                val pos = c.currentPosition.coerceAtLeast(0)
                val dur = c.duration.coerceAtLeast(0)

                val actualMediaId = c.currentMediaItem?.mediaId
                val isSongMismatch = actualMediaId != null && actualMediaId != cur.currentSong?.id

                if (cur.positionMs != pos || cur.isPlaying != c.isPlaying || isSongMismatch) {

                    val updatedSong = if (isSongMismatch) {
                        songList.find { it.id == actualMediaId } ?: cur.currentSong
                    } else {
                        cur.currentSong
                    }

                    _state.postValue(cur.copy(
                        currentSong = updatedSong,
                        positionMs = pos,
                        durationMs = if (dur > 0) dur else cur.durationMs,
                        isPlaying = c.isPlaying
                    ))
                }
            }
        }
    }

    // ── Sleep Timer ───────────────────────────────────────────────────────────

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _state.postValue(_state.value?.copy(sleepTimerMinutes = 0, sleepTimerRemainingSec = 0))
            return
        }

        var remaining = minutes * 60
        _state.postValue(_state.value?.copy(sleepTimerMinutes = minutes, sleepTimerRemainingSec = remaining))

        sleepTimerJob = scope.launch {
            while (remaining > 0) {
                delay(1000)
                remaining--
                _state.postValue(_state.value?.copy(sleepTimerRemainingSec = remaining))
            }
            // Timer expired — pause playback
            controller?.pause()
            _state.postValue(_state.value?.copy(sleepTimerMinutes = 0, sleepTimerRemainingSec = 0))
        }
    }

    // ── Player listeners ──────────────────────────────────────────────────────

    private fun attachListeners() {
        controller?.addListener(object : Player.Listener {

            override fun onIsPlayingChanged(playing: Boolean) {
                if (playing) {
                    if (sessionStartMs == null) {
                        sessionStartMs = System.currentTimeMillis()
                    }
                } else {
                    sessionStartMs?.let { start ->
                        accumulatedMs += System.currentTimeMillis() - start
                        sessionStartMs = null
                    }
                }
                _state.postValue(_state.value?.copy(isPlaying = playing))
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val c = controller ?: return

                val resolvedSong = songList.find { it.id == item?.mediaId }
                    ?: songList.getOrNull(c.currentMediaItemIndex)

                flushCurrent(completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

                currentIndex = c.currentMediaItemIndex
                trackingSong = resolvedSong

                accumulatedMs = 0L
                sessionStartMs = if (c.isPlaying) System.currentTimeMillis() else null

                _state.postValue(_state.value?.copy(
                    currentSong = resolvedSong,
                    queueIndex  = currentIndex,
                    positionMs  = 0L,
                    isPlaying   = c.isPlaying,
                    durationMs  = resolvedSong?.durationMs ?: 0L
                ))
            }

            override fun onShuffleModeEnabledChanged(s: Boolean) {
                _state.postValue(_state.value?.copy(shuffleOn = s))
            }

            override fun onRepeatModeChanged(r: Int) {
                _state.postValue(_state.value?.copy(repeatMode = r))
            }

            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_ENDED) flushCurrent(completed = true)
            }
        })
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    fun playQueue(songs: List<Song>, startIndex: Int) {
        if (songs.isEmpty()) return
        val idx = startIndex.coerceIn(0, songs.size - 1)

        flushCurrent(completed = false)
        songList     = songs
        currentIndex = idx
        trackingSong = songs[idx]
        accumulatedMs = 0L
        sessionStartMs = null

        val items = songs.map { s ->
            MediaItem.Builder()
                .setUri(s.uri)
                .setMediaId(s.id)
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(s.title).setArtist(s.artist).build())
                .build()
        }
        controller?.run {
            shuffleModeEnabled = false
            setMediaItems(items, idx, 0L)
            prepare()
            play()
        }
        _state.postValue(PlayerState(
            currentSong = songs[idx],
            isPlaying   = true,
            positionMs  = 0L,
            durationMs  = songs[idx].durationMs.coerceAtLeast(0),
            queueIndex  = idx,
            shuffleOn   = false
        ))
    }

    fun setQueue(songs: List<Song>, startIndex: Int = 0) {
        if (songs.isEmpty()) return
        val idx = startIndex.coerceIn(0, songs.size - 1)
        songList      = songs
        currentIndex  = idx
        trackingSong  = songs.getOrNull(idx)
        accumulatedMs = 0L
        sessionStartMs = null

        val items = songs.map { s ->
            MediaItem.Builder()
                .setUri(s.uri)
                .setMediaId(s.id)
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(s.title).setArtist(s.artist).build())
                .build()
        }
        controller?.run {
            setMediaItems(items, idx, 0L)
            prepare()
        }
    }

    fun updateSongDetails(song: Song) {
        songList = songList.map { if (it.id == song.id) song else it }
        val index = songList.indexOfFirst { it.id == song.id }
        if (index >= 0) {
            val mediaItem = MediaItem.Builder()
                .setUri(song.uri)
                .setMediaId(song.id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).build())
                .build()
            controller?.let { c ->
                if (index < c.mediaItemCount && c.getMediaItemAt(index).mediaId == song.id) {
                    c.replaceMediaItem(index, mediaItem)
                }
            }
        }
        val current = _state.value ?: return
        if (current.currentSong?.id == song.id) {
            _state.postValue(current.copy(currentSong = song))
        }
    }

    fun removeSongFromQueue(songId: String) {
        val removedIndex = songList.indexOfFirst { it.id == songId }
        if (removedIndex < 0) return

        val previousState = _state.value ?: PlayerState()
        val wasCurrent = previousState.currentSong?.id == songId
        if (wasCurrent) flushCurrent(completed = false)
        songList = songList.filterNot { it.id == songId }

        if (songList.isEmpty()) {
            controller?.clearMediaItems()
            currentIndex = 0
            trackingSong = null
            _state.postValue(PlayerState())
            return
        }

        if (removedIndex < currentIndex) currentIndex--
        if (wasCurrent) currentIndex = removedIndex.coerceAtMost(songList.lastIndex)
        controller?.let { c ->
            if (removedIndex < c.mediaItemCount && c.getMediaItemAt(removedIndex).mediaId == songId) {
                c.removeMediaItem(removedIndex)
            }
        }

        if (wasCurrent) {
            val nextSong = songList[currentIndex]
            trackingSong = nextSong
            accumulatedMs = 0L
            sessionStartMs = if (controller?.isPlaying == true) System.currentTimeMillis() else null
            _state.postValue(previousState.copy(
                currentSong = nextSong,
                queueIndex = currentIndex,
                positionMs = 0L,
                durationMs = nextSong.durationMs,
                isPlaying = controller?.isPlaying ?: false
            ))
        } else {
            _state.postValue(previousState.copy(queueIndex = currentIndex))
        }
    }

    fun togglePlay() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun skipNext() {
        val c = controller ?: return
        flushCurrent(false)
        if (c.hasNextMediaItem()) { c.seekToNextMediaItem() }
    }

    fun skipPrev() {
        val c = controller ?: return
        if (c.currentPosition > 3000) {
            c.seekTo(0)
        } else {
            flushCurrent(false)
            if (c.hasPreviousMediaItem()) { c.seekToPreviousMediaItem() }
        }
    }

    fun seekTo(ms: Long) { controller?.seekTo(ms) }

    fun toggleShuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }

    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else                   -> Player.REPEAT_MODE_OFF
            }
        }
    }

    private fun flushCurrent(completed: Boolean) {
        val song = trackingSong ?: return
        val now = System.currentTimeMillis()

        var total = accumulatedMs
        sessionStartMs?.let { start ->
            total += (now - start)
        }

        accumulatedMs  = 0L
        sessionStartMs = null

        if (total < MIN_LISTEN_MS) return

        val dur = song.durationMs.coerceAtLeast(1)
        val event = PlayEvent(
            songId     = song.id,
            startedAt  = now - total,
            listenedMs = total,
            completed  = completed || (total.toFloat() / dur >= 0.8f)
        )
        onPlayEvent?.invoke(event)
    }
}
