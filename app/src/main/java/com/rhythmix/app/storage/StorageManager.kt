package com.rhythmix.app.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.rhythmix.app.model.AnalyticsData
import com.rhythmix.app.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class StorageManager(private val context: Context) {

    companion object {
        private const val TAG = "StorageManager"
        private const val PREFS_NAME = "rhythmix_prefs"
        private const val KEY_FOLDER_URI = "music_folder_uri"
        private const val KEY_FOLDER_NAME = "music_folder_name"
        private const val KEY_CACHED_SONGS = "cached_songs_json"
        private const val KEY_CACHED_SONGS_VERSION = "cached_songs_version"
        private const val KEY_REMOVED_SONG_IDS = "removed_song_ids"
        private const val KEY_REMOVED_SONGS = "removed_songs_json"
        private const val ANALYTICS_FILE_NAME = "rhythmix_data.json"
        private const val ARTWORK_DIRECTORY_NAME = "Rhythmix Artwork"
        private const val ARTWORK_PREF_PREFIX = "song_artwork_"
        private const val SONG_METADATA_PREF_PREFIX = "song_metadata_"
        private const val MAX_EVENTS = 5000
        private const val SONG_CACHE_VERSION = 2
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "aac", "m4a", "ogg", "wav", "opus", "wma")
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder().create()

    // ── Folder URI ────────────────────────────────────────────────────────────

    fun saveFolderUri(uri: Uri) {
        val previousFolder = getSavedFolderUri()
        previousFolder?.let { old ->
            try {
                context.contentResolver.releasePersistableUriPermission(
                    old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
        }
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val folderName = DocumentFile.fromTreeUri(context, uri)?.name ?: "Music"
        prefs.edit()
            .putString(KEY_FOLDER_URI, uri.toString())
            .putString(KEY_FOLDER_NAME, folderName)
            .remove(KEY_CACHED_SONGS)
            .apply {
                if (previousFolder != uri) {
                    remove(KEY_REMOVED_SONG_IDS)
                    remove(KEY_REMOVED_SONGS)
                }
            }
            .apply()
    }

    fun getSavedFolderUri(): Uri? = prefs.getString(KEY_FOLDER_URI, null)?.let { Uri.parse(it) }
    fun getSavedFolderName(): String = prefs.getString(KEY_FOLDER_NAME, "Music") ?: "Music"
    fun hasSavedFolder(): Boolean = getSavedFolderUri() != null
    fun isFolderPermissionValid(): Boolean {
        val uri = getSavedFolderUri() ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    // ── Song cache ────────────────────────────────────────────────────────────

    fun saveSongsToCache(songs: List<Song>) {
        val cached = songs.map {
            CachedSong(it.id, it.title, it.artist, it.album, it.durationMs, it.uri.toString(), it.fileName, it.fileSize, it.addedAt)
        }
        prefs.edit()
            .putString(KEY_CACHED_SONGS, gson.toJson(cached))
            .putInt(KEY_CACHED_SONGS_VERSION, SONG_CACHE_VERSION)
            .apply()
        Log.i(TAG, "Cached ${songs.size} songs to prefs")
    }

    fun loadSongsFromCache(): List<Song>? {
        if (prefs.getInt(KEY_CACHED_SONGS_VERSION, 0) != SONG_CACHE_VERSION) return null
        val json = prefs.getString(KEY_CACHED_SONGS, null) ?: return null
        return try {
            val type = object : TypeToken<List<CachedSong>>() {}.type
            val cached: List<CachedSong> = gson.fromJson(json, type)
            cached.map {
                applySavedSongMetadata(
                    Song(it.id, it.title, it.artist, it.album, it.durationMs, Uri.parse(it.uriStr), it.fileName, it.fileSize, it.addedAt)
                )
            }.filterNot { it.id in removedSongIds() }
        } catch (e: Exception) {
            Log.w(TAG, "Cache parse failed: ${e.message}")
            null
        }
    }

    fun clearSongCache() { prefs.edit().remove(KEY_CACHED_SONGS).apply() }

    fun filterRemovedSongs(songs: List<Song>): List<Song> {
        val removedIds = removedSongIds()
        return songs.filterNot { it.id in removedIds }
    }

    fun getRemovedSongIds(): Set<String> = removedSongIds()

    suspend fun getRemovedSongs(folderUri: Uri): List<Song> {
        val discoveredSongs = scanSongs(folderUri)
        val removedIds = removedSongIds()
        val savedRemovedSongs = removedSongRecords()
        val matchedSavedSongs = savedRemovedSongs.mapNotNull { removed ->
            discoveredSongs.find { it.id == removed.id }
                ?: discoveredSongs.find { it.uri.toString() == removed.uriStr }
                ?: discoveredSongs.find { it.fileName.equals(removed.fileName, ignoreCase = true) }
        }
        val matchedLegacySongs = discoveredSongs.filter { it.id in removedIds }
        val matchedSongs = (matchedSavedSongs + matchedLegacySongs).distinctBy { it.id }
        return when {
            matchedSongs.isNotEmpty() -> matchedSongs
            removedIds.isNotEmpty() -> discoveredSongs
            else -> emptyList()
        }
    }

    fun restoreSongsToLibrary(songs: List<Song>) {
        if (songs.isEmpty()) return
        val selectedIds = songs.map { it.id }.toSet()
        val selectedUris = songs.map { it.uri.toString() }.toSet()
        val selectedFileNames = songs.map { it.fileName.lowercase() }.toSet()
        val savedRemovedSongs = removedSongRecords()
        val matchedSavedRemovedIds = savedRemovedSongs
            .filter { removed ->
                removed.id in selectedIds ||
                    removed.uriStr in selectedUris ||
                    removed.fileName.lowercase() in selectedFileNames
            }
            .map { it.id }
            .toSet()
        val restoredIds = matchedSavedRemovedIds + selectedIds
        val oldRemovedIds = removedSongIds()
        val restoringLegacyFallback = matchedSavedRemovedIds.isEmpty() &&
            selectedIds.intersect(oldRemovedIds).isEmpty()
        val remainingRemovedIds = if (restoringLegacyFallback) {
            emptySet()
        } else {
            oldRemovedIds - restoredIds
        }
        val remainingRemovedSongs = if (restoringLegacyFallback) {
            emptyList()
        } else {
            savedRemovedSongs.filterNot { it.id in restoredIds }
        }
        prefs.edit().apply {
            if (remainingRemovedIds.isEmpty()) {
                remove(KEY_REMOVED_SONG_IDS)
            } else {
                putStringSet(KEY_REMOVED_SONG_IDS, remainingRemovedIds)
            }
            if (remainingRemovedSongs.isEmpty()) {
                remove(KEY_REMOVED_SONGS)
            } else {
                putString(KEY_REMOVED_SONGS, gson.toJson(remainingRemovedSongs))
            }
        }.apply()
    }

    fun restoreAllSongsToLibrary() {
        prefs.edit()
            .remove(KEY_REMOVED_SONG_IDS)
            .remove(KEY_REMOVED_SONGS)
            .apply()
    }

    fun hasRemovedSongRecords(): Boolean = removedSongRecords().isNotEmpty()

    fun getSongArtworkUri(songId: String): Uri? =
        prefs.getString("$ARTWORK_PREF_PREFIX$songId", null)?.let(Uri::parse)

    suspend fun restoreSongArtwork(songId: String): Boolean = withContext(Dispatchers.IO) {
        val preferenceKey = "$ARTWORK_PREF_PREFIX$songId"
        val artworkUri = prefs.getString(preferenceKey, null)?.let(Uri::parse)
            ?: return@withContext false
        prefs.edit().remove(preferenceKey).apply()
        try {
            if (DocumentFile.fromSingleUri(context, artworkUri)?.delete() != true) {
                Log.w(TAG, "Unable to delete replaced artwork for song $songId")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to delete replaced artwork for song $songId", error)
        }
        true
    }

    fun saveSongMetadata(song: Song) {
        val metadata = EditableSongMetadata(song.title, song.artist, song.album)
        prefs.edit().putString("$SONG_METADATA_PREF_PREFIX${song.id}", gson.toJson(metadata)).apply()
    }

    fun removeSongFromLibrary(song: Song) {
        val removedIds = removedSongIds().toMutableSet().apply { add(song.id) }
        val removedSongs = removedSongRecords()
            .filterNot { it.id == song.id }
            .plus(song.toCachedSong())
        prefs.edit()
            .putStringSet(KEY_REMOVED_SONG_IDS, removedIds)
            .putString(KEY_REMOVED_SONGS, gson.toJson(removedSongs))
            .remove("$SONG_METADATA_PREF_PREFIX${song.id}")
            .apply()
    }

    suspend fun saveSongArtwork(songId: String, sourceUri: Uri): Uri = withContext(Dispatchers.IO) {
        val folderUri = getSavedFolderUri()
            ?: throw IOException("Choose a music folder before saving song artwork.")
        val root = DocumentFile.fromTreeUri(context, folderUri)
            ?: throw IOException("The selected music folder is no longer available.")
        val artworkDirectory = findOrCreateDirectory(root, ARTWORK_DIRECTORY_NAME)
        val mimeType = context.contentResolver.getType(sourceUri) ?: "image/jpeg"
        val extension = when (mimeType.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val fileName = "${songId}_${System.currentTimeMillis()}.$extension"
        val artworkFile = artworkDirectory.createFile(mimeType, fileName)
            ?: throw IOException("Unable to create an artwork file in the selected music folder.")

        try {
            val input = context.contentResolver.openInputStream(sourceUri)
                ?: throw IOException("Unable to read the selected image.")
            input.use { source ->
                val output = context.contentResolver.openOutputStream(artworkFile.uri)
                    ?: throw IOException("Unable to write the selected image to the music folder.")
                output.use { destination -> source.copyTo(destination) }
            }

            val preferenceKey = "$ARTWORK_PREF_PREFIX$songId"
            val previousUri = prefs.getString(preferenceKey, null)?.let(Uri::parse)
            prefs.edit().putString(preferenceKey, artworkFile.uri.toString()).apply()
            previousUri?.let { oldUri ->
                if (oldUri != artworkFile.uri) {
                    DocumentFile.fromSingleUri(context, oldUri)?.delete()
                }
            }
            artworkFile.uri
        } catch (error: Exception) {
            artworkFile.delete()
            throw error
        }
    }

    private fun findOrCreateDirectory(parent: DocumentFile, name: String): DocumentFile {
        val existing = parent.findFile(name)
        if (existing?.isDirectory == true) return existing
        if (existing != null && !existing.delete()) {
            throw IOException("Unable to prepare the artwork folder.")
        }
        return parent.createDirectory(name)
            ?: throw IOException("Unable to create the artwork folder in the selected music folder.")
    }

    // ── Song scanning ─────────────────────────────────────────────────────────

    suspend fun scanSongs(folderUri: Uri, onProgress: (Int, Int) -> Unit = { _, _ -> }): List<Song> =
        withContext(Dispatchers.IO) {
            val rootDoc = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext emptyList()
            val audioFiles = mutableListOf<DocumentFile>()
            collectAudioFiles(rootDoc, audioFiles, 0)
            val songs = mutableListOf<Song>()
            audioFiles.forEachIndexed { index, docFile ->
                try {
                    val name = docFile.name ?: return@forEachIndexed
                    val (fileArtist, fileTitle) = parseFilename(name)
                    val metadata = readAudioMetadata(docFile.uri, fileTitle, fileArtist)
                    val song = Song(
                        id = stableHash(name + docFile.length()),
                        title = metadata.title,
                        artist = metadata.artist,
                        album = metadata.album,
                        durationMs = metadata.durationMs,
                        uri = docFile.uri, fileName = name, fileSize = docFile.length()
                    )
                    songs.add(applySavedSongMetadata(song))
                } catch (e: Exception) { Log.w(TAG, "Skip ${docFile.name}: ${e.message}") }
                onProgress(index + 1, audioFiles.size)
            }
            songs
        }

    private fun collectAudioFiles(dir: DocumentFile, result: MutableList<DocumentFile>, depth: Int) {
        if (depth > 2) return
        dir.listFiles().forEach { file ->
            when {
                file.isDirectory && depth < 2 -> collectAudioFiles(file, result, depth + 1)
                file.isFile -> {
                    val ext = (file.name ?: "").substringAfterLast('.', "").lowercase()
                    if (ext in AUDIO_EXTENSIONS) result.add(file)
                }
            }
        }
    }

    private fun parseFilename(name: String): Pair<String, String> {
        val base = name.substringBeforeLast('.')
        return if (base.contains(" - ")) {
            val p = base.split(" - ", limit = 2); p[0].trim() to p[1].trim()
        } else "Unknown Artist" to base.trim()
    }

    private fun removedSongIds(): Set<String> =
        prefs.getStringSet(KEY_REMOVED_SONG_IDS, emptySet())?.toSet().orEmpty()

    private fun removedSongRecords(): List<CachedSong> {
        val json = prefs.getString(KEY_REMOVED_SONGS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<CachedSong>>() {}.type
            gson.fromJson<List<CachedSong>>(json, type).orEmpty()
        } catch (error: Exception) {
            Log.w(TAG, "Removed song records could not be read", error)
            emptyList()
        }
    }

    private fun Song.toCachedSong() = CachedSong(
        id, title, artist, album, durationMs, uri.toString(), fileName, fileSize, addedAt
    )

    private fun applySavedSongMetadata(song: Song): Song {
        val json = prefs.getString("$SONG_METADATA_PREF_PREFIX${song.id}", null) ?: return song
        return try {
            val metadata = gson.fromJson(json, EditableSongMetadata::class.java) ?: return song
            song.copy(
                title = metadata.title.ifBlank { song.title },
                artist = metadata.artist.ifBlank { song.artist },
                album = metadata.album.ifBlank { song.album }
            )
        } catch (error: Exception) {
            Log.w(TAG, "Ignoring invalid saved metadata for song ${song.id}", error)
            song
        }
    }

    // ── Analytics ─────────────────────────────────────────────────────────────

    suspend fun loadAnalytics(folderUri: Uri): AnalyticsData? = withContext(Dispatchers.IO) {
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext null
            val dataFile = rootDoc.findFile(ANALYTICS_FILE_NAME) ?: return@withContext null
            context.contentResolver.openInputStream(dataFile.uri)?.use { stream ->
                gson.fromJson(stream.bufferedReader().readText(), AnalyticsData::class.java)
            }
        } catch (e: Exception) { Log.e(TAG, "Load analytics failed: ${e.message}"); null }
    }

    suspend fun saveAnalytics(folderUri: Uri, data: AnalyticsData): Boolean = withContext(Dispatchers.IO) {
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext false
            val trimmed = data.copy(lastSyncAt = System.currentTimeMillis(), playEvents = data.playEvents.takeLast(MAX_EVENTS))
            rootDoc.findFile(ANALYTICS_FILE_NAME)?.delete()
            val newFile = rootDoc.createFile("application/json", ANALYTICS_FILE_NAME) ?: return@withContext false
            context.contentResolver.openOutputStream(newFile.uri)?.use { it.write(gson.toJson(trimmed).toByteArray()); it.flush() }
            true
        } catch (e: Exception) { Log.e(TAG, "Save analytics failed: ${e.message}"); false }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fun stableHash(input: String): String {
        var h = 0
        for (c in input) h = (h * 31 + c.code) or 0
        return Integer.toHexString(Math.abs(h)).padStart(8, '0')
    }

    private fun readAudioMetadata(uri: Uri, fallbackTitle: String, fallbackArtist: String): AudioMetadata {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            AudioMetadata(
                title = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.trim()?.takeIf(String::isNotEmpty) ?: fallbackTitle,
                artist = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.trim()?.takeIf(String::isNotEmpty) ?: fallbackArtist,
                album = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.trim()?.takeIf(String::isNotEmpty) ?: "Unknown Album",
                durationMs = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            )
        } catch (_: Exception) {
            AudioMetadata(fallbackTitle, fallbackArtist, "Unknown Album", 0L)
        } finally {
            mmr.release()
        }
    }

    private data class CachedSong(
        val id: String, val title: String, val artist: String, val album: String,
        val durationMs: Long, val uriStr: String, val fileName: String, val fileSize: Long, val addedAt: Long
    )

    private data class EditableSongMetadata(val title: String, val artist: String, val album: String)
    private data class AudioMetadata(
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long
    )
}
