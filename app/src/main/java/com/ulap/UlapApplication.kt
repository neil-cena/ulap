package com.ulap

import android.app.Application
import androidx.work.Configuration
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

    override val workManagerConfiguration: Configuration
        get() = wmConfiguration

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            val testToken = BuildConfig.TEST_BOT_TOKEN
            val testChatId = BuildConfig.TEST_CHAT_ID
            if (testToken.isNotBlank() && testChatId.isNotBlank() && !getCredentials.hasCredentials()) {
                saveCredentials(testToken, testChatId)
            }
        }
    }
}
