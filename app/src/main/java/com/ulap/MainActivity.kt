package com.ulap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ulap.R
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ulap.domain.usecase.GetCredentialsUseCase
import com.ulap.domain.usecase.SaveCredentialsUseCase
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.sync.BackupForegroundService
import com.ulap.ui.theme.ThemePreference
import com.ulap.ui.Screen
import com.ulap.ui.backup.BackupScreen
import com.ulap.ui.gallery.FoldersScreen
import com.ulap.ui.gallery.MediaTypeScreen
import com.ulap.ui.gallery.MediaViewerScreen
import com.ulap.ui.gallery.TimelineScreen
import com.ulap.ui.onboarding.BotSetupScreen
import com.ulap.ui.onboarding.FolderPickerScreen
import com.ulap.ui.onboarding.QrScanScreen
import com.ulap.ui.onboarding.QrShowScreen
import com.ulap.ui.onboarding.WelcomeScreen
import com.ulap.ui.restore.RestoreScreen
import com.ulap.ui.settings.SettingsScreen
import com.ulap.ui.theme.UlapTheme
import com.ulap.sync.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_BACKUP_RETRY = "com.ulap.OPEN_BACKUP_RETRY"
    }

    @Inject
    lateinit var getCredentials: GetCredentialsUseCase

    @Inject
    lateinit var saveCredentials: SaveCredentialsUseCase

    @Inject
    lateinit var userPrefs: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startDestination = if (getCredentials.hasCredentials()) Screen.Timeline.route
        else Screen.Onboarding.route
        if (getCredentials.hasCredentials()) SyncWorker.schedule(
            this,
            wifiOnly = userPrefs.wifiOnly.value,
            pauseOnLowBattery = userPrefs.pauseOnLowBattery.value,
        )

        // Capture once before setContent so the value doesn't change across recompositions.
        val openBackupRetry = intent.getBooleanExtra(EXTRA_OPEN_BACKUP_RETRY, false)
        intent.removeExtra(EXTRA_OPEN_BACKUP_RETRY)

        setContent {
            val themePreference by userPrefs.theme.collectAsState()
            UlapTheme(themePreference = themePreference) {
                UlapNavHost(
                    startDestination = startDestination,
                    getCredentials = getCredentials,
                    saveCredentials = saveCredentials,
                    userPrefs = userPrefs,
                    openBackupRetryFromIntent = openBackupRetry,
                )
            }
        }
    }
}

