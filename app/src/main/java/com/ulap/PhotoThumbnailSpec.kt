package com.ulap

import kotlin.math.roundToInt

/**
 * Compression and scaling constants for the preview thumbnail uploaded alongside
 * chunked image backups.
 *
 * Kept free of Android framework types so it can be exercised from plain JVM unit tests.
 */
object PhotoThumbnailSpec {

    /**
     * JPEG compression quality (0–100). 65 yields ~15–40 KB at [THUMB_MAX_EDGE_PX],
     * loading very fast over mobile data while remaining clearly legible at grid-cell size.
     */
    const val THUMB_JPEG_QUALITY = 65

    /** Longest edge of the generated preview in pixels. */
    const val THUMB_MAX_EDGE_PX = 512

    /**
     * Returns (width, height) scaled so the longest edge equals [maxEdge], preserving
     * the original aspect ratio.  Images already smaller than [maxEdge] are returned
     * unchanged (no upscaling).  Returns (1, 1) for invalid inputs.
     */
    fun computeScaledDimensions(
        srcWidth: Int,
        srcHeight: Int,
        maxEdge: Int = THUMB_MAX_EDGE_PX,
    ): Pair<Int, Int> {
        if (srcWidth <= 0 || srcHeight <= 0 || maxEdge <= 0) return Pair(1, 1)
        val longestEdge = maxOf(srcWidth, srcHeight)
        if (longestEdge <= maxEdge) return Pair(srcWidth, srcHeight)
        val scale = maxEdge.toFloat() / longestEdge
        val w = (srcWidth * scale).roundToInt().coerceAtLeast(1)
        val h = (srcHeight * scale).roundToInt().coerceAtLeast(1)
        return Pair(w, h)
    }

    /**
     * Returns the largest power-of-two `inSampleSize` such that decoding with that factor
     * keeps the bitmap at or above [targetWidth] × [targetHeight].  A subsequent
     * [android.graphics.Bitmap.createScaledBitmap] call brings the result to exact dimensions.
     *
     * Returns 1 for invalid inputs or when the source is already at or below the target.
     */
    fun computeInSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
        var sampleSize = 1
        val halfWidth = srcWidth / 2
        val halfHeight = srcHeight / 2
        while ((halfWidth / sampleSize) >= targetWidth && (halfHeight / sampleSize) >= targetHeight) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
