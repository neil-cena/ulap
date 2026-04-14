package com.ulap.ui.settings

import androidx.work.WorkManager
import com.ulap.data.auth.AuthResult
import com.ulap.data.auth.AuthSession
import com.ulap.data.auth.GoogleAuthManager
import com.ulap.data.auth.LoopbackRedirectServer
import com.ulap.data.googlephotos.GooglePhotosPickerApi
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.debug.DebugLogBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class GooglePhotosImportViewModelPkceTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var googleAuthManager: GoogleAuthManager
    private lateinit var pickerApi: GooglePhotosPickerApi
    private lateinit var workManager: WorkManager
    private lateinit var userPrefs: UserPreferencesRepository
    private lateinit var debugLog: DebugLogBuffer

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        googleAuthManager = mock()
        pickerApi = mock()
        workManager = mock()
        debugLog = mock()

        userPrefs = mock()
        whenever(userPrefs.googlePhotosWebClientId).thenReturn(MutableStateFlow("test-cid.apps.googleusercontent.com"))
        whenever(userPrefs.googlePhotosClientSecret).thenReturn(MutableStateFlow("test-secret"))
        whenever(userPrefs.pickerSessionId).thenReturn(kotlinx.coroutines.flow.flowOf(null))

        whenever(googleAuthManager.isSignedIn()).thenReturn(false)
        whenever(googleAuthManager.getAccessToken()).thenReturn(null)

        val mockServer = mock<LoopbackRedirectServer>()
        whenever(mockServer.redirectUri).thenReturn("http://127.0.0.1:9999")
        whenever(mockServer.port).thenReturn(9999)
        whenever(googleAuthManager.startAuth(any())).thenReturn(
            AuthSession(
                url = "https://accounts.google.com/o/oauth2/v2/auth?client_id=test-cid",
                codeVerifier = "test-verifier-123",
                server = mockServer,
            ),
        )

        whenever(workManager.getWorkInfosForUniqueWorkFlow(any())).thenReturn(
            kotlinx.coroutines.flow.flowOf(emptyList()),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = GooglePhotosImportViewModel(
        googleAuthManager = googleAuthManager,
        pickerApi = pickerApi,
        workManager = workManager,
        userPreferencesRepository = userPrefs,
        debugLog = debugLog,
    )

    @Test
    fun `launchSignIn opens browser and completes on success`() = runTest {
        whenever(googleAuthManager.awaitAuthResult(any(), any(), any()))
            .thenReturn(AuthResult.Success)

        val vm = createViewModel()
        var browserUrl: String? = null

        vm.launchSignIn { url -> browserUrl = url }

        assertNotNull("Browser should have been opened", browserUrl)
        assertTrue("Browser URL should contain client_id", browserUrl!!.contains("client_id=test-cid"))

        val state = vm.uiState.first()
        assertTrue("Should be signed in", state.isSignedIn)
        assertTrue("Should have access token", state.hasPhotosAccessToken)
    }

    @Test
    fun `launchSignIn shows error on auth failure`() = runTest {
        whenever(googleAuthManager.awaitAuthResult(any(), any(), any()))
            .thenReturn(AuthResult.Error("access_denied", "User denied access"))

        val vm = createViewModel()
        vm.launchSignIn { }

        val state = vm.uiState.first()
        assertFalse("Should not be signed in", state.isSignedIn)
        assertEquals("User denied access", state.error)
    }

    @Test
    fun `launchSignIn maps network_error to friendly message`() = runTest {
        whenever(googleAuthManager.awaitAuthResult(any(), any(), any()))
            .thenReturn(AuthResult.Error("network_error", "Unable to resolve host \"oauth2.googleapis.com\""))

        val vm = createViewModel()
        vm.launchSignIn { }

        val state = vm.uiState.first()
        assertFalse("Should not be signed in", state.isSignedIn)
        assertEquals(
            "Network error — please check your internet connection and try again.",
            state.error,
        )
    }

    @Test
    fun `launchSignIn does nothing without client ID`() = runTest {
        whenever(userPrefs.googlePhotosWebClientId).thenReturn(MutableStateFlow(null))
        whenever(userPrefs.googlePhotosClientSecret).thenReturn(MutableStateFlow(null))

        val vm = createViewModel()
        var opened = false
        vm.launchSignIn { opened = true }

        assertFalse("Browser should not have opened", opened)
    }

    @Test
    fun `signOut clears state`() = runTest {
        whenever(googleAuthManager.awaitAuthResult(any(), any(), any()))
            .thenReturn(AuthResult.Success)

        val vm = createViewModel()
        vm.launchSignIn { }
        assertTrue(vm.uiState.first().isSignedIn)

        whenever(googleAuthManager.getAccessToken()).thenReturn(null)
        whenever(googleAuthManager.isSignedIn()).thenReturn(false)
        vm.signOut()

        val state = vm.uiState.first()
        assertFalse("Should not be signed in after sign out", state.isSignedIn)
        assertFalse("Should not have access token", state.hasPhotosAccessToken)
    }

    @Test
    fun `initial state when signed in refreshes token`() = runTest {
        whenever(googleAuthManager.isSignedIn()).thenReturn(true)
        whenever(googleAuthManager.getAccessToken()).thenReturn(null)
        whenever(googleAuthManager.refreshToken(any(), any())).thenReturn(true)

        val vm = createViewModel()
        val state = vm.uiState.first()
        assertTrue("Should have access token after refresh", state.hasPhotosAccessToken)
    }
}
