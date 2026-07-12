package com.rhythmix.app.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.rhythmix.app.model.AnalyticsData
import com.rhythmix.app.model.PlayEvent
import com.rhythmix.app.model.Song
import com.rhythmix.app.model.SongStats
import com.rhythmix.app.player.PlayerController
import com.rhythmix.app.player.PlayerState
import com.rhythmix.app.storage.StorageManager
import kotlinx.coroutines.launch

sealed class AppScreen {
    object Onboarding : AppScreen()
    object Scanning   : AppScreen()
    object Home       : AppScreen()
    object Library    : AppScreen()
    object Analytics  : AppScreen()
    object Player     : AppScreen()
    object Settings   : AppScreen()
}

data class ScanProgress(val scanned: Int, val total: Int)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object { private const val TAG = "MainViewModel" }

    val storage = StorageManager(app)
    val playerController = PlayerController(app)

    private val _screen       = MutableLiveData<AppScreen>(AppScreen.Onboarding)
    val screen: LiveData<AppScreen> = _screen

    private val _songs        = MutableLiveData<List<Song>>(emptyList())
    val songs: LiveData<List<Song>> = _songs

    private val _analytics    = MutableLiveData(AnalyticsData())
    val analytics: LiveData<AnalyticsData> = _analytics

    private val _scanProgress = MutableLiveData<ScanProgress?>()
    val scanProgress: LiveData<ScanProgress?> = _scanProgress

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    private val _sortMode     = MutableLiveData("title")
    val sortMode: LiveData<String> = _sortMode

    val playerState: LiveData<PlayerState> = playerController.state

    init {
        playerController.connect()
        playerController.onPlayEvent = { event -> onPlayEvent(event) }

        if (storage.hasSavedFolder() && storage.isFolderPermissionValid()) {
            viewModelScope.launch {
                val uri = storage.getSavedFolderUri()!!
                // Load analytics first (fast)
                loadAnalyticsFromFolder(uri)

                // Try song cache first — avoids rescanning every launch
                val cached: List<Song> = storage.loadSongsFromCache().orEmpty()
                if (cached.isNotEmpty()) {
                    Log.i(TAG, "Restored ${cached.size} songs from cache")
                    _songs.postValue(cached)
                    playerController.setQueue(cached, 0)
                    _screen.postValue(AppScreen.Home)
                } else {
                    // No cache — full scan
                    scanFolderInternal(uri)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playerController.disconnect()
    }

    fun onFolderSelected(uri: Uri) {
        storage.saveFolderUri(uri)
        viewModelScope.launch {
            loadAnalyticsFromFolder(uri)
            scanFolderInternal(uri)
        }
    }

    private suspend fun scanFolderInternal(folderUri: Uri) {
        _screen.postValue(AppScreen.Scanning)
        val scanned = storage.scanSongs(folderUri) { done, total ->
            _scanProgress.postValue(ScanProgress(done, total))
        }
        _scanProgress.postValue(null)
        if (scanned.isEmpty()) {
            _toastMessage.postValue("No audio files found in this folder.")
            _screen.postValue(AppScreen.Onboarding)
            return
        }
        storage.saveSongsToCache(scanned)  // cache so next launch is instant
        _songs.postValue(scanned)
        playerController.setQueue(scanned, 0)
        _screen.postValue(AppScreen.Home)
        Log.i(TAG, "Scanned ${scanned.size} songs")
    }

    private suspend fun loadAnalyticsFromFolder(folderUri: Uri) {
        val data = storage.loadAnalytics(folderUri) ?: return
        _analytics.postValue(data)
        Log.i(TAG, "Loaded ${data.playEvents.size} play events")
    }

    private fun onPlayEvent(event: PlayEvent) {
        val current = _analytics.value ?: AnalyticsData()
        val updated = current.copy(
            playEvents = current.playEvents + event,
            lastSyncAt = System.currentTimeMillis()
        )
        _analytics.postValue(updated)
        viewModelScope.launch {
            storage.getSavedFolderUri()?.let { storage.saveAnalytics(it, updated) }
        }
    }

    fun clearAnalytics() {
        val cleared = AnalyticsData()
        _analytics.postValue(cleared)
        viewModelScope.launch {
            storage.getSavedFolderUri()?.let { storage.saveAnalytics(it, cleared) }
        }
        _toastMessage.postValue("Listening history cleared.")
    }

    // ── Stats helpers (called by fragments, reactive via analytics LiveData) ──

    fun computeSongStats(limit: Int = 10): List<SongStats> {
        val events  = _analytics.value?.playEvents ?: return emptyList()
        val songMap = _songs.value?.associateBy { it.id } ?: return emptyList()
        return events.groupBy { it.songId }
            .mapNotNull { (id, evts) ->
                val song = songMap[id] ?: return@mapNotNull null
                SongStats(id, song.title, song.artist,
                    playCount        = evts.size,
                    completedCount   = evts.count { it.completed },
                    totalListenedMs  = evts.sumOf { it.listenedMs })
            }
            .sortedByDescending { it.playCount }
            .take(limit)
    }

    /**
     * Returns total listening time across ALL saved play events.
     * BUG FIX: also adds the currently-playing in-progress segment
     * (the portion not yet flushed as a PlayEvent) so the counter
     * updates live while a song is playing, not just after it ends.
     */
    fun getTotalListeningTimeMs(): Long {
        val saved = _analytics.value?.playEvents?.sumOf { it.listenedMs } ?: 0L
        val live  = liveListeningMs()
        return saved + live
    }

    fun getTodayListeningTimeMs(): Long {
        val cal = java.util.Calendar.getInstance().also {
            it.set(java.util.Calendar.HOUR_OF_DAY, 0)
            it.set(java.util.Calendar.MINUTE, 0)
            it.set(java.util.Calendar.SECOND, 0)
            it.set(java.util.Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis
        val saved = _analytics.value?.playEvents
            ?.filter { it.startedAt >= startOfDay }
            ?.sumOf { it.listenedMs } ?: 0L
        // Add live segment — it was started today if currently playing
        val live = liveListeningMs()
        return saved + live
    }

    /**
     * Returns ms listened in the current unflushed segment — directly from
     * PlayerController's internal accumulatedMs + active session elapsed.
     *
     * WHY NOT positionMs: positionMs resets to 0 on every skip/next/prev, which
     * caused the displayed total to DECREASE when skipping. accumulatedMs +
     * sessionStartMs never decreases — it only resets when flushCurrent() emits
     * a PlayEvent (at which point that ms is added to saved playEvents instead).
     */
    private fun liveListeningMs(): Long = playerController.getLiveUnflushedMs()

    fun getWeeklyListeningMs(): LongArray {
        // Returns array[7] — index 0 = today, 1 = yesterday, ...
        val result = LongArray(7)
        val cal = java.util.Calendar.getInstance()
        repeat(7) { dayOffset ->
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            val end = cal.timeInMillis
            result[dayOffset] = _analytics.value?.playEvents
                ?.filter { it.startedAt in start until end }
                ?.sumOf { it.listenedMs } ?: 0L
            cal.add(java.util.Calendar.DAY_OF_YEAR, -2) // go back 2 to step back 1 net
        }
        return result
    }

    fun navigate(screen: AppScreen) { _screen.postValue(screen) }
    fun setSortMode(mode: String)   { _sortMode.postValue(mode) }
    fun rescan() {
        storage.clearSongCache()
        val uri = storage.getSavedFolderUri() ?: return
        viewModelScope.launch { scanFolderInternal(uri) }
    }

    fun getSortedSongs(): List<Song> {
        val list = _songs.value ?: return emptyList()
        return when (_sortMode.value) {
            "artist" -> list.sortedBy { it.artist.lowercase() }
            "album"  -> list.sortedBy { it.album.lowercase() }
            "recent" -> list.sortedByDescending { it.addedAt }
            else     -> list.sortedBy { it.title.lowercase() }
        }
    }

    fun toastShown() { _toastMessage.postValue(null) }
}
