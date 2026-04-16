package com.ulap.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Bug Reproduction Test — Support Group URL constant.
 *
 * Verifies that the canonical Telegram community group URL is defined correctly.
 * FAILS until SupportConstants.SUPPORT_GROUP_URL is added with the right value.
 */
@RunWith(JUnit4::class)
class SupportGroupUrlBrt {

    @Test
    fun supportGroupUrl_isCorrectTelegramInviteLink() {
        assertEquals(
            "https://t.me/+Y3NxM1T75dY1Njhl",
            SupportConstants.SUPPORT_GROUP_URL,
        )
    }
}
