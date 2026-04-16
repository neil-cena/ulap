package com.ulap.ui.backup

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.domain.model.SyncProgress
import com.ulap.domain.usecase.FetchIndexFromPinnedMessageUseCase
import com.ulap.domain.usecase.GetBackupStatsUseCase
import com.ulap.domain.usecase.ObserveFailedItemsUseCase
import com.ulap.domain.usecase.ResetFailedToPendingUseCase
import com.ulap.domain.usecase.ScanMediaUseCase
import com.ulap.sync.BackupForegroundService
import com.ulap.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Bug Reproduction Test — BackupViewModel.syncNow() (3 defects).
 *
 * Defect 1 — startBackup(context) never called:
 *   syncNow() must fire BackupForegroundService.startBackup(context), which calls
 *   context.startForegroundService() with ACTION_START_BACKUP.  Current impl: silent no-op.
 *
 * Defect 2 — fetchIndex() called redundantly:
 *   SyncEngine.runUploadPipeline() already calls fetchIndex internally.  Calling it again
 *   in syncNow() is unnecessary duplication.  Current impl: calls it.
 *
 * Defect 3 — runWithWifiCheck{} wrapper missing:
 *   syncNow() must respect the Wi-Fi-only preference.  Current impl: always runs regardless
 *   of connectivity.
 *
 * Deterministic: no network, no disk, no Room, no Hilt.
 * Android stubs return default values (unitTests.isReturnDefaultValues = true).
 * Intent construction is intercepted via Mockito.mockConstruction to verify action string.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class BackupViewModelSyncNowBrt {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var getBackupStats: GetBackupStatsUseCase
    private lateinit var resetFailedToPending: ResetFailedToPendingUseCase
    private lateinit var scanMedia: ScanMediaUseCase
    private lateinit var fetchIndex: FetchIndexFromPinnedMessageUseCase
    private lateinit var syncEngine: SyncEngine
    private lateinit var observeFailedItems: ObserveFailedItemsUseCase
    private lateinit var userPrefs: UserPreferencesRepository
    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var network: Network
    private lateinit var networkCapabilities: NetworkCapabilities

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        getBackupStats = mock()
        resetFailedToPending = mock()
        scanMedia = mock()
        fetchIndex = mock()
        syncEngine = mock()
        observeFailedItems = mock()
        userPrefs = mock()
        context = mock()
        connectivityManager = mock()
        network = mock()
        networkCapabilities = mock()

        // Safe defaults for StateFlows and Flows accessed during ViewModel initialisation.
        whenever(getBackupStats()).thenReturn(emptyFlow())
        whenever(observeFailedItems()).thenReturn(emptyFlow())
        whenever(syncEngine.progress).thenReturn(MutableStateFlow(SyncProgress()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── connectivity helpers ──────────────────────────────────────────────────

    /**
     * Configures the mocked Context to report an active Wi-Fi network so that
     * runWithWifiCheck() permits the backup action to proceed.
     */
    private fun setupWifiConnectivity() {
        whenever(context.getSystemService(ConnectivityManager::class.java))
            .thenReturn(connectivityManager)
        whenever(connectivityManager.activeNetwork).thenReturn(network)
        whenever(connectivityManager.getNetworkCapabilities(network))
            .thenReturn(networkCapabilities)
        // TRANSPORT_WIFI = 1 (compile-time constant in Android SDK stubs)
        whenever(networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            .thenReturn(true)
    }

    /**
     * Configures the mocked Context to report mobile-data-only connectivity so that
     * runWithWifiCheck() blocks the backup action when wifiOnly = true.
     */
    private fun setupMobileDataConnectivity() {
        whenever(context.getSystemService(ConnectivityManager::class.java))
            .thenReturn(connectivityManager)
        whenever(connectivityManager.activeNetwork).thenReturn(network)
        whenever(connectivityManager.getNetworkCapabilities(network))
            .thenReturn(networkCapabilities)
        whenever(networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
            .thenReturn(false)
    }

    private fun buildViewModel() = BackupViewModel(
        getBackupStats = getBackupStats,
        resetFailedToPending = resetFailedToPending,
        scanMedia = scanMedia,
        fetchIndex = fetchIndex,
        syncEngine = syncEngine,
        observeFailedItems = observeFailedItems,
        userPrefs = userPrefs,
        context = context,
    )

    // ─── Defect 1: BackupForegroundService.startBackup(context) never called ─

    /**
     * syncNow() must call context.startForegroundService() with an Intent whose
     * action is ACTION_START_BACKUP ("com.ulap.action.START_BACKUP").
     *
     * FAILS against current code: syncNow() never calls startForegroundService().
     */
    @Test
    fun syncNow_callsStartForegroundService_withStartBackupAction() = runTest {
        whenever(userPrefs.wifiOnly).thenReturn(MutableStateFlow(false))
        setupWifiConnectivity()
        val viewModel = buildViewModel()

        Mockito.mockConstruction(Intent::class.java).use { construction ->
            viewModel.syncNow()

            // Primary assertion — FAILS against current code (startForegroundService never called)
            verify(context).startForegroundService(any())

            // Secondary: the Intent constructed inside startBackup(context) must carry
            // ACTION_START_BACKUP.  mockConstruction intercepts new Intent(context, ...) and
            // records the setAction() call on the resulting mock.
            val constructedIntent = construction.constructed().firstOrNull()
            checkNotNull(constructedIntent) {
                "No Intent was constructed — BackupForegroundService.startBackup(context) was never reached"
            }
            verify(constructedIntent).setAction(BackupForegroundService.ACTION_START_BACKUP)
        }
    }

    // ─── Defect 2: fetchIndex() called redundantly ───────────────────────────

    /**
     * syncNow() must NOT call fetchIndex().
     * SyncEngine.runUploadPipeline() already performs the index fetch internally.
     *
     * FAILS against current code: syncNow() calls fetchIndex() before scanMedia().
     */
    @Test
    fun syncNow_doesNotCallFetchIndex() = runTest {
        whenever(userPrefs.wifiOnly).thenReturn(MutableStateFlow(false))
        setupWifiConnectivity()
        val viewModel = buildViewModel()

        Mockito.mockConstruction(Intent::class.java).use {
            viewModel.syncNow()

            // FAILS against current code: fetchIndex() is invoked once inside the launch block
            verify(fetchIndex, never()).invoke()
        }
    }

    // ─── Defect 3: runWithWifiCheck{} wrapper missing ────────────────────────

    /**
     * When wifiOnly = true and the device is on mobile data, syncNow() must NOT
     * proceed with any backup work — the WiFi-only guard must block the action entirely.
     * The definitive observable symptom is that scanMedia() is not called.
     *
     * FAILS against current code: syncNow() has no WiFi guard; it launches a coroutine that
     * calls scanMedia() regardless of wifiOnly or connectivity.
     *
     * Note: clearInvocations(scanMedia) discards the eager scanMedia() call from the
     * ViewModel init block so this verify is scoped to syncNow() only.
     */
    @Test
    fun syncNow_wifiOnly_mobileData_doesNotCallScanMedia() = runTest {
        whenever(userPrefs.wifiOnly).thenReturn(MutableStateFlow(true))
        setupMobileDataConnectivity()
        val viewModel = buildViewModel()

        clearInvocations(scanMedia) // discard the init-block scanMedia(fullScan=false) call

        viewModel.syncNow()

        // FAILS against current code: no WiFi guard → syncNow launches a coroutine that
        // calls scanMedia() unconditionally
        verify(scanMedia, never()).invoke(any())
    }

    /**
     * Complementary guard: when wifiOnly = true and the device is on mobile data,
     * the service must also not be started.  This assertion is meaningful after
     * defect 1 (missing startBackup call) is resolved alongside defect 3.
     */
    @Test
    fun syncNow_wifiOnly_mobileData_doesNotCallStartForegroundService() = runTest {
        whenever(userPrefs.wifiOnly).thenReturn(MutableStateFlow(true))
        setupMobileDataConnectivity()
        val viewModel = buildViewModel()

        Mockito.mockConstruction(Intent::class.java).use {
            viewModel.syncNow()

            verify(context, never()).startForegroundService(any())
        }
    }

    /**
     * When wifiOnly = true but the device IS on Wi-Fi, syncNow() must still start
     * the backup service — the guard must not block Wi-Fi users.
     *
     * FAILS against current code: syncNow() never calls startForegroundService at all.
     */
    @Test
    fun syncNow_wifiOnly_onWifi_callsStartForegroundService() = runTest {
        whenever(userPrefs.wifiOnly).thenReturn(MutableStateFlow(true))
        setupWifiConnectivity()
        val viewModel = buildViewModel()

        Mockito.mockConstruction(Intent::class.java).use {
            viewModel.syncNow()

            // FAILS against current code: startForegroundService is never called
            verify(context).startForegroundService(any())
        }
    }
}
