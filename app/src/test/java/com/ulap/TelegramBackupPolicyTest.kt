package com.ulap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramBackupPolicyTest {

    @Test
    fun shouldSendPhotoAsDocument_false_for_square_under_edge_limit() {
        assertFalse(TelegramBackupPolicy.shouldSendPhotoAsDocumentByDecodedBounds(1000, 1000))
    }

    @Test
    fun shouldSendPhotoAsDocument_false_for_ratio_exactly_twenty_to_one() {
        assertFalse(TelegramBackupPolicy.shouldSendPhotoAsDocumentByDecodedBounds(2000, 100))
    }

    @Test
    fun shouldSendPhotoAsDocument_true_for_panorama_over_twenty_to_one() {
        assertTrue(TelegramBackupPolicy.shouldSendPhotoAsDocumentByDecodedBounds(2100, 100))
    }

    @Test
    fun shouldSendPhotoAsDocument_true_for_tall_strip_over_twenty_to_one() {
        assertTrue(TelegramBackupPolicy.shouldSendPhotoAsDocumentByDecodedBounds(100, 2100))
    }

    @Test
    fun shouldSendPhotoAsDocument_true_when_edge_exceeds_telegram_limit() {
        assertTrue(
            TelegramBackupPolicy.shouldSendPhotoAsDocumentByDecodedBounds(
                TelegramBackupPolicy.TELEGRAM_PHOTO_MAX_EDGE_PX + 1,
                100,
            ),
        )
    }

    @Test
    fun shouldSendPhotoAsDocument_true_for_non_positive_dimensions() {
        assertTrue(TelegramBackupPolicy.shouldSendPhotoAsDocumentByDecodedBounds(0, 100))
        assertTrue(TelegramBackupPolicy.shouldSendPhotoAsDocumentByDecodedBounds(100, -1))
    }
}
