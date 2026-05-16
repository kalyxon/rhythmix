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
    val queueIndex: Int = 0
)

class PlayerController(private val context: Context) {

    companion object {
        private const val TAG = "PlayerController"
        private const val MIN_LISTEN_MS = 2000L
        // Periodic flush interval — ensures background time is saved regularly
        private const val FLUSH_INTERVAL_MS = 30_000L
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var progressJob: Job? = null
    private var periodicFlushJob: Job? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private val _state = MutableLiveData(PlayerState())
    val state: LiveData<PlayerState> = _state

    var onPlayEvent: ((PlayEvent) -> Unit)? = null

    // ── Analytics state ───────────────────────────────────────────────────────
    // BUG 1 FIX: trackStartMs was reset to 0 in resetTracking(), so when flush()
    // checked "if (trackStartMs > 0)" it sometimes computed 0 elapsed time.
    // Now we use a nullable Long: null = not tracking, non-null = wall-clock start.
    private var sessionStartMs: Long? = null   // wall-clock ms when current play segment started
    private var accumulatedMs: Long = 0L       // ms accumulated from previous pause/resume cycles
    private var trackingSong: Song? = null

    private var songList: List<Song> = emptyList()
    var currentIndex: Int = 0
        private set
    val queue: List<Song> get() = songList

    /**
     * Returns ms listened in the CURRENT in-progress segment that has not yet
     * been flushed as a PlayEvent. This is: accumulated ms from past pause/resume
     * cycles of THIS song + elapsed ms in the current playing segment.
     *
     * IMPORTANT: this value only ever increases. It resets to 0 when flushCurrent()
     * is called (song ends / skips). The ViewModel adds this on top of saved
     * PlayEvents to show a live counter that never goes backwards.
     */
    fun getLiveUnflushedMs(): Long {
        var total = accumulatedMs
        sessionStartMs?.let { start ->
            total += System.currentTimeMillis() - start
        }
        return total.coerceAtLeast(0L)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, token).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                attachListeners()
                startPolling()
                startPeriodicFlush()
                Log.i(TAG, "MediaController connected")
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}")
            }
        }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        flushCurrent(completed = false)
        progressJob?.cancel()
        periodicFlushJob?.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }

    // ── Polling ───────────────────────────────────────────────────────────────

    private fun startPolling() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (true) {
                delay(200)
                val c = controller ?: continue
                val dur = c.duration.coerceAtLeast(0)
                val pos = c.currentPosition.coerceAtLeast(0)
                val cur = _state.value ?: continue
                if (cur.positionMs != pos || cur.durationMs != dur || cur.isPlaying != c.isPlaying) {
                    _state.postValue(cur.copy(positionMs = pos, durationMs = dur, isPlaying = c.isPlaying))
                }
            }
        }
    }

    /**
     * BUG 2 FIX: background listening time.
     * When the app is backgrounded, LiveData observers are paused so
     * onIsPlayingChanged never fires in the UI layer. The player keeps
     * playing (foreground service) but we never accumulate time.
     *
     * Solution: a coroutine running in the PlayerController scope (not
     * bound to any lifecycle) periodically flushes and re-starts tracking.
     * This runs whether or not any fragment/activity is visible.
     */
    private fun startPeriodicFlush() {
        periodicFlushJob?.cancel()
        periodicFlushJob = scope.launch {
            while (true) {
                delay(FLUSH_INTERVAL_MS)
                val c = controller ?: continue
                if (c.isPlaying && trackingSong != null) {
                    // Flush what we have so far and immediately restart tracking
                    // This creates a new PlayEvent every 30s while playing in background
                    val now = System.currentTimeMillis()
                    val start = sessionStartMs ?: now
                    val elapsed = now - start
                    val total = accumulatedMs + elapsed

                    if (total >= MIN_LISTEN_MS) {
                        onPlayEvent?.invoke(PlayEvent(
                            songId     = trackingSong!!.id,
                            startedAt  = start,
                            listenedMs = total,
                            completed  = false
                        ))
                        Log.d(TAG, "Periodic flush: ${trackingSong?.title} ${total}ms")
                    }

                    // Reset accumulator, restart segment from now
                    accumulatedMs = 0L
                    sessionStartMs = now
                }
            }
        }
    }

    // ── Player listeners ──────────────────────────────────────────────────────

    private fun attachListeners() {
        controller?.addListener(object : Player.Listener {

            override fun onIsPlayingChanged(playing: Boolean) {
                if (playing) {
                    // BUG 3 FIX: only start a new segment if we aren't already tracking one.
                    // Previously resetTracking() zeroed trackStartMs but left isTracking=false,
                    // so the next onIsPlayingChanged(true) always overwrote a valid start time.
                    if (sessionStartMs == null) {
                        sessionStartMs = System.currentTimeMillis()
                        Log.d(TAG, "Tracking started: ${trackingSong?.title}")
                    }
                } else {
                    // Paused — accumulate elapsed time from this segment
                    sessionStartMs?.let { start ->
                        accumulatedMs += System.currentTimeMillis() - start
                        sessionStartMs = null
                        Log.d(TAG, "Tracking paused: accumulated=${accumulatedMs}ms")
                    }
                }
                _state.postValue(_state.value?.copy(isPlaying = playing))
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                val c = controller ?: return
                val newIdx = c.currentMediaItemIndex.coerceIn(0, (songList.size - 1).coerceAtLeast(0))
                val resolvedSong = item?.mediaId?.let { id -> songList.firstOrNull { it.id == id } }
                    ?: songList.getOrNull(newIdx)

                // Flush old song's accumulated time before switching
                flushCurrent(completed = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

                currentIndex  = newIdx
                trackingSong  = resolvedSong
                // If already playing (auto-advance), start tracking the new song immediately
                if (c.isPlaying) sessionStartMs = System.currentTimeMillis()

                _state.postValue(_state.value?.copy(
                    currentSong = resolvedSong,
                    queueIndex  = newIdx,
                    positionMs  = 0L,
                    durationMs  = resolvedSong?.durationMs?.coerceAtLeast(0) ?: 0L
                ))
                Log.d(TAG, "Transition → ${resolvedSong?.title} (idx=$newIdx reason=$reason)")
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
        // Don't start sessionStartMs here — wait for onIsPlayingChanged(true) callback
        // so we don't double-count the gap between this call and actual audio starting.
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
        Log.d(TAG, "playQueue idx=$idx → ${songs[idx].title}")
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
            shuffleModeEnabled = false
            setMediaItems(items, idx, 0L)
            prepare()
        }
        _state.postValue(PlayerState(
            currentSong = songs.getOrNull(idx),
            queueIndex  = idx,
            durationMs  = songs.getOrNull(idx)?.durationMs?.coerceAtLeast(0) ?: 0L
        ))
    }

    fun togglePlay() {
        controller?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun skipNext() {
        val c = controller ?: return
        flushCurrent(false)
        if (c.hasNextMediaItem()) { c.seekToNextMediaItem(); c.play() }
        else if (songList.isNotEmpty()) { c.seekToDefaultPosition(0); c.play() }
    }

    fun skipPrev() {
        val c = controller ?: return
        if (c.currentPosition > 3000) { c.seekTo(0) }
        else {
            flushCurrent(false)
            if (c.hasPreviousMediaItem()) { c.seekToPreviousMediaItem(); c.play() }
            else if (songList.isNotEmpty()) { c.seekToDefaultPosition(songList.size - 1); c.play() }
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

    // ── Analytics flush ───────────────────────────────────────────────────────

    /**
     * Compute total listened ms and emit a PlayEvent.
     * Total = accumulated ms from past pause/resume cycles
     *       + current segment (if still playing)
     *
     * BUG 4 FIX: old flush() used trackStartMs which was sometimes 0 after
     * resetTracking(), causing (System.currentTimeMillis() - 0) = epoch-size
     * number, inflating listening time to billions of ms.
     * Now we only add elapsed if sessionStartMs is non-null (actively playing).
     */
    private fun flushCurrent(completed: Boolean) {
        val song = trackingSong ?: return

        var total = accumulatedMs
        // Add current in-progress segment if still playing
        sessionStartMs?.let { start ->
            total += System.currentTimeMillis() - start
        }

        // Reset tracking state immediately (before the callback, to avoid double-flush)
        accumulatedMs  = 0L
        sessionStartMs = null

        if (total < MIN_LISTEN_MS) {
            Log.d(TAG, "Flush skipped: ${total}ms < min")
            return
        }

        val dur = song.durationMs.coerceAtLeast(1)
        val event = PlayEvent(
            songId     = song.id,
            startedAt  = System.currentTimeMillis() - total,
            listenedMs = total,
            completed  = completed || (total.toFloat() / dur >= 0.8f)
        )
        onPlayEvent?.invoke(event)
        Log.d(TAG, "Flushed: ${song.title} ${total}ms completed=${event.completed}")
    }
}
