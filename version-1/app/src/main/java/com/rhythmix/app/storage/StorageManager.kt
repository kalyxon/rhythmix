package com.rhythmix.app.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.rhythmix.app.model.AnalyticsData
import com.rhythmix.app.model.PlayEvent
import com.rhythmix.app.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ─────────────────────────────────────────────────────────────────────────────
 * StorageManager
 * ─────────────────────────────────────────────────────────────────────────────
 * THE KEY INSIGHT that makes reinstall-persistence work:
 *
 * 1. User picks their music folder via SAF (ACTION_OPEN_DOCUMENT_TREE).
 * 2. We call takePersistableUriPermission() → Android grants a permanent
 *    read+write permission to that tree URI that SURVIVES app reinstalls.
 * 3. We create/open "rhythmix_data.json" INSIDE the chosen music folder.
 * 4. On fresh install: user picks same folder → we find rhythmix_data.json →
 *    all analytics history is restored instantly. No sync needed.
 *
 * Why NOT Capacitor/localStorage?
 *   - localStorage is tied to WebView's app-private storage → wiped on uninstall.
 *   - Capacitor Filesystem can only write to app-private dirs or external storage
 *     root (needs MANAGE_EXTERNAL_STORAGE, rejected by Play Store).
 *   - SAF is the ONLY Android-approved way to write to an arbitrary user folder
 *     that persists across reinstalls without special permissions.
 * ─────────────────────────────────────────────────────────────────────────────
 */
class StorageManager(private val context: Context) {

    companion object {
        private const val TAG = "StorageManager"
        private const val PREFS_NAME = "rhythmix_prefs"
        private const val KEY_FOLDER_URI = "music_folder_uri"
        private const val KEY_FOLDER_NAME = "music_folder_name"
        private const val ANALYTICS_FILE_NAME = "rhythmix_data.json"
        private const val MAX_EVENTS = 5000
        private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "aac", "m4a", "ogg", "wav", "opus", "wma")
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    // ── Folder URI persistence ────────────────────────────────────────────────

