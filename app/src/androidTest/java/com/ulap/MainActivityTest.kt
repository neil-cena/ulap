package com.ulap

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke test. For full flow, add to local.properties:
 *   ulap.testBotToken=your_bot_token
 *   ulap.testChatId=your_chat_id
 *
 * The debug build then auto-seeds credentials and opens to Timeline. This test
 * opens the Backup tab and asserts the Backup screen loads (e.g. "Back Up Now").
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appLaunchesAndBackupScreenLoads() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Backup").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Back Up Now").assertExists()
    }
}
