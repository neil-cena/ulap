package com.ulap.ui.settings

import android.content.Context
import com.ulap.data.auth.GoogleAuthManager
import com.ulap.data.repository.UserPreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class GooglePhotosSetupViewModelBrt {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var googleAuthManager: GoogleAuthManager
    private lateinit var userPreferencesRepository: UserPreferencesRepository
    private lateinit var context: Context
    private lateinit var viewModel: GooglePhotosSetupViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        googleAuthManager = mock()
        userPreferencesRepository = mock()
        context = mock()

        whenever(userPreferencesRepository.googlePhotosWebClientId)
            .thenReturn(MutableStateFlow("test-client-id"))

        viewModel = GooglePhotosSetupViewModel(
            googleAuthManager = googleAuthManager,
            userPrefs = userPreferencesRepository,
            context = context,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun clearClientId_signOutCalledBeforeClientIdCleared() = runTest {
        val order = inOrder(googleAuthManager, userPreferencesRepository)

        viewModel.clearClientId()

        order.verify(googleAuthManager).signOut()
        order.verify(userPreferencesRepository).setGooglePhotosWebClientId(null)
    }
}
