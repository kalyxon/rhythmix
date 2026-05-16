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

class StorageManager(private val context: Context) {

    companion object {
        private const val TAG = "StorageManager"
        private const val PREFS_NAME = "rhythmix_prefs"
        private const val KEY_FOLDER_URI = "music_folder_uri"
        private const val KEY_FOLDER_NAME = "music_folder_name"
        private const val KEY_CACHED_SONGS = "cached_songs_json"
        private const val ANALYTICS_FILE_NAME = "rhythmix_data.json"
        private const val MAX_EVENTS = 5000
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "aac", "m4a", "ogg", "wav", "opus", "wma")
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder().create()

    // ── Folder URI ────────────────────────────────────────────────────────────

    fun saveFolderUri(uri: Uri) {
        getSavedFolderUri()?.let { old ->
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
        prefs.edit().putString(KEY_CACHED_SONGS, gson.toJson(cached)).apply()
        Log.i(TAG, "Cached ${songs.size} songs to prefs")
    }

    fun loadSongsFromCache(): List<Song>? {
        val json = prefs.getString(KEY_CACHED_SONGS, null) ?: return null
        return try {
            val type = object : TypeToken<List<CachedSong>>() {}.type
            val cached: List<CachedSong> = gson.fromJson(json, type)
            cached.map { Song(it.id, it.title, it.artist, it.album, it.durationMs, Uri.parse(it.uriStr), it.fileName, it.fileSize, it.addedAt) }
        } catch (e: Exception) {
            Log.w(TAG, "Cache parse failed: ${e.message}")
            null
        }
    }

    fun clearSongCache() { prefs.edit().remove(KEY_CACHED_SONGS).apply() }

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
                    val (artist, title) = parseFilename(name)
                    songs.add(Song(
                        id = stableHash(name + docFile.length()),
                        title = title, artist = artist, album = "Unknown Album",
                        durationMs = getAudioDuration(docFile.uri),
                        uri = docFile.uri, fileName = name, fileSize = docFile.length()
                    ))
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

    private fun getAudioDuration(uri: Uri): Long = try {
        android.media.MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(context, uri)
            mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    } catch (_: Exception) { 0L }

    private data class CachedSong(
        val id: String, val title: String, val artist: String, val album: String,
        val durationMs: Long, val uriStr: String, val fileName: String, val fileSize: Long, val addedAt: Long
    )
}
