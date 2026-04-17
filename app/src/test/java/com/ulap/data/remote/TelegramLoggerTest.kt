package com.ulap.data.remote

import com.ulap.domain.model.BotCredential
import com.ulap.domain.repository.CredentialRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("NonAsciiCharacters")
class TelegramLoggerTest {

    private class FakeCredentialRepo(private val token: String?) : CredentialRepository {
        override fun getBotToken(): String? = token
        override fun getChatId(): String? = null
        override fun saveCredentials(token: String, chatId: String) {}
        override fun clearCredentials() {}
        override fun hasCredentials(): Boolean = token != null
        override fun getLastIndexFileId(): String? = null
        override fun setLastIndexFileId(fileId: String?) {}
        override fun getLastIndexMessageId(): Long? = null
        override fun setLastIndexMessageId(messageId: Long?) {}
        override fun getAdditionalBotTokens(): List<BotCredential> = emptyList()
        override fun saveAdditionalBotTokens(bots: List<BotCredential>) {}
        override fun clearAdditionalBots() {}
    }

    private class FakeTelegramBotApi : TelegramBotApi {

        data class SendCall(val token: String, val chatId: String, val text: String)

        val sendMessageCalls = mutableListOf<SendCall>()

        override suspend fun sendMessage(
            token: String,
            chatId: String,
            text: String,
        ): TelegramResponse<TelegramMessage> {
            sendMessageCalls.add(SendCall(token, chatId, text))
            return TelegramResponse(ok = true, result = null, description = null, errorCode = null, parameters = null)
        }

        override suspend fun getMe(token: String): TelegramResponse<TelegramMe> =
            throw UnsupportedOperationException("not used in TelegramLogger tests")

        override suspend fun sendPhoto(
            token: String,
            chatId: RequestBody,
            photo: MultipartBody.Part,
            caption: RequestBody?,
        ): TelegramResponse<TelegramMessage> =
            throw UnsupportedOperationException("not used in TelegramLogger tests")

        override suspend fun sendPhotoFromUrl(
            token: String,
            chatId: RequestBody,
            photoUrl: RequestBody,
            caption: RequestBody?,
        ): TelegramResponse<TelegramMessage> =
            throw UnsupportedOperationException("not used in TelegramLogger tests")

        override suspend fun sendVideo(
            token: String,
            chatId: RequestBody,
            video: MultipartBody.Part,
            caption: RequestBody?,
            supportsStreaming: RequestBody?,
        ): TelegramResponse<TelegramMessage> =
            throw UnsupportedOperationException("not used in TelegramLogger tests")

        override suspend fun sendDocument(
            token: String,
            chatId: RequestBody,
            document: MultipartBody.Part,
            caption: RequestBody?,
            thumbnail: MultipartBody.Part?,
        ): TelegramResponse<TelegramMessage> =
            throw UnsupportedOperationException("not used in TelegramLogger tests")

        override suspend fun getFile(
            token: String,
            fileId: String,
        ): TelegramResponse<TelegramFile> =
            throw UnsupportedOperationException("not used in TelegramLogger tests")

        override suspend fun deleteMessage(
            token: String,
            chatId: String,
            messageId: Long,
        ): TelegramResponse<Boolean> =
            throw UnsupportedOperationException("not used in TelegramLogger tests")

        override suspend fun forwardMessage(
            token: String,
            chatId: String,
            fromChatId: String,
            messageId: Long,
        ): TelegramResponse<TelegramMessage> =
            throw UnsupportedOperationException("not used in TelegramLogger tests")

        override suspend fun deleteMessages(
            token: String,
            chatId: String,
            messageIdsJson: String,
        ): TelegramResponse<Boolean> =
            throw UnsupportedOperationException("not used in TelegramLogger tests")

        override suspend fun getUpdates(
            token: String,
            offset: Int?,
            limit: Int?,
        ): TelegramResponse<List<TelegramUpdate>> =
            throw UnsupportedOperationException("not used in TelegramLogger tests")

        override suspend fun getChat(
            token: String,
            chatId: String,
        ): TelegramResponse<TelegramChatInfo> =
            throw UnsupportedOperationException("not used in TelegramLogger tests")

