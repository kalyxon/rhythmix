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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class AppScreen {
    object Onboarding : AppScreen()
    object Scanning : AppScreen()
    object Home : AppScreen()
    object Library : AppScreen()
    object Analytics : AppScreen()
    object Player : AppScreen()
    object Settings : AppScreen()
}

data class ScanProgress(val scanned: Int, val total: Int)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object { private const val TAG = "MainViewModel" }

    val storage = StorageManager(app)
    val playerController = PlayerController(app)

    // ── UI State ──────────────────────────────────────────────────────────────
    private val _screen = MutableLiveData<AppScreen>(AppScreen.Onboarding)
    val screen: LiveData<AppScreen> = _screen

    private val _songs = MutableLiveData<List<Song>>(emptyList())
    val songs: LiveData<List<Song>> = _songs

    private val _analytics = MutableLiveData(AnalyticsData())
    val analytics: LiveData<AnalyticsData> = _analytics

    private val _scanProgress = MutableLiveData<ScanProgress?>()
    val scanProgress: LiveData<ScanProgress?> = _scanProgress

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    private val _sortMode = MutableLiveData("title")
    val sortMode: LiveData<String> = _sortMode

    val playerState: LiveData<PlayerState> = playerController.state

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        playerController.connect()
        playerController.onPlayEvent = { event -> onPlayEvent(event) }

        // Check if we have a saved folder from previous install
        if (storage.hasSavedFolder() && storage.isFolderPermissionValid()) {
            viewModelScope.launch {
                val folderUri = storage.getSavedFolderUri()!!
                loadAnalyticsFromFolder(folderUri)
                scanFolderInternal(folderUri, showOnboarding = false)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playerController.disconnect()
    }

    // ── Folder selection ──────────────────────────────────────────────────────

    /** Called from Activity after user picks a folder via SAF picker */
    fun onFolderSelected(uri: Uri) {
        storage.saveFolderUri(uri)
        viewModelScope.launch {
            loadAnalyticsFromFolder(uri)
            scanFolderInternal(uri, showOnboarding = true)
        }
    }

    private suspend fun scanFolderInternal(folderUri: Uri, showOnboarding: Boolean) {
        _screen.postValue(AppScreen.Scanning)

        val scannedSongs = storage.scanSongs(folderUri) { done, total ->
            _scanProgress.postValue(ScanProgress(done, total))
        }

        _scanProgress.postValue(null)

        if (scannedSongs.isEmpty()) {
            _toastMessage.postValue("No audio files found in this folder.")
            if (showOnboarding) _screen.postValue(AppScreen.Onboarding)
            return
        }

        _songs.postValue(scannedSongs)
        playerController.setQueue(scannedSongs, 0)
        _screen.postValue(AppScreen.Home)
        Log.i(TAG, "Scanned ${scannedSongs.size} songs")
    }

    // ── Analytics ─────────────────────────────────────────────────────────────

    private suspend fun loadAnalyticsFromFolder(folderUri: Uri) {
        val data = storage.loadAnalytics(folderUri)
        if (data != null) {
            _analytics.postValue(data)
            Log.i(TAG, "Loaded ${data.playEvents.size} play events from disk")
        }
    }

    private fun onPlayEvent(event: PlayEvent) {
        val current = _analytics.value ?: AnalyticsData()
        val updated = current.copy(
            playEvents = current.playEvents + event,
            lastSyncAt = System.currentTimeMillis()
        )
        _analytics.postValue(updated)

        // Persist to disk
        viewModelScope.launch {
            val folderUri = storage.getSavedFolderUri() ?: return@launch
            storage.saveAnalytics(folderUri, updated)
        }
    }

    fun clearAnalytics() {
        val cleared = AnalyticsData()
        _analytics.postValue(cleared)
        viewModelScope.launch {
            val folderUri = storage.getSavedFolderUri() ?: return@launch
            storage.saveAnalytics(folderUri, cleared)
        }
        _toastMessage.postValue("Listening history cleared.")
    }

    // ── Song stats computation ────────────────────────────────────────────────

    fun computeSongStats(limit: Int = 10): List<SongStats> {
        val events = _analytics.value?.playEvents ?: return emptyList()
        val songMap = _songs.value?.associateBy { it.id } ?: return emptyList()

        return events
            .groupBy { it.songId }
            .mapNotNull { (id, evts) ->
                val song = songMap[id] ?: return@mapNotNull null
                SongStats(
                    songId = id,
                    title = song.title,
                    artist = song.artist,
                    playCount = evts.size,
                    completedCount = evts.count { it.completed },
                    totalListenedMs = evts.sumOf { it.listenedMs }
                )
            }
            .sortedByDescending { it.playCount }
            .take(limit)
    }

    fun getTotalListeningTimeMs(): Long =
        _analytics.value?.playEvents?.sumOf { it.listenedMs } ?: 0L

    fun getTodayListeningTimeMs(): Long {
        val startOfDay = System.currentTimeMillis().let {
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.timeInMillis
        }
        return _analytics.value?.playEvents
            ?.filter { it.startedAt >= startOfDay }
            ?.sumOf { it.listenedMs } ?: 0L
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    fun navigate(screen: AppScreen) { _screen.postValue(screen) }

    fun setSortMode(mode: String) { _sortMode.postValue(mode) }

    fun rescan() {
        val uri = storage.getSavedFolderUri() ?: return
        viewModelScope.launch { scanFolderInternal(uri, showOnboarding = false) }
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
