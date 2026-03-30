package com.ulap.di

import com.ulap.data.remote.TelegramBotApi
import com.ulap.data.remote.TelegramLogger
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.debug.DebugLogBuffer
import com.ulap.domain.repository.CredentialRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideTelegramLogger(
        api: TelegramBotApi,
        debugLogBuffer: DebugLogBuffer,
        credentialRepository: CredentialRepository,
        userPrefs: UserPreferencesRepository,
        @ApplicationScope scope: CoroutineScope,
    ): TelegramLogger = TelegramLogger(
        api = api,
        channel = debugLogBuffer.newEntryChannel,
        botToken = MutableStateFlow(credentialRepository.getBotToken()),
        loggingChatId = userPrefs.telegramLoggingChatId,
        telegramLoggingEnabled = userPrefs.telegramLoggingEnabled,
        scope = scope,
    )
}
