package com.ulap

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
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
import com.ulap.domain.usecase.ScanMediaUseCase
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.sync.BackupForegroundService
import com.ulap.ui.theme.ThemePreference
import com.ulap.ui.Screen
import com.ulap.ui.backup.BackupScreen
import com.ulap.ui.gallery.FoldersScreen
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

    @Inject
    lateinit var getCredentials: GetCredentialsUseCase

    @Inject
    lateinit var saveCredentials: SaveCredentialsUseCase

    @Inject
    lateinit var scanMedia: ScanMediaUseCase

    @Inject
    lateinit var userPrefs: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val startDestination = if (getCredentials.hasCredentials()) Screen.Timeline.route
        else Screen.Onboarding.route
        if (getCredentials.hasCredentials()) SyncWorker.schedule(this)

        setContent {
            val themePreference by userPrefs.theme.collectAsState()
            UlapTheme(themePreference = themePreference) {
                UlapNavHost(
                    startDestination = startDestination,
                    getCredentials = getCredentials,
                    saveCredentials = saveCredentials,
                    scanMedia = scanMedia,
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
    scanMedia: ScanMediaUseCase,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val scope = rememberCoroutineScope()

    val bottomNavRoutes = listOf(Screen.Timeline.route, Screen.Folders.route, Screen.Backup.route, Screen.Settings.route)
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
                    SyncWorker.schedule(context.applicationContext)
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
                            scope.launch {
                                try { scanMedia(fullScan = false) } catch (_: Exception) { }
                            }
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
                TimelineScreen(onItemClick = { id ->
                    navController.navigate(Screen.MediaViewer.createRoute(id))
                })
            }
            composable(Screen.Folders.route) {
                FoldersScreen(onFolderClick = { _ -> })
            }
            composable(Screen.Backup.route) { BackupScreen() }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateToFolderPicker = {
                        navController.navigate(Screen.FolderPicker.createRoute(false))
                    },
                    onNavigateToRestore = { navController.navigate(Screen.Restore.route) },
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
            icon = { Icon(Icons.Default.GridView, null) },
            label = { Text("Timeline") },
        )
        NavigationBarItem(
            selected = currentRoute == Screen.Folders.route,
            onClick = { navController.navigateToTab(Screen.Folders.route) },
            icon = { Icon(Icons.Default.Folder, null) },
            label = { Text("Folders") },
        )
        NavigationBarItem(
            selected = currentRoute == Screen.Backup.route,
            onClick = { navController.navigateToTab(Screen.Backup.route) },
            icon = { Icon(Icons.Default.Backup, null) },
            label = { Text("Backup") },
        )
        NavigationBarItem(
            selected = currentRoute == Screen.Settings.route,
            onClick = { navController.navigateToTab(Screen.Settings.route) },
            icon = { Icon(Icons.Default.Settings, null) },
            label = { Text("Settings") },
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
