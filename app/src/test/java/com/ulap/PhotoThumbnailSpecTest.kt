package com.ulap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PhotoThumbnailSpecTest {

    // ------- computeScaledDimensions -------

    @Test
    fun landscape_4000x3000_longest_edge_becomes_max_edge() {
        val (w, h) = PhotoThumbnailSpec.computeScaledDimensions(4000, 3000)
        assertEquals(PhotoThumbnailSpec.THUMB_MAX_EDGE_PX, w)
        assertEquals(384, h)
    }

    @Test
    fun portrait_3000x4000_longest_edge_becomes_max_edge() {
        val (w, h) = PhotoThumbnailSpec.computeScaledDimensions(3000, 4000)
        assertEquals(384, w)
        assertEquals(PhotoThumbnailSpec.THUMB_MAX_EDGE_PX, h)
    }

    @Test
    fun square_2000x2000_scales_to_max_edge_square() {
        val (w, h) = PhotoThumbnailSpec.computeScaledDimensions(2000, 2000)
        assertEquals(PhotoThumbnailSpec.THUMB_MAX_EDGE_PX, w)
        assertEquals(PhotoThumbnailSpec.THUMB_MAX_EDGE_PX, h)
    }

    @Test
    fun small_image_under_max_edge_is_not_upscaled() {
        val (w, h) = PhotoThumbnailSpec.computeScaledDimensions(300, 200)
        assertEquals(300, w)
        assertEquals(200, h)
    }

    @Test
    fun image_with_longest_edge_exactly_at_max_is_unchanged() {
        val maxEdge = PhotoThumbnailSpec.THUMB_MAX_EDGE_PX
        val (w, h) = PhotoThumbnailSpec.computeScaledDimensions(maxEdge, 300)
        assertEquals(maxEdge, w)
        assertEquals(300, h)
    }

    @Test
    fun aspect_ratio_preserved_for_landscape() {
        val (w, h) = PhotoThumbnailSpec.computeScaledDimensions(6000, 4000)
        val srcRatio = 6000.0 / 4000.0
        val outRatio = w.toDouble() / h.toDouble()
        assertTrue("aspect ratio must be preserved within 2%: got $w×$h", abs(outRatio - srcRatio) < 0.02)
    }

    @Test
    fun aspect_ratio_preserved_for_portrait() {
        val (w, h) = PhotoThumbnailSpec.computeScaledDimensions(4000, 6000)
        val srcRatio = 4000.0 / 6000.0
        val outRatio = w.toDouble() / h.toDouble()
        assertTrue("aspect ratio must be preserved within 2%: got $w×$h", abs(outRatio - srcRatio) < 0.02)
    }

    @Test
    fun custom_maxEdge_is_respected() {
        val (w, h) = PhotoThumbnailSpec.computeScaledDimensions(2000, 1000, maxEdge = 200)
        assertEquals(200, w)
        assertEquals(100, h)
    }

    @Test
    fun zero_srcWidth_returns_fallback() {
        val (w, h) = PhotoThumbnailSpec.computeScaledDimensions(0, 1000)
        assertEquals(1, w)
        assertEquals(1, h)
    }

    @Test
    fun negative_srcHeight_returns_fallback() {
        val (w, h) = PhotoThumbnailSpec.computeScaledDimensions(1000, -1)
        assertEquals(1, w)
        assertEquals(1, h)
    }

    @Test
    fun zero_maxEdge_returns_fallback() {
        val (w, h) = PhotoThumbnailSpec.computeScaledDimensions(1000, 1000, maxEdge = 0)
        assertEquals(1, w)
        assertEquals(1, h)
    }

    @Test
    fun both_output_dimensions_are_at_least_one() {
        // Extremely wide panorama: 100000×1 — height cannot round to zero.
        val (w, h) = PhotoThumbnailSpec.computeScaledDimensions(100_000, 1)
        assertTrue("width must be ≥ 1", w >= 1)
        assertTrue("height must be ≥ 1", h >= 1)
    }

    // ------- computeInSampleSize -------

    @Test
    fun inSampleSize_for_typical_large_photo_is_4() {
        // 4000×3000 → target ~512×384; decoded size with sampleSize=4 is 1000×750 (≥ target).
        val (targetW, targetH) = PhotoThumbnailSpec.computeScaledDimensions(4000, 3000)
        val result = PhotoThumbnailSpec.computeInSampleSize(4000, 3000, targetW, targetH)
        assertEquals(4, result)
    }

    @Test
    fun inSampleSize_result_is_always_a_power_of_two() {
        listOf(
            Quadruple(8000, 6000, 512, 384),
            Quadruple(1920, 1080, 512, 288),
            Quadruple(4096, 4096, 512, 512),
        ).forEach { (sw, sh, tw, th) ->
            val s = PhotoThumbnailSpec.computeInSampleSize(sw, sh, tw, th)
            assertTrue("sampleSize=$s must be a power of two for ${sw}×${sh}→${tw}×${th}",
                s > 0 && (s and (s - 1)) == 0)
        }
    }

    @Test
    fun inSampleSize_for_image_already_at_target_is_1() {
        assertEquals(1, PhotoThumbnailSpec.computeInSampleSize(512, 384, 512, 384))
    }

    @Test
    fun inSampleSize_for_image_smaller_than_target_is_1() {
        assertEquals(1, PhotoThumbnailSpec.computeInSampleSize(300, 200, 512, 384))
    }

    @Test
    fun inSampleSize_for_invalid_input_is_1() {
        assertEquals(1, PhotoThumbnailSpec.computeInSampleSize(0, 3000, 512, 384))
        assertEquals(1, PhotoThumbnailSpec.computeInSampleSize(4000, -1, 512, 384))
        assertEquals(1, PhotoThumbnailSpec.computeInSampleSize(4000, 3000, 0, 384))
        assertEquals(1, PhotoThumbnailSpec.computeInSampleSize(4000, 3000, 512, -5))
    }

    // ------- constants (guard against accidental degradation) -------

    @Test
    fun quality_constant_is_in_heavy_but_visible_compression_range() {
        // 50–75 → ~15–50 KB thumbnails that load fast and remain clearly legible.
        assertTrue(
            "THUMB_JPEG_QUALITY=${PhotoThumbnailSpec.THUMB_JPEG_QUALITY} must be in 50..75",
            PhotoThumbnailSpec.THUMB_JPEG_QUALITY in 50..75,
        )
    }

    @Test
    fun max_edge_constant_is_suitable_for_grid_thumbnails() {
        // 256–640: large enough to look crisp in a grid cell; small enough to load fast.
        assertTrue(
            "THUMB_MAX_EDGE_PX=${PhotoThumbnailSpec.THUMB_MAX_EDGE_PX} must be in 256..640",
            PhotoThumbnailSpec.THUMB_MAX_EDGE_PX in 256..640,
        )
    }
}

/** Minimal named-tuple helper used by the parameterised test above. */
private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