        override suspend fun pinChatMessage(
            token: String,
            chatId: String,
            messageId: Long,
            disableNotification: Boolean,
        ): TelegramResponse<Boolean> =
            throw UnsupportedOperationException("not used in TelegramLogger tests")

        override suspend fun getChatMember(
            token: String,
            chatId: String,
            userId: Long,
        ): TelegramResponse<com.ulap.data.remote.TelegramChatMember> =
            throw UnsupportedOperationException("not used in TelegramLogger tests")
    }

    private fun makeFreshChannel() = Channel<String>(capacity = Channel.UNLIMITED)

    @Test
    fun `null credentials — send is skipped`() = runTest {
        val fakeApi = FakeTelegramBotApi()
        val channel = makeFreshChannel()

        val logger = TelegramLogger(
            api = fakeApi,
            channel = channel,
            credentialRepository = FakeCredentialRepo(null),
            loggingChatId = MutableStateFlow("chat-id"),
            telegramLoggingEnabled = MutableStateFlow(true),
            scope = backgroundScope,
        )

        channel.trySend("should not be sent")
        logger.flushNow()

        assertTrue(
            "sendMessage must not be called when botToken is null, " +
                "but got ${fakeApi.sendMessageCalls.size} call(s)",
            fakeApi.sendMessageCalls.isEmpty(),
        )
    }

    @Test
    fun `toggle disabled — send is skipped`() = runTest {
        val fakeApi = FakeTelegramBotApi()
        val channel = makeFreshChannel()

        val logger = TelegramLogger(
            api = fakeApi,
            channel = channel,
            credentialRepository = FakeCredentialRepo("valid-token"),
            loggingChatId = MutableStateFlow("chat-id"),
            telegramLoggingEnabled = MutableStateFlow(false),
            scope = backgroundScope,
        )

        channel.trySend("should not be sent")
        logger.flushNow()

        assertTrue(
            "sendMessage must not be called when telegramLoggingEnabled is false, " +
                "but got ${fakeApi.sendMessageCalls.size} call(s)",
            fakeApi.sendMessageCalls.isEmpty(),
        )
    }

    @Test
    fun `entries are batched into one sendMessage call`() = runTest {
        val fakeApi = FakeTelegramBotApi()
        val channel = makeFreshChannel()

        val logger = TelegramLogger(
            api = fakeApi,
            channel = channel,
            credentialRepository = FakeCredentialRepo("valid-token"),
            loggingChatId = MutableStateFlow("chat-id"),
            telegramLoggingEnabled = MutableStateFlow(true),
            scope = backgroundScope,
        )

        channel.trySend("line 1")
        channel.trySend("line 2")
        logger.flushNow()

        assertEquals(
            "Two entries should produce exactly one sendMessage call, " +
                "but got ${fakeApi.sendMessageCalls.size}",
            1,
            fakeApi.sendMessageCalls.size,
        )

        val sentText = fakeApi.sendMessageCalls[0].text
        assertTrue(
            "Batch text must contain 'line 1', but got: $sentText",
            sentText.contains("line 1"),
        )
        assertTrue(
            "Batch text must contain 'line 2', but got: $sentText",
            sentText.contains("line 2"),
        )
    }

    @Test
    fun `droppedSinceLastFlush prepends warning to batch`() = runTest {
        val fakeApi = FakeTelegramBotApi()
        val channel = makeFreshChannel()

        val logger = TelegramLogger(
            api = fakeApi,
            channel = channel,
            credentialRepository = FakeCredentialRepo("valid-token"),
            loggingChatId = MutableStateFlow("chat-id"),
            telegramLoggingEnabled = MutableStateFlow(true),
            scope = backgroundScope,
        )

        repeat(501) { i -> channel.trySend("entry $i") }
        logger.flushNow()

        assertTrue(
            "Expected at least one sendMessage call after flush with 501 entries, " +
                "but got ${fakeApi.sendMessageCalls.size}",
            fakeApi.sendMessageCalls.isNotEmpty(),
        )

        val sentText = fakeApi.sendMessageCalls[0].text
        assertTrue(
            "Batch text must start with a drop warning (⚠) when entries were dropped, " +
                "but got: ${sentText.take(120)}",
            sentText.startsWith("⚠"),
        )
    }
}
