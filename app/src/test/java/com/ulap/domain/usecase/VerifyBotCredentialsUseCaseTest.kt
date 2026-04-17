package com.ulap.domain.usecase

import com.ulap.data.remote.TelegramBotApi
import com.ulap.data.remote.TelegramChatInfo
import com.ulap.data.remote.TelegramChatMember
import com.ulap.data.remote.TelegramMe
import com.ulap.data.remote.TelegramResponse
import com.ulap.data.remote.TelegramResponseParameters
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import retrofit2.HttpException
import retrofit2.Response
import java.net.SocketTimeoutException

/**
 * BRT — VerifyBotCredentialsUseCase must run the full getMe → getChat → getChatMember
 * pipeline and return a typed VerifyResult without ever propagating exceptions.
 *
 * Each test corresponds to exactly one branch in the verification flowchart.
 */
class VerifyBotCredentialsUseCaseTest {

    private val api: TelegramBotApi = mock()

    private fun buildUseCase() = VerifyBotCredentialsUseCase(api)

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun okMe(id: Long = 42L, name: String = "MyBot") =
        TelegramResponse(ok = true, result = TelegramMe(id = id, username = "mybot", firstName = name), description = null, errorCode = null, parameters = null)

    private fun failMe() =
        TelegramResponse<TelegramMe>(ok = false, result = null, description = "Unauthorized", errorCode = 401, parameters = null)

    private fun okChat(type: String = "supergroup", id: Long = -100_999L) =
        TelegramResponse(ok = true, result = TelegramChatInfo(id = id, type = type, pinnedMessage = null), description = null, errorCode = null, parameters = null)

    private fun failChatNotFound() =
        TelegramResponse<TelegramChatInfo>(ok = false, result = null, description = "Bad Request: chat not found", errorCode = 400, parameters = null)

    private fun failChatMigrated(newId: Long) =
        TelegramResponse<TelegramChatInfo>(ok = false, result = null, description = "Bad Request: chat not found", errorCode = 400,
            parameters = TelegramResponseParameters(retryAfter = null, migrateToChatId = newId))

    private fun okMember(status: String) =
        TelegramResponse(ok = true, result = TelegramChatMember(status = status), description = null, errorCode = null, parameters = null)

    private fun http400(): HttpException =
        HttpException(Response.error<Any>(400, "Bad Request".toResponseBody()))

    // ── 1. getMe returns ok=false → InvalidToken ──────────────────────────────

    @Test
    fun invoke_whenGetMeReturnsFalse_returnsInvalidToken() = runTest {
        whenever(api.getMe(any())).thenReturn(failMe())

        val result = buildUseCase()("123:token", "-100999")

        assertTrue("Expected InvalidToken, got $result", result is VerifyResult.Error.InvalidToken)
    }

    // ── 2. getMe throws HttpException(401) → InvalidToken ─────────────────────

    @Test
    fun invoke_whenGetMeThrowsHttpException401_returnsInvalidToken_doesNotThrow() = runTest {
        whenever(api.getMe(any())).doSuspendableAnswer { throw http400() }

        val result = runCatching { buildUseCase()("123:token", "-100999") }

        assertTrue("must not throw", result.isSuccess)
        assertTrue("Expected InvalidToken, got ${result.getOrNull()}", result.getOrNull() is VerifyResult.Error.InvalidToken)
    }

    // ── 3. getMe throws SocketTimeoutException → Network ─────────────────────

    @Test
    fun invoke_whenGetMeThrowsSocketTimeout_returnsNetwork_doesNotThrow() = runTest {
        whenever(api.getMe(any())).doSuspendableAnswer { throw SocketTimeoutException("timeout") }

        val result = runCatching { buildUseCase()("123:token", "-100999") }

        assertTrue("must not throw", result.isSuccess)
        assertTrue("Expected Network, got ${result.getOrNull()}", result.getOrNull() is VerifyResult.Error.Network)
    }

    // ── 4. getChat returns chat-not-found, no migration param → ChatNotFound ──

    @Test
    fun invoke_whenGetChatReturnsNotFound_nomigrateParam_returnsChatNotFound() = runTest {
        whenever(api.getMe(any())).thenReturn(okMe())
        whenever(api.getChat(any(), any())).thenReturn(failChatNotFound())

        val result = buildUseCase()("123:token", "-100999")

        assertTrue("Expected ChatNotFound, got $result", result is VerifyResult.Error.ChatNotFound)
    }

    // ── 5. getChat returns migrate_to_chat_id, 2nd getChat ok, admin → Success ─

    @Test
    fun invoke_whenGetChatReturnsMigration_retriesWithNewId_andSucceedsAsAdmin() = runTest {
        whenever(api.getMe(any())).thenReturn(okMe(id = 42L))
        // First call returns migration hint, second call (with new id) returns ok
        var callCount = 0
        whenever(api.getChat(any(), any())).doSuspendableAnswer {
            if (callCount++ == 0) failChatMigrated(-100_123L) else okChat(type = "supergroup", id = -100_123L)
        }
        whenever(api.getChatMember(any(), any(), any())).thenReturn(okMember("administrator"))

        val result = buildUseCase()("123:token", "-999")

        assertTrue("Expected Success, got $result", result is VerifyResult.Success)
        assertEquals("-100123", (result as VerifyResult.Success).correctedChatId)
    }

    // ── 6. getChat ok, type=private → PrivateChatNotAllowed (group required) ──

