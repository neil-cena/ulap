package com.ulap.data.remote

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

fun sanitizeTokenForPath(token: String): String =
    token.trim().replace(":", "%3A")

interface TelegramBotApi {

    @GET("bot{token}/getMe")
    suspend fun getMe(
        @Path(value = "token", encoded = true) token: String,
    ): TelegramResponse<TelegramMe>

    @Multipart
    @POST("bot{token}/sendPhoto")
    suspend fun sendPhoto(
        @Path(value = "token", encoded = true) token: String,
        @Part("chat_id") chatId: RequestBody,
        @Part photo: MultipartBody.Part,
        @Part("caption") caption: RequestBody? = null,
    ): TelegramResponse<TelegramMessage>

    @Multipart
    @POST("bot{token}/sendVideo")
    suspend fun sendVideo(
        @Path(value = "token", encoded = true) token: String,
        @Part("chat_id") chatId: RequestBody,
        @Part video: MultipartBody.Part,
        @Part("caption") caption: RequestBody? = null,
        @Part("supports_streaming") supportsStreaming: RequestBody? = null,
    ): TelegramResponse<TelegramMessage>

    @Multipart
    @POST("bot{token}/sendDocument")
    suspend fun sendDocument(
        @Path(value = "token", encoded = true) token: String,
        @Part("chat_id") chatId: RequestBody,
        @Part document: MultipartBody.Part,
        @Part("caption") caption: RequestBody? = null,
    ): TelegramResponse<TelegramMessage>

    @GET("bot{token}/getFile")
    suspend fun getFile(
        @Path(value = "token", encoded = true) token: String,
        @Query("file_id") fileId: String,
    ): TelegramResponse<TelegramFile>

    @POST("bot{token}/deleteMessage")
    suspend fun deleteMessage(
        @Path(value = "token", encoded = true) token: String,
        @Query("chat_id") chatId: String,
        @Query("message_id") messageId: Long,
    ): TelegramResponse<Boolean>

    /** Used to obtain current [TelegramDocument.fileId] for an existing chat message (repair path). */
    @FormUrlEncoded
    @POST("bot{token}/forwardMessage")
    suspend fun forwardMessage(
        @Path(value = "token", encoded = true) token: String,
        @Field("chat_id") chatId: String,
        @Field("from_chat_id") fromChatId: String,
        @Field("message_id") messageId: Long,
    ): TelegramResponse<TelegramMessage>

    @FormUrlEncoded
    @POST("bot{token}/deleteMessages")
    suspend fun deleteMessages(
        @Path(value = "token", encoded = true) token: String,
        @Field("chat_id") chatId: String,
        @Field("message_ids") messageIdsJson: String,
    ): TelegramResponse<Boolean>

    @GET("bot{token}/getUpdates")
    suspend fun getUpdates(
        @Path(value = "token", encoded = true) token: String,
        @Query("offset") offset: Int? = null,
        @Query("limit") limit: Int? = null,
    ): TelegramResponse<List<TelegramUpdate>>

    @GET("bot{token}/getChat")
    suspend fun getChat(
        @Path(value = "token", encoded = true) token: String,
        @Query("chat_id") chatId: String,
    ): TelegramResponse<TelegramChatInfo>

    @POST("bot{token}/pinChatMessage")
    suspend fun pinChatMessage(
        @Path(value = "token", encoded = true) token: String,
        @Query("chat_id") chatId: String,
        @Query("message_id") messageId: Long,
        @Query("disable_notification") disableNotification: Boolean = true,
    ): TelegramResponse<Boolean>
}
