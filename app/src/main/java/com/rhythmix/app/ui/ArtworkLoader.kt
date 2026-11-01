package com.rhythmix.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import com.rhythmix.app.R
import com.rhythmix.app.model.Song
import com.rhythmix.app.storage.StorageManager
import java.util.HashMap
import java.util.HashSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object ArtworkLoader {

    private const val TAG = "ArtworkLoader"
    private val executor = Executors.newFixedThreadPool(2)
    private val cacheLock = Any()
    private val inFlight = HashMap<String, MutableList<() -> Unit>>()
    private val fallbackInFlight = HashMap<Int, MutableList<() -> Unit>>()
    private val missingArtwork = HashSet<String>()
    private val requestVersions = ConcurrentHashMap<String, Int>()
    private val fallbackCovers = intArrayOf(
        R.drawable.cover_01, R.drawable.cover_02, R.drawable.cover_03,
        R.drawable.cover_04, R.drawable.cover_05, R.drawable.cover_06,
        R.drawable.cover_07, R.drawable.cover_08, R.drawable.cover_09,
        R.drawable.cover_10, R.drawable.cover_11, R.drawable.cover_12
    )
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int = bitmap.byteCount
    }
    private val fallbackCache = object : LruCache<Int, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: Int, bitmap: Bitmap): Int = bitmap.byteCount
    }

    fun fallbackResource(song: Song): Int =
        fallbackCovers[Math.floorMod(song.id.hashCode(), fallbackCovers.size)]

    fun getFallbackBitmap(
        song: Song,
        context: android.content.Context,
        onLoaded: () -> Unit
    ): Bitmap? {
        val resource = fallbackResource(song)
        var startRequest = false
        synchronized(cacheLock) {
            fallbackCache.get(resource)?.let { return it }
            val callbacks = fallbackInFlight[resource]
            if (callbacks == null) {
                fallbackInFlight[resource] = mutableListOf(onLoaded)
                startRequest = true
            } else {
                callbacks.add(onLoaded)
            }
        }
        if (startRequest) {
            val appContext = context.applicationContext
            executor.execute {
                val bitmap = BitmapFactory.decodeResource(appContext.resources, resource)
                val callbacks = synchronized(cacheLock) {
                    if (bitmap != null) fallbackCache.put(resource, bitmap)
                    fallbackInFlight.remove(resource).orEmpty()
                }
                callbacks.forEach { it() }
            }
        }
        return null
    }

    fun load(song: Song, imageView: ImageView, circular: Boolean = false) {
        val key = song.id
        imageView.tag = key
        imageView.scaleType = ImageView.ScaleType.CENTER_CROP
        imageView.setBackgroundResource(
            if (circular) R.drawable.bg_artwork_circle_placeholder else R.drawable.bg_artwork_placeholder
        )
        imageView.clipToOutline = true
        imageView.setImageResource(R.drawable.ic_artwork_default)

        val appContext = imageView.context.applicationContext
        val bitmap = getOrRequestBitmap(song, appContext) {
            imageView.post {
                if (imageView.tag != key) return@post
                val artwork = cachedBitmap(key)
                when {
                    artwork != null -> imageView.setImageBitmap(artwork)
                    hasNoArtwork(song) -> imageView.setImageResource(fallbackResource(song))
                }
            }
        }
        if (bitmap != null) imageView.setImageBitmap(bitmap)
    }

    fun getOrRequestBitmap(song: Song, context: android.content.Context, onLoaded: () -> Unit): Bitmap? {
        val key = song.id
        val version = requestVersions[key] ?: 0
        val requestKey = "$key:$version"
        var startRequest = false
        synchronized(cacheLock) {
            cache.get(key)?.let { return it }
            if (requestKey in missingArtwork) return null
            val callbacks = inFlight[requestKey]
            if (callbacks == null) {
                inFlight[requestKey] = mutableListOf(onLoaded)
                startRequest = true
            } else {
                callbacks.add(onLoaded)
            }
        }

        if (startRequest) {
            val customUri = StorageManager(context).getSongArtworkUri(key)
            executor.execute {
                val bitmap = extractBitmap(song, context, customUri)
                val callbacks = synchronized(cacheLock) {
                    if ((requestVersions[key] ?: 0) == version) {
                        if (bitmap == null) missingArtwork.add(requestKey) else cache.put(key, bitmap)
                    }
                    inFlight.remove(requestKey).orEmpty()
                }
                callbacks.forEach { it() }
            }
        }
        return null
    }

    private fun cachedBitmap(key: String): Bitmap? = synchronized(cacheLock) { cache.get(key) }

    fun cachedBitmap(song: Song): Bitmap? = cachedBitmap(song.id)

    fun hasNoArtwork(song: Song): Boolean = synchronized(cacheLock) {
        "${song.id}:${requestVersions[song.id] ?: 0}" in missingArtwork
    }

    fun clear(songId: String) {
        synchronized(cacheLock) {
            cache.remove(songId)
            requestVersions[songId] = (requestVersions[songId] ?: 0) + 1
            missingArtwork.removeAll { it.startsWith("$songId:") }
        }
    }

    private fun extractBitmap(
        song: Song,
        context: android.content.Context,
        customUri: android.net.Uri?
    ): Bitmap? {
        if (customUri != null) {
            decodeBitmap(context, customUri)?.let { return it }
            Log.w(TAG, "Unable to load saved artwork for ${song.fileName}; trying embedded artwork")
        }

        val retriever = MediaMetadataRetriever()
        return try {
            try {
                retriever.setDataSource(context, song.uri)
            } catch (_: Exception) {
                val descriptor = context.contentResolver.openFileDescriptor(song.uri, "r")
                    ?: throw java.io.IOException("Unable to open ${song.fileName}")
                descriptor.use { retriever.setDataSource(it.fileDescriptor) }
            }

            retriever.embeddedPicture?.let { bytes ->
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                options.inSampleSize = 1
                while (options.outWidth / options.inSampleSize > 1024 ||
                    options.outHeight / options.inSampleSize > 1024
                ) {
                    options.inSampleSize *= 2
                }
                options.inJustDecodeBounds = false
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to read embedded artwork for ${song.fileName}", error)
            null
        } finally {
            retriever.release()
        }
    }

    private fun decodeBitmap(context: android.content.Context, uri: android.net.Uri): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options()
            while (bounds.outWidth / options.inSampleSize > 1024 ||
                bounds.outHeight / options.inSampleSize > 1024
            ) {
                options.inSampleSize *= 2
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        } catch (error: Exception) {
            Log.w(TAG, "Unable to decode selected artwork", error)
            null
        }
    }
}