    @Test
    fun invoke_whenChatTypeIsPrivate_returnsPrivateChatNotAllowed() = runTest {
        whenever(api.getMe(any())).thenReturn(okMe())
        whenever(api.getChat(any(), any())).thenReturn(okChat(type = "private"))

        val result = buildUseCase()("123:token", "123456789")

        assertTrue(
            "Private chats must be rejected — users must use a group. Got: $result",
            result is VerifyResult.Error.PrivateChatNotAllowed,
        )
        verify(api, never()).getChatMember(any(), any(), any())
    }

    // ── 7. getChatMember status=member → BotNotAdmin ──────────────────────────

    @Test
    fun invoke_whenBotIsRegularMember_returnsBotNotAdmin() = runTest {
        whenever(api.getMe(any())).thenReturn(okMe())
        whenever(api.getChat(any(), any())).thenReturn(okChat(type = "supergroup"))
        whenever(api.getChatMember(any(), any(), any())).thenReturn(okMember("member"))

        val result = buildUseCase()("123:token", "-100999")

        assertTrue("Expected BotNotAdmin, got $result", result is VerifyResult.Error.BotNotAdmin)
    }

    // ── 8. getChatMember status=restricted → BotNotAdmin ─────────────────────

    @Test
    fun invoke_whenBotIsRestricted_returnsBotNotAdmin() = runTest {
        whenever(api.getMe(any())).thenReturn(okMe())
        whenever(api.getChat(any(), any())).thenReturn(okChat(type = "supergroup"))
        whenever(api.getChatMember(any(), any(), any())).thenReturn(okMember("restricted"))

        val result = buildUseCase()("123:token", "-100999")

        assertTrue("Expected BotNotAdmin, got $result", result is VerifyResult.Error.BotNotAdmin)
    }

    // ── 9. getChatMember status=administrator in a group → Success ────────────

    @Test
    fun invoke_whenBotIsAdminInGroup_returnsSuccess() = runTest {
        whenever(api.getMe(any())).thenReturn(okMe(name = "GroupBot"))
        whenever(api.getChat(any(), any())).thenReturn(okChat(type = "group"))
        whenever(api.getChatMember(any(), any(), any())).thenReturn(okMember("administrator"))

        val result = buildUseCase()("123:token", "-999")

        assertTrue("Expected Success, got $result", result is VerifyResult.Success)
        assertEquals("GroupBot", (result as VerifyResult.Success).botName)
    }

    // ── 10. getChatMember status=creator in a channel → Success ──────────────

    @Test
    fun invoke_whenBotIsCreatorInChannel_returnsSuccess() = runTest {
        whenever(api.getMe(any())).thenReturn(okMe(name = "ChannelBot"))
        whenever(api.getChat(any(), any())).thenReturn(okChat(type = "channel"))
        whenever(api.getChatMember(any(), any(), any())).thenReturn(okMember("creator"))

        val result = buildUseCase()("123:token", "-100999")

        assertTrue("Expected Success, got $result", result is VerifyResult.Success)
    }

    // ── 11. getChat throws HttpException → Network, no propagation ────────────

    @Test
    fun invoke_whenGetChatThrowsHttpException_returnsNetwork_doesNotThrow() = runTest {
        whenever(api.getMe(any())).thenReturn(okMe())
        whenever(api.getChat(any(), any())).doSuspendableAnswer { throw http400() }

        val result = runCatching { buildUseCase()("123:token", "-100999") }

        assertTrue("must not throw", result.isSuccess)
        assertTrue("Expected Network, got ${result.getOrNull()}", result.getOrNull() is VerifyResult.Error.Network)
    }

    // ── 12. getChatMember throws IOException → Network, no propagation ────────

    @Test
    fun invoke_whenGetChatMemberThrowsIOException_returnsNetwork_doesNotThrow() = runTest {
        whenever(api.getMe(any())).thenReturn(okMe())
        whenever(api.getChat(any(), any())).thenReturn(okChat(type = "supergroup"))
        whenever(api.getChatMember(any(), any(), any())).doSuspendableAnswer { throw java.io.IOException("reset") }

        val result = runCatching { buildUseCase()("123:token", "-100999") }

        assertTrue("must not throw", result.isSuccess)
        assertTrue("Expected Network, got ${result.getOrNull()}", result.getOrNull() is VerifyResult.Error.Network)
    }

    // ── 13. getChatMember status=kicked → BotKicked ───────────────────────────

    @Test
    fun invoke_whenBotIsKicked_returnsBotKicked() = runTest {
        whenever(api.getMe(any())).thenReturn(okMe())
        whenever(api.getChat(any(), any())).thenReturn(okChat(type = "supergroup"))
        whenever(api.getChatMember(any(), any(), any())).thenReturn(okMember("kicked"))

        val result = buildUseCase()("123:token", "-100999")

        assertTrue("Expected BotKicked, got $result", result is VerifyResult.Error.BotKicked)
    }

    // ── 14. getChatMember status=left → BotKicked ─────────────────────────────

    @Test
    fun invoke_whenBotHasLeft_returnsBotKicked() = runTest {
        whenever(api.getMe(any())).thenReturn(okMe())
        whenever(api.getChat(any(), any())).thenReturn(okChat(type = "supergroup"))
        whenever(api.getChatMember(any(), any(), any())).thenReturn(okMember("left"))

        val result = buildUseCase()("123:token", "-100999")

        assertTrue("Expected BotKicked, got $result", result is VerifyResult.Error.BotKicked)
    }
}
