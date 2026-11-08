package com.rhythmix.app.ui.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.rhythmix.app.model.Song
import com.rhythmix.app.ui.ArtworkLoader
import kotlin.math.abs

/**
 * Custom View that renders a stylish visual album artwork collage
 * generated from member songs' title/artist hash colors and initials.
 */
class AlbumCollageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val PALETTE = listOf(
        intArrayOf(0xC8, 0xFF, 0x00), // Lime
        intArrayOf(0x9B, 0x6D, 0xFF), // Purple
        intArrayOf(0xFF, 0x4D, 0x8F), // Pink
        intArrayOf(0x00, 0xD4, 0xFF), // Cyan
        intArrayOf(0xFF, 0x8C, 0x42), // Orange
        intArrayOf(0x1D, 0xB9, 0x54), // Green
        intArrayOf(0xFF, 0xB8, 0x30), // Amber
        intArrayOf(0x00, 0xBC, 0xD4), // Teal
        intArrayOf(0xE0, 0x40, 0xFB), // Violet
        intArrayOf(0xFF, 0x52, 0x52)  // Red
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private val rectF = RectF()
    private val requestedArtworkIds = HashSet<String>()

    var songs: List<Song> = emptyList()
        set(value) {
            field = value
            requestedArtworkIds.clear()
            invalidate()
        }

    var albumTitle: String = "Album"
        set(value) {
            field = value
            invalidate()
        }

    var collageStyle: String = "grid2x2"
        set(value) {
            field = value
            invalidate()
        }

    var cornerRadiusDp: Float = 16f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val den = resources.displayMetrics.density
        val r = cornerRadiusDp * den

        // Clip rounded corners
        path.reset()
        rectF.set(0f, 0f, w, h)
        path.addRoundRect(rectF, r, r, Path.Direction.CW)
        canvas.clipPath(path)

        val items = songs.take(4)

        if (items.isEmpty()) {
            // Draw placeholder single tile
            drawTile(canvas, 0f, 0f, w, h, albumTitle, albumTitle.firstOrNull()?.uppercase() ?: "♪")
            return
        }

        if (items.size == 1 || collageStyle == "gradient") {
            // Single song or gradient background style
            val song = items.first()
            drawSongTile(canvas, 0f, 0f, w, h, song, w * 0.3f)
            return
        }

        drawAlbumDeck(canvas, items, w, h, den)
    }

    private fun drawAlbumDeck(canvas: Canvas, items: List<Song>, w: Float, h: Float, den: Float) {
        val cardWidth = w * 0.62f
        val cardHeight = h * 0.82f
        val left = (w - cardWidth) / 2f
        val top = (h - cardHeight) / 2f + den * 4f
        val radius = 10f * den

        drawDeckCard(canvas, items[1], left - w * 0.13f, top + den * 8f, cardWidth, cardHeight, -12f, radius)
        if (items.size > 2) {
            drawDeckCard(canvas, items[2], left + w * 0.13f, top + den * 8f, cardWidth, cardHeight, 12f, radius)
        }
        drawDeckCard(canvas, items[0], left, top, cardWidth, cardHeight, 0f, radius)
    }

    private fun drawDeckCard(
        canvas: Canvas,
        song: Song,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        rotation: Float,
        radius: Float
    ) {
        val centerX = x + width / 2f
        val centerY = y + height / 2f
        canvas.save()
        canvas.rotate(rotation, centerX, centerY)
        path.reset()
        rectF.set(x, y, x + width, y + height)
        path.addRoundRect(rectF, radius, radius, Path.Direction.CW)
        canvas.clipPath(path)
        drawSongTile(canvas, x, y, width, height, song, width * 0.4f)
        canvas.restore()

        paint.color = Color.argb(75, 255, 255, 255)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density
        canvas.save()
        canvas.rotate(rotation, centerX, centerY)
        canvas.drawRoundRect(x, y, x + width, y + height, radius, radius, paint)
        canvas.restore()
        paint.style = Paint.Style.FILL
    }

    private fun drawSongTile(canvas: Canvas, x: Float, y: Float, tw: Float, th: Float, song: Song, textSize: Float) {
        val bitmap = ArtworkLoader.cachedBitmap(song)
        if (bitmap != null) {
            requestedArtworkIds.remove(song.uri.toString())
            drawArtworkBitmap(canvas, bitmap, x, y, tw, th)
            return
        }

        val key = song.uri.toString()
        if (requestedArtworkIds.add(key)) {
            ArtworkLoader.getOrRequestBitmap(song, context) {
                if (ArtworkLoader.hasNoArtwork(song)) {
                    ArtworkLoader.getFallbackBitmap(song, context) { postInvalidate() }
                }
                postInvalidate()
            }
        }
        if (ArtworkLoader.hasNoArtwork(song)) {
            val fallback = ArtworkLoader.getFallbackBitmap(song, context) { postInvalidate() }
            if (fallback != null) {
                drawArtworkBitmap(canvas, fallback, x, y, tw, th)
                return
            }
        }
        val rgb = getRgbForId(song.id)
        drawTileRgb(
            canvas,
            x,
            y,
            tw,
            th,
            rgb,
            song.title.firstOrNull()?.uppercase() ?: "♪",
            textSize
        )
    }

    private fun drawArtworkBitmap(canvas: Canvas, bitmap: Bitmap, x: Float, y: Float, tw: Float, th: Float) {
        val scale = maxOf(tw / bitmap.width, th / bitmap.height)
        val cropWidth = tw / scale
        val cropHeight = th / scale
        val source = Rect(
            ((bitmap.width - cropWidth) / 2f).toInt(),
            ((bitmap.height - cropHeight) / 2f).toInt(),
            ((bitmap.width + cropWidth) / 2f).toInt(),
            ((bitmap.height + cropHeight) / 2f).toInt()
        )
        rectF.set(x, y, x + tw, y + th)
        paint.isFilterBitmap = true
        canvas.drawBitmap(bitmap, source, rectF, paint)
    }

    private fun drawTile(canvas: Canvas, x: Float, y: Float, tw: Float, th: Float, idSeed: String, letter: String) {
        val rgb = getRgbForId(idSeed)
        drawTileRgb(canvas, x, y, tw, th, rgb, letter, tw * 0.35f)
    }

    private fun drawTileRgb(canvas: Canvas, x: Float, y: Float, tw: Float, th: Float, rgb: IntArray, letter: String, textSize: Float) {
        // Dark background tint
        paint.color = Color.rgb(rgb[0] / 3, rgb[1] / 3, rgb[2] / 3)
        canvas.drawRect(x, y, x + tw, y + th, paint)

        // Subtle gradient highlight
        val grad = LinearGradient(
            x, y, x + tw, y + th,
            Color.rgb(rgb[0] / 2, rgb[1] / 2, rgb[2] / 2),
            Color.rgb(rgb[0] / 4, rgb[1] / 4, rgb[2] / 4),
            Shader.TileMode.CLAMP
        )
        paint.shader = grad
        canvas.drawRect(x, y, x + tw, y + th, paint)
        paint.shader = null

        // Letter
        textPaint.color = Color.rgb(rgb[0], rgb[1], rgb[2])
        textPaint.textSize = textSize
        val fontMetrics = textPaint.fontMetrics
        val baseline = y + (th / 2f) - ((fontMetrics.descent + fontMetrics.ascent) / 2f)
        canvas.drawText(letter, x + (tw / 2f), baseline, textPaint)
    }

    private fun getRgbForId(idStr: String): IntArray {
        var hash = 0
        for (c in idStr) hash = (hash * 31 + c.code) or 0
        return PALETTE[abs(hash) % PALETTE.size]
    }
}
