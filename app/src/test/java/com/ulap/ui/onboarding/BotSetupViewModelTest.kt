package com.ulap.ui.onboarding

import com.ulap.domain.usecase.SaveCredentialsUseCase
import com.ulap.domain.usecase.GetCredentialsUseCase
import com.ulap.domain.usecase.VerifyBotCredentialsUseCase
import com.ulap.domain.usecase.VerifyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * BRT — when VerifyBotCredentialsUseCase returns Success with a correctedChatId,
 * BotSetupViewModel must update uiState.chatId with the corrected value
 * before invoking the onSuccess callback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotSetupViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val verifyUseCase: VerifyBotCredentialsUseCase = mock()
    private val saveUseCase: SaveCredentialsUseCase = mock()
    private val getUseCase: GetCredentialsUseCase = mock()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        whenever(getUseCase.getToken()).thenReturn(null)
        whenever(getUseCase.getChatId()).thenReturn(null)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun verify_whenSuccessWithCorrectedChatId_updatesStateChatIdBeforeCallback() = runTest {
        whenever(verifyUseCase(any(), any())).thenReturn(
            VerifyResult.Success(botName = "MyBot", correctedChatId = "-100123")
        )
        val vm = BotSetupViewModel(verifyUseCase, saveUseCase, getUseCase)
        vm.onTokenChanged("123:token")
        vm.onChatIdChanged("-999")

        var callbackChatId: String? = null
        vm.verify {
            callbackChatId = vm.uiState.value.chatId
        }
        advanceUntilIdle()

        assertEquals(
            "chatId in state must be the corrected id when onSuccess fires",
            "-100123",
            callbackChatId,
        )
        assertEquals("-100123", vm.uiState.value.chatId)
    }

    @Test
    fun verify_whenNetworkError_doesNotCallOnSuccess() = runTest {
        whenever(verifyUseCase(any(), any())).thenReturn(
            VerifyResult.Error.Network("timeout")
        )
        val vm = BotSetupViewModel(verifyUseCase, saveUseCase, getUseCase)
        vm.onTokenChanged("123:token")
        vm.onChatIdChanged("-100999")

        var callbackInvoked = false
        vm.verify { callbackInvoked = true }
        advanceUntilIdle()

        assertEquals(false, callbackInvoked)
        val error = vm.uiState.value.verifyError
        assertEquals(true, error != null && error.isNotBlank())
    }
}
