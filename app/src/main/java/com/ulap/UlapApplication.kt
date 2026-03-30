package com.ulap

import android.app.Application
import androidx.work.Configuration
import com.ulap.data.remote.TelegramLogger
import com.ulap.debug.DebugLogBuffer
import com.ulap.domain.usecase.GetCredentialsUseCase
import com.ulap.domain.usecase.SaveCredentialsUseCase
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class UlapApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var wmConfiguration: Configuration

    @Inject
    lateinit var getCredentials: GetCredentialsUseCase

    @Inject
    lateinit var saveCredentials: SaveCredentialsUseCase

    @Inject
    lateinit var debugLog: DebugLogBuffer

    @Inject
    lateinit var telegramLogger: TelegramLogger

    override val workManagerConfiguration: Configuration
        get() = wmConfiguration

    override fun onCreate() {
        super.onCreate()

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                debugLog.log("UlapApplication", "UNCAUGHT EXCEPTION on thread ${thread.name}: ${throwable::class.java.name}")
                if (::telegramLogger.isInitialized) {
                    telegramLogger.logFatal(throwable)
                }
            }
            previousHandler?.uncaughtException(thread, throwable)
        }

        if (BuildConfig.DEBUG) {
            val testToken = BuildConfig.TEST_BOT_TOKEN
            val testChatId = BuildConfig.TEST_CHAT_ID
            if (testToken.isNotBlank() && testChatId.isNotBlank() && !getCredentials.hasCredentials()) {
                saveCredentials(testToken, testChatId)
            }
        }

        debugLog.log("UlapApplication", "onCreate — app started")
    }
}
