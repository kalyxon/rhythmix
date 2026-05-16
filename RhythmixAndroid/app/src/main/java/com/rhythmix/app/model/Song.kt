package com.rhythmix.app.model

import android.net.Uri

/**
 * Represents a single audio track discovered from the user's music folder.
 * [uri] is a SAF content URI valid for the lifetime of the granted permission.
 * [id] is a stable hash of filename+size — survives reinstall as long as the file exists.
 */
data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri,
    val fileName: String,
    val fileSize: Long,
    val addedAt: Long = System.currentTimeMillis()
)
