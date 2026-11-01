package com.rhythmix.app.model

/**
 * A single play event — mirrors the web app's playEvents array exactly.
 * Written to rhythmix_data.json in the user's music folder.
 */
data class PlayEvent(
    val songId: String,
    val startedAt: Long,       // epoch ms
    val listenedMs: Long,
    val completed: Boolean
)

/**
 * Root analytics file — serialised as JSON and saved in the music folder.
 * Schema is kept identical to the web app so data is portable between platforms.
 */
data class AnalyticsData(
    val schemaVersion: Int = 1,
    val lastSyncAt: Long = System.currentTimeMillis(),
    val playEvents: List<PlayEvent> = emptyList(),
    val albums: List<Album> = emptyList(),
    val favoriteSongIds: List<String> = emptyList()
)

/** Lightweight summary used by the dashboard/analytics UI */
data class SongStats(
    val songId: String,
    val title: String,
    val artist: String,
    val playCount: Int,
    val completedCount: Int,
    val totalListenedMs: Long
)
