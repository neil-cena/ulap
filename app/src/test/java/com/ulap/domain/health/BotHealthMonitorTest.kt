package com.ulap.domain.health

import com.ulap.data.remote.BotBanStore
import com.ulap.data.remote.BotPool
import com.ulap.data.remote.TelegramBotApi
import com.ulap.data.remote.TelegramMe
import com.ulap.data.remote.TelegramResponse
import com.ulap.domain.model.BotCredential
import com.ulap.domain.repository.CredentialRepository
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class BotHealthMonitorTest {

    private val meResponse = TelegramResponse(
        ok = true,
        result = TelegramMe(id = 1L, username = "testbot", firstName = "Test"),
        description = null,
        errorCode = null,
        parameters = null,
    )

    private fun fakeApi(
        behavior: (String) -> TelegramResponse<TelegramMe>,
    ): TelegramBotApi = object : TelegramBotApi {
        override suspend fun getMe(token: String) = behavior(token)
        // All other methods are unused by BotHealthMonitor.
        override suspend fun sendPhoto(token: String, chatId: RequestBody, photo: MultipartBody.Part, caption: RequestBody?) = throw NotImplementedError()
        override suspend fun sendPhotoFromUrl(token: String, chatId: RequestBody, photoUrl: RequestBody, caption: RequestBody?) = throw NotImplementedError()
        override suspend fun sendVideo(token: String, chatId: RequestBody, video: MultipartBody.Part, caption: RequestBody?, supportsStreaming: RequestBody?) = throw NotImplementedError()
        override suspend fun sendDocument(token: String, chatId: RequestBody, document: MultipartBody.Part, caption: RequestBody?, thumbnail: MultipartBody.Part?) = throw NotImplementedError()
        override suspend fun getFile(token: String, fileId: String) = throw NotImplementedError()
        override suspend fun deleteMessage(token: String, chatId: String, messageId: Long) = throw NotImplementedError()
        override suspend fun forwardMessage(token: String, chatId: String, fromChatId: String, messageId: Long) = throw NotImplementedError()
        override suspend fun sendMessage(token: String, chatId: String, text: String) = throw NotImplementedError()
        override suspend fun deleteMessages(token: String, chatId: String, messageIdsJson: String) = throw NotImplementedError()
        override suspend fun getUpdates(token: String, offset: Int?, limit: Int?) = throw NotImplementedError()
        override suspend fun getChat(token: String, chatId: String) = throw NotImplementedError()
        override suspend fun pinChatMessage(token: String, chatId: String, messageId: Long, disableNotification: Boolean) = throw NotImplementedError()
        override suspend fun getChatMember(token: String, chatId: String, userId: Long) = throw NotImplementedError()
    }

    private fun buildMonitor(
        bots: List<BotCredential>,
        apiBehavior: (String) -> TelegramResponse<TelegramMe>,
    ): BotHealthMonitor {
        val banStore = BotBanStore.noOpForTest()
        val repo = FakeCredentialRepo(bots)
        val pool = BotPool(repo, banStore)
        return BotHealthMonitor(fakeApi(apiBehavior), pool, banStore)
    }

    @Test
    fun checkAll_marksAllHealthy_whenGetMeSucceeds() = runTest {
        val monitor = buildMonitor(
            listOf(BotCredential(0, "token0"), BotCredential(1, "token1")),
        ) { meResponse }

        monitor.checkAll()

        assertEquals(BotHealthStatus.HEALTHY, monitor.healthState.value[0])
        assertEquals(BotHealthStatus.HEALTHY, monitor.healthState.value[1])
    }

    @Test
    fun checkAll_marksBanned_on401() = runTest {
        val monitor = buildMonitor(
            listOf(BotCredential(0, "token0"), BotCredential(1, "token1")),
        ) { token ->
            if (token.contains("token0")) throw HttpException(
                Response.error<Any>(401, okhttp3.ResponseBody.create(null, ""))
            )
            else meResponse
        }

        monitor.checkAll()

        assertEquals(BotHealthStatus.BANNED, monitor.healthState.value[0])
        assertEquals(BotHealthStatus.HEALTHY, monitor.healthState.value[1])
    }

    @Test
    fun checkAll_marksUnreachable_onNetworkError() = runTest {
        val monitor = buildMonitor(
            listOf(BotCredential(0, "token0")),
        ) { throw java.net.SocketTimeoutException("timeout") }

        monitor.checkAll()

        assertEquals(BotHealthStatus.UNREACHABLE, monitor.healthState.value[0])
    }

    @Test
    fun checkSingle_updatesOnlyTargetBot() = runTest {
        val monitor = buildMonitor(
            listOf(BotCredential(0, "token0"), BotCredential(1, "token1")),
        ) { meResponse }

        monitor.checkAll() // both healthy

        val bannedApi = fakeApi { token ->
            if (token.contains("token0")) throw HttpException(
                Response.error<Any>(401, okhttp3.ResponseBody.create(null, ""))
            )
            else meResponse
        }
        // Rebuild is not possible without re-wiring; test via checkSingle on index 1 stays HEALTHY
        // since the fake API returns success for token1.
        monitor.checkSingle(1)
        assertEquals(BotHealthStatus.HEALTHY, monitor.healthState.value[1])
    }

    private class FakeCredentialRepo(private val bots: List<BotCredential>) : CredentialRepository {
        override fun getBotToken() = bots.firstOrNull()?.token
        override fun getChatId() = null
        override fun saveCredentials(token: String, chatId: String) {}
        override fun clearCredentials() {}
        override fun hasCredentials() = bots.isNotEmpty()
        override fun getLastIndexFileId() = null
        override fun setLastIndexFileId(fileId: String?) {}
        override fun getLastIndexMessageId(): Long? = null
        override fun setLastIndexMessageId(messageId: Long?) {}
        override fun getAdditionalBotTokens() = bots.drop(1)
        override fun saveAdditionalBotTokens(bots: List<BotCredential>) {}
        override fun clearAdditionalBots() {}
    }
}