    /** Save the tree URI and take a persistent permission so it survives reinstall */
    fun saveFolderUri(uri: Uri) {
        // Release old permission if any
        getSavedFolderUri()?.let { old ->
            try {
                context.contentResolver.releasePersistableUriPermission(
                    old,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
        }

        // Take new persistent permission
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )

        // Extract folder name for display
        val docFile = DocumentFile.fromTreeUri(context, uri)
        val folderName = docFile?.name ?: "Music"

        prefs.edit()
            .putString(KEY_FOLDER_URI, uri.toString())
            .putString(KEY_FOLDER_NAME, folderName)
            .apply()

        Log.i(TAG, "Saved folder: $folderName ($uri)")
    }

    fun getSavedFolderUri(): Uri? {
        val uriStr = prefs.getString(KEY_FOLDER_URI, null) ?: return null
        return Uri.parse(uriStr)
    }

    fun getSavedFolderName(): String {
        return prefs.getString(KEY_FOLDER_NAME, "Music") ?: "Music"
    }

    fun hasSavedFolder(): Boolean = getSavedFolderUri() != null

    /** Verify the saved URI permission is still valid */
    fun isFolderPermissionValid(): Boolean {
        val uri = getSavedFolderUri() ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    // ── Song scanning ─────────────────────────────────────────────────────────

    /**
     * Scans the chosen folder for audio files.
     * Returns list of Songs with SAF URIs.
     * [onProgress] is called with (scanned, total) for progress UI.
     */
    suspend fun scanSongs(
        folderUri: Uri,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Song> = withContext(Dispatchers.IO) {
        val rootDoc = DocumentFile.fromTreeUri(context, folderUri)
            ?: return@withContext emptyList()

        // Collect audio files (including one level deep subdirectories)
        val audioFiles = mutableListOf<DocumentFile>()
        collectAudioFiles(rootDoc, audioFiles, depth = 0)

        val songs = mutableListOf<Song>()
        val total = audioFiles.size

        audioFiles.forEachIndexed { index, docFile ->
            try {
                val name = docFile.name ?: return@forEachIndexed
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in AUDIO_EXTENSIONS) return@forEachIndexed

                val base = name.substringBeforeLast('.')
                val (artist, title) = if (base.contains(" - ")) {
                    val parts = base.split(" - ", limit = 2)
                    parts[0].trim() to parts[1].trim()
                } else {
                    "Unknown Artist" to base.trim()
                }

                val songId = stableHash(name + docFile.length())
                val duration = getAudioDuration(context, docFile.uri)

                songs.add(
                    Song(
                        id = songId,
                        title = title,
                        artist = artist,
                        album = "Unknown Album",
                        durationMs = duration,
                        uri = docFile.uri,
                        fileName = name,
                        fileSize = docFile.length()
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error processing ${docFile.name}: ${e.message}")
            }

            onProgress(index + 1, total)
        }

        songs
    }

    private fun collectAudioFiles(
        dir: DocumentFile,
        result: MutableList<DocumentFile>,
        depth: Int
    ) {
        if (depth > 2) return // limit recursion
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

    // ── Analytics file I/O ────────────────────────────────────────────────────

    /**
     * Load analytics data from rhythmix_data.json in the music folder.
     * Returns null if file doesn't exist (first install / fresh folder).
     */
    suspend fun loadAnalytics(folderUri: Uri): AnalyticsData? = withContext(Dispatchers.IO) {
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext null
            val dataFile = rootDoc.findFile(ANALYTICS_FILE_NAME) ?: return@withContext null

            context.contentResolver.openInputStream(dataFile.uri)?.use { stream ->
                val json = stream.bufferedReader().readText()
                gson.fromJson(json, AnalyticsData::class.java)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load analytics: ${e.message}")
            null
        }
    }

    /**
     * Save analytics data to rhythmix_data.json in the music folder.
     * Creates the file if it doesn't exist, overwrites if it does.
     *
     * NOTE: DocumentFile doesn't support in-place overwrite on SAF.
     * Strategy: delete old → create new. This is safe and atomic enough for JSON data.
     */
    suspend fun saveAnalytics(folderUri: Uri, data: AnalyticsData): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val rootDoc = DocumentFile.fromTreeUri(context, folderUri)
                    ?: return@withContext false

                // Keep only last MAX_EVENTS events
                val trimmed = data.copy(
                    lastSyncAt = System.currentTimeMillis(),
                    playEvents = data.playEvents.takeLast(MAX_EVENTS)
                )
                val json = gson.toJson(trimmed)

                // Delete existing file first (SAF requires this to overwrite)
                rootDoc.findFile(ANALYTICS_FILE_NAME)?.delete()

                // Create fresh file
                val newFile = rootDoc.createFile("application/json", ANALYTICS_FILE_NAME)
                    ?: return@withContext false

                context.contentResolver.openOutputStream(newFile.uri)?.use { stream ->
                    stream.write(json.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }

                Log.i(TAG, "Analytics saved: ${trimmed.playEvents.size} events")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save analytics: ${e.message}")
                false
            }
        }

    // ── Utilities ─────────────────────────────────────────────────────────────

    /** Stable hash identical to the web app's sha1like() function */
    private fun stableHash(input: String): String {
        var h = 0
        for (c in input) {
            h = (h * 31 + c.code) or 0
        }
        return Integer.toHexString(Math.abs(h)).padStart(8, '0')
    }

    /** Get audio duration via MediaMetadataRetriever */
    private fun getAudioDuration(context: Context, uri: Uri): Long {
        return try {
            android.media.MediaMetadataRetriever().use { mmr ->
                mmr.setDataSource(context, uri)
                mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
            }
        } catch (_: Exception) { 0L }
    }
}
