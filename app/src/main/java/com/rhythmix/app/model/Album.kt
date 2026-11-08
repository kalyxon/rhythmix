package com.rhythmix.app.model

/**
 * Represents a music album or collage collection.
 * Can be auto-generated (category) or user-created (custom).
 */
data class Album(
    val id: String,
    val title: String,
    val description: String = "",
    val category: String = "Custom", // "Favorites", "Top Tracks", "Recently Added", "Artist", "Custom"
    val songIds: List<String> = emptyList(),
    val collageStyle: String = "grid2x2", // "grid2x2", "mosaic", "gradient"
    val colorHex: String = "#C8F34B",
    val createdAt: Long = System.currentTimeMillis(),
    val coverUri: String? = null
)
