package com.ulap.ui

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object BotSetup : Screen("bot_setup")
    object QrScan : Screen("qr_scan")
    object QrShow : Screen("qr_show")
    object FolderPicker : Screen("folder_picker/{fromOnboarding}") {
        fun createRoute(fromOnboarding: Boolean) = "folder_picker/$fromOnboarding"
    }
    object Timeline : Screen("timeline")
    object Folders : Screen("folders")
    object MediaType : Screen("media_type")
    object Backup : Screen("backup")
    object Settings : Screen("settings")
    object MediaViewer : Screen("media_viewer/{mediaId}") {
        fun createRoute(mediaId: String) = "media_viewer/$mediaId"
    }
    object FolderDetail : Screen("folder_detail/{bucketName}") {
        fun createRoute(bucket: String) = "folder_detail/$bucket"
    }
    object Restore : Screen("restore")
}