@Composable
private fun UlapNavHost(
    startDestination: String,
    getCredentials: GetCredentialsUseCase,
    saveCredentials: SaveCredentialsUseCase,
    userPrefs: UserPreferencesRepository,
    openBackupRetryFromIntent: Boolean = false,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // Start as false; only flip to true after navigation to Backup has been issued so
    // BackupScreen's LaunchedEffect fires after the screen is on the back-stack.
    var pendingOpenBackupRetry by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (openBackupRetryFromIntent) {
            navController.navigateToTab(Screen.Backup.route)
            pendingOpenBackupRetry = true
        }
    }

    val bottomNavRoutes = listOf(Screen.Timeline.route, Screen.MediaType.route, Screen.Backup.route, Screen.Settings.route)
    val showBottomNav = currentRoute in bottomNavRoutes

    Scaffold(
        bottomBar = {
            if (showBottomNav) {
                BottomBar(navController, currentRoute)
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Onboarding.route) {
                WelcomeScreen(
                    onGetStarted = { navController.navigate(Screen.BotSetup.route) },
                    onScanQr = { navController.navigate(Screen.QrScan.route) },
                )
            }
            composable(Screen.BotSetup.route) {
                BotSetupScreen(onContinue = {
                    navController.navigate(Screen.FolderPicker.createRoute(true)) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                })
            }
            composable(Screen.QrScan.route) {
                val context = LocalContext.current
                QrScanScreen(onScanned = { creds ->
                    saveCredentials(creds.token, creds.chatId)
                    SyncWorker.schedule(
                        context.applicationContext,
                        wifiOnly = userPrefs.wifiOnly.value,
                        pauseOnLowBattery = userPrefs.pauseOnLowBattery.value,
                    )
                    navController.navigate(Screen.Timeline.route) {
                        popUpTo(0) { inclusive = true }
                    }
                })
            }
            composable(Screen.QrShow.route) {
                QrShowScreen(
                    token = getCredentials.getToken() ?: "",
                    chatId = getCredentials.getChatId() ?: "",
                )
            }
            composable(Screen.FolderPicker.route) { backStack ->
                val fromOnboarding = backStack.arguments?.getString("fromOnboarding") == "true"
                val context = LocalContext.current
                FolderPickerScreen(
                    onDone = {
                        if (fromOnboarding) {
                            BackupForegroundService.startBackup(context)
                            navController.navigate(Screen.Timeline.route) {
                                popUpTo(0) { inclusive = true }
                            }
                        } else {
                            navController.popBackStack()
                        }
                    },
                    fromOnboarding = fromOnboarding,
                )
            }
            composable(Screen.Timeline.route) {
                TimelineScreen(
                    onItemClick = { id ->
                        navController.navigate(Screen.MediaViewer.createRoute(id))
                    },
                    onSelectFolders = {
                        navController.navigate(Screen.FolderPicker.createRoute(false))
                    },
                )
            }
            composable(Screen.Folders.route) {
                FoldersScreen(onFolderClick = { _ -> })
            }
            composable(Screen.MediaType.route) {
                MediaTypeScreen(
                    onItemClick = { id ->
                        navController.navigate(Screen.MediaViewer.createRoute(id))
                    },
                )
            }
            composable(Screen.Backup.route) {
                BackupScreen(
                    onOpenWithRetry = pendingOpenBackupRetry,
                    onConsumeRetry = { pendingOpenBackupRetry = false },
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToFolderPicker = {
                        navController.navigate(Screen.FolderPicker.createRoute(false))
                    },
                    onNavigateToQrShow = {
                        navController.navigate(Screen.QrShow.route)
                    },
                )
            }
            composable(Screen.MediaViewer.route) { backStack ->
                val mediaId = backStack.arguments?.getString("mediaId") ?: return@composable
                MediaViewerScreen(
                    mediaId = mediaId,
                    onBack = { navController.popBackStack() },
                    viewModel = hiltViewModel(backStack),
                )
            }
            composable(Screen.Restore.route) { RestoreScreen() }
        }
    }
}

@Composable
private fun BottomBar(navController: NavController, currentRoute: String?) {
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == Screen.Timeline.route,
            onClick = { navController.navigateToTab(Screen.Timeline.route) },
            icon = { Icon(Icons.Default.GridView, contentDescription = stringResource(R.string.nav_timeline)) },
            label = { Text(stringResource(R.string.nav_timeline)) },
        )
        NavigationBarItem(
            selected = currentRoute == Screen.MediaType.route,
            onClick = { navController.navigateToTab(Screen.MediaType.route) },
            icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = stringResource(R.string.nav_media_type)) },
            label = { Text(stringResource(R.string.nav_media_type)) },
        )
        NavigationBarItem(
            selected = currentRoute == Screen.Backup.route,
            onClick = { navController.navigateToTab(Screen.Backup.route) },
            icon = { Icon(Icons.Default.Backup, contentDescription = stringResource(R.string.nav_backup)) },
            label = { Text(stringResource(R.string.nav_backup)) },
        )
        NavigationBarItem(
            selected = currentRoute == Screen.Settings.route,
            onClick = { navController.navigateToTab(Screen.Settings.route) },
            icon = { Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.nav_settings)) },
            label = { Text(stringResource(R.string.nav_settings)) },
        )
    }
}

private fun NavController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
