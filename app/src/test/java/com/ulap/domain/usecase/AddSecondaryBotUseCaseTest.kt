package com.ulap.domain.usecase

import com.ulap.data.remote.BotBanStore
import com.ulap.data.remote.BotPool
import com.ulap.data.remote.TelegramBotApi
import com.ulap.data.remote.TelegramChatInfo
import com.ulap.data.remote.TelegramChatMember
import com.ulap.data.remote.TelegramMe
import com.ulap.data.remote.TelegramResponse
import com.ulap.domain.model.BotCredential
import com.ulap.domain.repository.CredentialRepository
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import retrofit2.Response
import java.net.SocketTimeoutException

/**
 * BRT — AddSecondaryBotUseCase must verify chat access and admin status
 * against the saved primary chat_id before persisting a new secondary bot.
 */
class AddSecondaryBotUseCaseTest {

    private val api: TelegramBotApi = mock()

    private fun buildRepo(chatId: String = "-100999", primaryToken: String = "primary:token"): CredentialRepository =
        object : CredentialRepository {
            private val additionals = mutableListOf<BotCredential>()
            override fun getBotToken() = primaryToken
            override fun getChatId() = chatId
            override fun saveCredentials(token: String, chatId: String) {}
            override fun clearCredentials() {}
            override fun hasCredentials() = true
            override fun getLastIndexFileId() = null
            override fun setLastIndexFileId(fileId: String?) {}
            override fun getAdditionalBotTokens(): List<BotCredential> = additionals.toList()
            override fun saveAdditionalBotTokens(bots: List<BotCredential>) { additionals.clear(); additionals.addAll(bots) }
            override fun clearAdditionalBots() { additionals.clear() }
        }

    private fun buildUseCase(chatId: String = "-100999"): AddSecondaryBotUseCase {
        val repo = buildRepo(chatId)
        val pool = BotPool(repo, BotBanStore.noOpForTest())
        return AddSecondaryBotUseCase(api, repo, pool)
    }

    private fun okMe(id: Long = 77L, name: String = "SecondaryBot") =
        TelegramResponse(ok = true, result = TelegramMe(id = id, username = "secbot", firstName = name), description = null, errorCode = null, parameters = null)

    private fun okChat(type: String = "supergroup") =
        TelegramResponse(ok = true, result = TelegramChatInfo(id = -100_999L, type = type, pinnedMessage = null), description = null, errorCode = null, parameters = null)

    private fun okMember(status: String) =
        TelegramResponse(ok = true, result = TelegramChatMember(status = status), description = null, errorCode = null, parameters = null)

    private fun failChatNotFound() =
        TelegramResponse<TelegramChatInfo>(ok = false, result = null, description = "Bad Request: chat not found", errorCode = 400, parameters = null)

    private fun http400(): HttpException =
        HttpException(Response.error<Any>(400, "Bad Request".toResponseBody()))

    // ── token check ───────────────────────────────────────────────────────────

    @Test
    fun addBot_whenGetMeReturnsFalse_returnsInvalidToken() = runTest {
        whenever(api.getMe(any())).thenReturn(
            TelegramResponse<TelegramMe>(ok = false, result = null, description = "Unauthorized", errorCode = 401, parameters = null)
        )

        val result = buildUseCase()("new:token", "label")

        assertTrue("Expected InvalidToken, got $result", result is VerifyResult.Error.InvalidToken)
    }

    // ── chat not found ────────────────────────────────────────────────────────

    @Test
    fun addBot_whenGetChatReturnsNotFound_returnsChatNotFound() = runTest {
        whenever(api.getMe(any())).thenReturn(okMe())
        whenever(api.getChat(any(), any())).thenReturn(failChatNotFound())

        val result = buildUseCase()("new:token", "label")

        assertTrue("Expected ChatNotFound, got $result", result is VerifyResult.Error.ChatNotFound)
    }

    // ── bot not admin ─────────────────────────────────────────────────────────

    @Test
    fun addBot_whenBotIsRegularMember_returnsBotNotAdmin_doesNotPersist() = runTest {
        val repo = buildRepo()
        val pool = BotPool(repo, BotBanStore.noOpForTest())
        val useCase = AddSecondaryBotUseCase(api, repo, pool)

        whenever(api.getMe(any())).thenReturn(okMe())
        whenever(api.getChat(any(), any())).thenReturn(okChat(type = "supergroup"))
        whenever(api.getChatMember(any(), any(), any())).thenReturn(okMember("member"))

        val result = useCase("new:token", "label")

        assertTrue("Expected BotNotAdmin, got $result", result is VerifyResult.Error.BotNotAdmin)
        assertTrue("Bot must not be persisted on BotNotAdmin", repo.getAdditionalBotTokens().isEmpty())
    }

    // ── bot kicked ────────────────────────────────────────────────────────────

    @Test
    fun addBot_whenBotIsKicked_returnsBotKicked() = runTest {
        whenever(api.getMe(any())).thenReturn(okMe())
        whenever(api.getChat(any(), any())).thenReturn(okChat(type = "supergroup"))
        whenever(api.getChatMember(any(), any(), any())).thenReturn(okMember("kicked"))

        val result = buildUseCase()("new:token", "label")

        assertTrue("Expected BotKicked, got $result", result is VerifyResult.Error.BotKicked)
    }

    // ── network error ─────────────────────────────────────────────────────────

    @Test
    fun addBot_whenGetChatThrowsHttpException_returnsNetwork_doesNotThrow() = runTest {
        whenever(api.getMe(any())).thenReturn(okMe())
        whenever(api.getChat(any(), any())).doSuspendableAnswer { throw http400() }

        val result = runCatching { buildUseCase()("new:token", "label") }

        assertTrue("must not throw", result.isSuccess)
        assertTrue("Expected Network, got ${result.getOrNull()}", result.getOrNull() is VerifyResult.Error.Network)
    }

    @Test
    fun addBot_whenGetMeThrowsSocketTimeout_returnsNetwork_doesNotThrow() = runTest {
        whenever(api.getMe(any())).doSuspendableAnswer { throw SocketTimeoutException("timeout") }

        val result = runCatching { buildUseCase()("new:token", "label") }

        assertTrue("must not throw", result.isSuccess)
        assertTrue("Expected Network, got ${result.getOrNull()}", result.getOrNull() is VerifyResult.Error.Network)
    }

    // ── happy path ────────────────────────────────────────────────────────────

    @Test
    fun addBot_whenAdminInSupergroup_returnsSuccessAndPersists() = runTest {
        val repo = buildRepo()
        val pool = BotPool(repo, BotBanStore.noOpForTest())
        val useCase = AddSecondaryBotUseCase(api, repo, pool)

        whenever(api.getMe(any())).thenReturn(okMe(name = "NewBot"))
        whenever(api.getChat(any(), any())).thenReturn(okChat(type = "supergroup"))
        whenever(api.getChatMember(any(), any(), any())).thenReturn(okMember("administrator"))

        val result = useCase("new:token", "My Bot")

        assertTrue("Expected Success, got $result", result is VerifyResult.Success)
        assertEquals("NewBot", (result as VerifyResult.Success).botName)
        assertEquals(1, repo.getAdditionalBotTokens().size)
    }

    @Test
    fun addBot_whenPrivateChat_noAdminCheckRequired_returnsSuccess() = runTest {
        whenever(api.getMe(any())).thenReturn(okMe(name = "PrivateBot"))
        whenever(api.getChat(any(), any())).thenReturn(okChat(type = "private"))

        val result = buildUseCase()("new:token", "Private Bot")

        assertTrue("Expected Success, got $result", result is VerifyResult.Success)
    }
}
