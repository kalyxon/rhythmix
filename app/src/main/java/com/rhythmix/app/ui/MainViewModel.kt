package com.rhythmix.app.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.rhythmix.app.model.Album
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
    object Scanning   : AppScreen()
    object Home       : AppScreen()
    object Library    : AppScreen()
    object Albums     : AppScreen()
    data class AlbumDetail(val albumId: String) : AppScreen()
    data class SongDetail(val songId: String) : AppScreen()
    object Analytics  : AppScreen()
    object Player     : AppScreen()
    object Settings   : AppScreen()
}

data class ScanProgress(val scanned: Int, val total: Int)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val PREF_THEME = "current_theme_id"
        private const val PREF_REDUCE_MOTION = "reduce_motion"
    }

    val storage = StorageManager(app)
    val playerController = PlayerController(app)
    private val prefs = app.getSharedPreferences("rhythmix_prefs", Context.MODE_PRIVATE)

    private val _screen = MutableLiveData<AppScreen>(AppScreen.Onboarding)
    val screen: LiveData<AppScreen> = _screen

    private val _songs = MutableLiveData<List<Song>>(emptyList())
    val songs: LiveData<List<Song>> = _songs

    private val _analytics = MutableLiveData(AnalyticsData())
    val analytics: LiveData<AnalyticsData> = _analytics

    private val _themeId = MutableLiveData(prefs.getString(PREF_THEME, "emerald") ?: "emerald")
    val themeId: LiveData<String> = _themeId

    private val _reduceMotion = MutableLiveData(prefs.getBoolean(PREF_REDUCE_MOTION, false))
    val reduceMotion: LiveData<Boolean> = _reduceMotion

    private val _scanProgress = MutableLiveData<ScanProgress?>()
    val scanProgress: LiveData<ScanProgress?> = _scanProgress

    private val _toastMessage = MutableLiveData<String?>()
    val toastMessage: LiveData<String?> = _toastMessage

    private val _artworkUpdated = MutableLiveData<String?>()
    val artworkUpdated: LiveData<String?> = _artworkUpdated

    private val _sortMode = MutableLiveData("title")
    val sortMode: LiveData<String> = _sortMode

    val playerState: LiveData<PlayerState> = playerController.state

    init {
        playerController.connect()
        playerController.onPlayEvent = { event -> onPlayEvent(event) }

        if (storage.hasSavedFolder() && storage.isFolderPermissionValid()) {
            viewModelScope.launch {
                val uri = storage.getSavedFolderUri()!!
                loadAnalyticsFromFolder(uri)

                val cached = withContext(Dispatchers.IO) {
                    storage.loadSongsFromCache()
                }
                if (cached != null) {
                    Log.i(TAG, "Restored ${cached.size} songs from cache")
                    _songs.postValue(cached)
                    playerController.setQueue(cached, 0)
                    _screen.postValue(AppScreen.Home)
                } else {
                    scanFolderInternal(uri)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        playerController.disconnect()
    }

    fun setTheme(theme: String) {
        prefs.edit().putString(PREF_THEME, theme).apply()
        _themeId.postValue(theme)
    }

    fun setReduceMotion(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_REDUCE_MOTION, enabled).apply()
        _reduceMotion.value = enabled
    }

    fun onFolderSelected(uri: Uri) {
        storage.saveFolderUri(uri)
        viewModelScope.launch {
            loadAnalyticsFromFolder(uri)
            scanFolderInternal(uri)
        }
    }

    fun saveSongArtwork(songId: String, imageUri: Uri) {
        viewModelScope.launch {
            try {
                storage.saveSongArtwork(songId, imageUri)
                ArtworkLoader.clear(songId)
                _artworkUpdated.value = songId
                _toastMessage.value = "Artwork saved offline in your music folder."
            } catch (error: Exception) {
                Log.e(TAG, "Unable to save artwork for song $songId", error)
                _toastMessage.value = error.message ?: "Unable to save that image."
            }
        }
    }

    fun restoreSongArtwork(songId: String) {
        viewModelScope.launch {
            try {
                if (storage.restoreSongArtwork(songId)) {
                    ArtworkLoader.clear(songId)
                    _artworkUpdated.value = songId
                    _toastMessage.value = "Original artwork restored."
                } else {
                    _toastMessage.value = "This song has no custom artwork to restore."
                }
            } catch (error: Exception) {
                Log.e(TAG, "Unable to restore artwork for song $songId", error)
                _toastMessage.value = error.message ?: "Unable to restore original artwork."
            }
        }
    }

    private suspend fun scanFolderInternal(folderUri: Uri) {
        _screen.postValue(AppScreen.Scanning)
        val discoveredSongs = storage.scanSongs(folderUri) { done, total ->
            _scanProgress.postValue(ScanProgress(done, total))
        }
        _scanProgress.postValue(null)
        if (discoveredSongs.isEmpty()) {
            _toastMessage.postValue("No audio files found in this folder.")
            _screen.postValue(AppScreen.Onboarding)
            return
        }
        val scanned = storage.filterRemovedSongs(discoveredSongs)
        storage.saveSongsToCache(scanned)
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

    // ── Favorites ─────────────────────────────────────────────────────────────

    fun isFavorite(songId: String): Boolean {
        return _analytics.value?.favoriteSongIds?.contains(songId) == true
    }

    fun toggleFavorite(songId: String) {
        val current = _analytics.value ?: AnalyticsData()
        val list = current.favoriteSongIds.toMutableList()
        val isFavNow = if (list.contains(songId)) {
            list.remove(songId)
            false
        } else {
            list.add(songId)
            true
        }
        val updated = current.copy(favoriteSongIds = list)
        _analytics.postValue(updated)
        viewModelScope.launch {
            storage.getSavedFolderUri()?.let { storage.saveAnalytics(it, updated) }
        }
        _toastMessage.postValue(if (isFavNow) "Added to Favorites ♥" else "Removed from Favorites")
    }

    // ── Album Management ──────────────────────────────────────────────────────

    fun createAlbum(
        title: String,
        description: String,
        songIds: List<String>,
        collageStyle: String = "grid2x2",
        coverUri: String? = null
    ) {
        if (title.isBlank()) {
            _toastMessage.postValue("Please enter an album title.")
            return
        }
        if (songIds.isEmpty()) {
            _toastMessage.postValue("Please select at least 1 song for the album.")
            return
        }

        val newAlbum = Album(
            id = "album_" + System.currentTimeMillis(),
            title = title.trim(),
            description = description.trim(),
            category = "Custom",
            songIds = songIds,
            collageStyle = collageStyle,
            createdAt = System.currentTimeMillis(),
            coverUri = coverUri
        )

        val current = _analytics.value ?: AnalyticsData()
        val updated = current.copy(albums = current.albums + newAlbum)
        _analytics.postValue(updated)
        viewModelScope.launch {
            storage.getSavedFolderUri()?.let { storage.saveAnalytics(it, updated) }
        }
        _toastMessage.postValue("Album '$title' created!")
    }

    fun deleteAlbum(albumId: String) {
        val current = _analytics.value ?: AnalyticsData()
        if (albumId in setOf("smart_favs", "smart_all", "smart_recent", "smart_top")) {
            _toastMessage.postValue("This built-in mix can't be deleted.")
            return
        }
        val isSmartMix = albumId.startsWith("smart_artist_")
        if (!isSmartMix && current.albums.none { it.id == albumId && it.category == "Custom" }) return
        val updated = if (isSmartMix) {
            current.copy(
                albums = current.albums.filterNot { it.id == albumId },
                deletedSmartMixIds = (current.deletedSmartMixIds + albumId).distinct()
            )
        } else {
            current.copy(albums = current.albums.filterNot { it.id == albumId })
        }
        _analytics.postValue(updated)
        viewModelScope.launch {
            storage.getSavedFolderUri()?.let { storage.saveAnalytics(it, updated) }
        }
        _toastMessage.postValue(if (isSmartMix) "Smart mix deleted." else "Album deleted.")
        if (_screen.value is AppScreen.AlbumDetail) {
            navigate(AppScreen.Albums)
        }
    }

    fun canDeleteAlbum(album: Album): Boolean =
        album.category == "Custom" || album.id.startsWith("smart_artist_")

    fun updateAlbum(
        albumId: String,
        title: String,
        description: String,
        songIds: List<String>,
        collageStyle: String,
        coverUri: String?
    ) {
        if (title.isBlank()) {
            _toastMessage.postValue("Please enter an album title.")
            return
        }
        if (songIds.isEmpty()) {
            _toastMessage.postValue("Please select at least 1 song for the album.")
            return
        }

        val current = _analytics.value ?: AnalyticsData()
        val existing = current.albums.find { it.id == albumId } ?: return
        val updatedAlbum = existing.copy(
            title = title.trim(),
            description = description.trim(),
            songIds = songIds.distinct(),
            collageStyle = collageStyle,
            coverUri = coverUri
        )
        val updated = current.copy(albums = current.albums.map {
            if (it.id == albumId) updatedAlbum else it
        })
        _analytics.postValue(updated)
        viewModelScope.launch {
            storage.getSavedFolderUri()?.let { storage.saveAnalytics(it, updated) }
        }
        _toastMessage.postValue("Album '${updatedAlbum.title}' updated.")
    }

    fun updateSmartMix(
        albumId: String,
        title: String,
        description: String,
        songIds: List<String>,
        collageStyle: String,
        coverUri: String?
    ) {
        if (title.isBlank()) {
            _toastMessage.postValue("Please enter a mix title.")
            return
        }

        val source = getSmartCategoryAlbums().find { it.id == albumId } ?: return
        val updatedMix = source.copy(
            title = title.trim(),
            description = description.trim(),
            songIds = songIds.distinct(),
            collageStyle = collageStyle,
            coverUri = coverUri
        )
        val current = _analytics.value ?: AnalyticsData()
        val updated = current.copy(albums = current.albums.filterNot { it.id == albumId } + updatedMix)
        _analytics.postValue(updated)
        viewModelScope.launch {
            storage.getSavedFolderUri()?.let { storage.saveAnalytics(it, updated) }
        }
        _toastMessage.postValue("Mix '${updatedMix.title}' updated.")
    }

    fun updateSongMetadata(songId: String, title: String, artist: String, album: String) {
        val existing = _songs.value.orEmpty().find { it.id == songId } ?: return
        if (title.isBlank() || artist.isBlank()) {
            _toastMessage.postValue("Song title and artist can't be empty.")
            return
        }
        val updatedSong = existing.copy(
            title = title.trim(),
            artist = artist.trim(),
            album = album.trim().ifBlank { "Unknown Album" }
        )
        storage.saveSongMetadata(updatedSong)
        val updatedSongs = _songs.value.orEmpty().map {
            if (it.id == songId) updatedSong else it
        }
        _songs.value = updatedSongs
        storage.saveSongsToCache(updatedSongs)
        playerController.updateSongDetails(updatedSong)
        _toastMessage.postValue("Song details updated.")
    }

    fun removeSongFromLibrary(songId: String) {
        val currentSongs = _songs.value.orEmpty()
        val removedSong = currentSongs.find { it.id == songId } ?: return
        val updatedSongs = currentSongs.filterNot { it.id == songId }
        if (updatedSongs.size == _songs.value.orEmpty().size) return

        storage.removeSongFromLibrary(removedSong)
        storage.saveSongsToCache(updatedSongs)
        _songs.value = updatedSongs
        playerController.removeSongFromQueue(songId)
        val current = _analytics.value ?: AnalyticsData()
        val updatedAnalytics = current.copy(
            favoriteSongIds = current.favoriteSongIds.filterNot { it == songId },
            albums = current.albums.map { album ->
                album.copy(songIds = album.songIds.filterNot { it == songId })
            }
        )
        _analytics.value = updatedAnalytics
        viewModelScope.launch {
            storage.getSavedFolderUri()?.let { storage.saveAnalytics(it, updatedAnalytics) }
        }
        _toastMessage.postValue("Song removed from your library. The audio file was not deleted.")
        if (_screen.value is AppScreen.SongDetail) navigate(AppScreen.Library)
    }

    fun getSongPlayCount(songId: String): Int =
        _analytics.value?.playEvents?.count { it.songId == songId } ?: 0

    fun getSmartCategoryAlbums(): List<Album> {
        val allSongs = _songs.value ?: emptyList()
        val favIds = _analytics.value?.favoriteSongIds ?: emptyList()
        val topStats = computeSongStats(10)

        if (allSongs.isEmpty()) return emptyList()

        val result = mutableListOf<Album>()

        // 1. Favorites / Liked Songs
        val favSongs = favIds
        result.add(Album(
            id = "smart_favs",
            title = if (favIds.isNotEmpty()) "Liked Songs" else "Favorites",
            description = if (favIds.isNotEmpty()) "${favIds.size} liked tracks" else "Like a song to save it here",
            category = "Favorites",
            songIds = favSongs,
            collageStyle = "grid2x2",
            colorHex = "#FF3B30"
        ))

        // 2. Full Library Collection
        result.add(Album(
            id = "smart_all",
            title = "Library Collection",
            description = "${allSongs.size} tracks across your music folder",
            category = "Full Library",
            songIds = allSongs.map { it.id },
            collageStyle = "grid2x2",
            colorHex = "#C8F34B"
        ))

        // 3. Recently Discovered / Added
        val recents = allSongs.sortedByDescending { it.addedAt }.take(8).map { it.id }
        result.add(Album(
            id = "smart_recent",
            title = "Recently Discovered",
            description = "Latest additions to your library",
            category = "Recently Added",
            songIds = recents,
            collageStyle = "gradient",
            colorHex = "#00D4FF"
        ))

        // 4. Heavy Rotation / Top Played Collage
        val topIds = if (topStats.isNotEmpty()) topStats.map { it.songId } else allSongs.take(8).map { it.id }
        result.add(Album(
            id = "smart_top",
            title = "Heavy Rotation",
            description = if (topStats.isNotEmpty()) "Your top played tracks" else "Featured music mix",
            category = "Top Played",
            songIds = topIds,
            collageStyle = "grid2x2",
            colorHex = "#B026FF"
        ))

        // 5. Group by Artists (Category Albums)
        val artistGroups = allSongs
            .filter { it.artist.isNotBlank() && !it.artist.equals("Unknown Artist", ignoreCase = true) }
            .groupBy { it.artist.trim().lowercase() }
            .filterValues { it.size > 1 }
            .toSortedMap()
        artistGroups.entries.take(4).forEach { (artistKey, songs) ->
            val artistName = songs.first().artist.trim()
            val artistId = storage.stableHash(artistKey)
            result.add(Album(
                id = "smart_artist_$artistId",
                title = "$artistName Mix",
                description = "${songs.size} tracks by $artistName",
                category = "Artist Spotlight",
                songIds = songs.map { it.id },
                collageStyle = "grid2x2",
                colorHex = "#FF7A00"
            ))
        }

        val current = _analytics.value ?: AnalyticsData()
        val savedMixes = current.albums.associateBy { it.id }
        return result
            .filterNot { it.id in current.deletedSmartMixIds }
            .map { generated -> savedMixes[generated.id] ?: generated }
    }

    fun getAlbumById(id: String): Album? {
        val smart = getSmartCategoryAlbums().find { it.id == id }
        if (smart != null) return smart
        return _analytics.value?.albums?.find { it.id == id }
    }

    fun clearAnalytics() {
        val cleared = AnalyticsData()
        _analytics.postValue(cleared)
        viewModelScope.launch {
            storage.getSavedFolderUri()?.let { storage.saveAnalytics(it, cleared) }
        }
        _toastMessage.postValue("Listening history cleared.")
    }

    // ── Stats helpers ─────────────────────────────────────────────────────────

    fun computeSongStats(limit: Int = 10, startedAfter: Long? = null): List<SongStats> {
        val events = _analytics.value?.playEvents
            ?.filter { startedAfter == null || it.startedAt >= startedAfter }
            ?: return emptyList()
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
        val live = liveListeningMs()
        return saved + live
    }

    private fun liveListeningMs(): Long = playerController.getLiveUnflushedMs()

    fun navigate(screen: AppScreen) { _screen.postValue(screen) }
    fun setSortMode(mode: String)   { _sortMode.postValue(mode) }
    fun rescan() {
        storage.clearSongCache()
        val uri = storage.getSavedFolderUri() ?: return
        viewModelScope.launch { scanFolderInternal(uri) }
    }

    fun getRemovedSongs(onResult: (List<Song>) -> Unit) {
        val uri = storage.getSavedFolderUri() ?: run {
            onResult(emptyList())
            return
        }
        viewModelScope.launch {
            onResult(storage.getRemovedSongs(uri))
        }
    }

    fun hasRemovedSongs(): Boolean =
        storage.getRemovedSongIds().isNotEmpty() || storage.hasRemovedSongRecords()

    fun restoreSongs(songs: List<Song>) {
        if (songs.isEmpty()) return
        storage.restoreSongsToLibrary(songs)
        rescan()
    }

    fun restoreAllRemovedSongs() {
        storage.restoreAllSongsToLibrary()
        rescan()
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
