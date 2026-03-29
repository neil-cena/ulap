package com.ulap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug Reproduction Tests — Telegram `PHOTO_INVALID_DIMENSIONS` (auto-debug Phase 2).
 *
 * **Defect:** Backup uses `sendPhoto` for bytes Telegram rejects when width/height/aspect violate
 * Bot API photo rules → HTTP 400 `Bad Request: PHOTO_INVALID_DIMENSIONS`.
 *
 * **Contract (black box):** For known decoded raster bounds `(w, h)`, the app must not plan or
 * perform `sendPhoto` when [TelegramBackupPolicy] says the dimensions are invalid for photos;
 * it must use `sendDocument` (or resize/re-encode first). This file asserts that contract via
 * public policy APIs and via [backupPlannerUnderTest] / [MediaInput] / [UploadPlan] from the
 * shared upload-planner contract in this package.
 *
 * Deterministic: no network, no disk, no clock, no RNG, no shared mutable state.
 */
class TelegramPhotoDimensionsBrt {

    companion object {
        /** Verbatim error from defect specification (documentation / future matchers). */
        const val MSG_TELEGRAM_PHOTO_INVALID_DIMENSIONS =
            "Telegram API error 400: Bad Request: PHOTO_INVALID_DIMENSIONS"
    }

    /**
     * End-to-end planner contract: extreme aspect ratio must not be [UploadPlan.SendAsPhoto].
     * Fails today while [ReferenceCorrectMediaUploadPlanner] only enforces max edge, not aspect
     * (panoramas are still routed like photos → Telegram 400).
     */
    @Test
    fun brt_planner_extreme_aspect_ratio_must_not_be_send_photo() {
        val sut = backupPlannerUnderTest()
        val shortEdge = 100
        val longEdge = TelegramBackupPolicy.TELEGRAM_PHOTO_MAX_ASPECT_RATIO * shortEdge + 1
        assertTrue(
            "Fixture must violate Telegram photo aspect (long/short > ${TelegramBackupPolicy.TELEGRAM_PHOTO_MAX_ASPECT_RATIO}).",
            TelegramBackupPolicy.shouldSendPhotoAsDocumentByDecodedBounds(longEdge, shortEdge),
        )
        val plan = sut.plan(
            MediaInput(
                sizeBytes = 8_000L,
                widthPx = longEdge,
                heightPx = shortEdge,
                uriReadable = true,
            ),
        )
        assertEquals(
            "Extreme aspect decoded bounds must not be planned as sendPhoto ($MSG_TELEGRAM_PHOTO_INVALID_DIMENSIONS).",
            UploadPlan.SendAsDocument,
            plan,
        )
    }

    @Test
    fun brt_planner_extreme_aspect_ratio_portrait_must_not_be_send_photo() {
        val sut = backupPlannerUnderTest()
        val shortEdge = 100
        val longEdge = TelegramBackupPolicy.TELEGRAM_PHOTO_MAX_ASPECT_RATIO * shortEdge + 1
        assertTrue(
            TelegramBackupPolicy.shouldSendPhotoAsDocumentByDecodedBounds(shortEdge, longEdge),
        )
        val plan = sut.plan(
            MediaInput(
                sizeBytes = 8_000L,
                widthPx = shortEdge,
                heightPx = longEdge,
                uriReadable = true,
            ),
        )
        assertEquals(
            "Tall-strip extreme aspect must not be planned as sendPhoto ($MSG_TELEGRAM_PHOTO_INVALID_DIMENSIONS).",
            UploadPlan.SendAsDocument,
            plan,
        )
    }

    /**
     * Both edges within Telegram's max pixel edge, but aspect still invalid - catches "max edge
     * only" preflight gaps.
     */
    @Test
    fun brt_planner_max_edge_ok_but_aspect_invalid_must_not_be_send_photo() {
        val sut = backupPlannerUnderTest()
        val w = TelegramBackupPolicy.TELEGRAM_PHOTO_MAX_EDGE_PX
        val h = 400
        assertTrue(
            "Fixture: within max edge but aspect must force document.",
            TelegramBackupPolicy.shouldSendPhotoAsDocumentByDecodedBounds(w, h),
        )
        assertTrue(
            TelegramBackupPolicy.dimensionsOkForPhoto(w, h),
        )
        val plan = sut.plan(
            MediaInput(
                sizeBytes = 50_000L,
                widthPx = w,
                heightPx = h,
                uriReadable = true,
            ),
        )
        assertEquals(
            "Large edge within limit but invalid aspect must not use sendPhoto ($MSG_TELEGRAM_PHOTO_INVALID_DIMENSIONS).",
            UploadPlan.SendAsDocument,
            plan,
        )
    }

    /** Policy surface: panorama must be flagged for non-photo upload. */
    @Test
    fun brt_policy_panorama_requires_document_path() {
        val shortEdge = 100
        val longEdge = TelegramBackupPolicy.TELEGRAM_PHOTO_MAX_ASPECT_RATIO * shortEdge + 1
        assertTrue(
            TelegramBackupPolicy.shouldSendPhotoAsDocumentByDecodedBounds(longEdge, shortEdge),
        )
    }

    /** Policy surface: exactly max allowed aspect (long/short == 20) must still be allowed as photo. */
    @Test
    fun brt_policy_aspect_exactly_twenty_to_one_still_ok_for_photo_path() {
        val shortEdge = 100
        val longEdge = TelegramBackupPolicy.TELEGRAM_PHOTO_MAX_ASPECT_RATIO * shortEdge
        assertFalse(
            TelegramBackupPolicy.shouldSendPhotoAsDocumentByDecodedBounds(longEdge, shortEdge),
        )
    }

    /** Policy surface: oversize edge must force non-photo path. */
    @Test
    fun brt_policy_exceeding_max_edge_requires_document_path() {
        val w = TelegramBackupPolicy.TELEGRAM_PHOTO_MAX_EDGE_PX + 1
        val h = 100
        assertTrue(TelegramBackupPolicy.shouldSendPhotoAsDocumentByDecodedBounds(w, h))
        assertFalse(TelegramBackupPolicy.dimensionsOkForPhoto(w, h))
    }

    /** Policy surface: non-positive decoded bounds must not be treated as sendPhoto-safe. */
    @Test
    fun brt_policy_non_positive_decoded_bounds_require_document_path() {
        assertTrue(TelegramBackupPolicy.shouldSendPhotoAsDocumentByDecodedBounds(0, 100))
        assertTrue(TelegramBackupPolicy.shouldSendPhotoAsDocumentByDecodedBounds(100, -1))
    }
}
