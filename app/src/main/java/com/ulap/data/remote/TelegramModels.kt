package com.ulap.data.remote

import com.google.gson.annotations.SerializedName

data class TelegramResponse<T>(
    @SerializedName("ok") val ok: Boolean,
    @SerializedName("result") val result: T?,
    @SerializedName("description") val description: String?,
    @SerializedName("error_code") val errorCode: Int?,
    @SerializedName("parameters") val parameters: TelegramResponseParameters?,
)

data class TelegramResponseParameters(
    @SerializedName("retry_after") val retryAfter: Int?,
    @SerializedName("migrate_to_chat_id") val migrateToChatId: Long?,
)

data class TelegramMessage(
    @SerializedName("message_id") val messageId: Long,
    @SerializedName("document") val document: TelegramDocument?,
    @SerializedName("video") val video: TelegramVideo?,
    @SerializedName("photo") val photo: List<TelegramPhotoSize>?,
    @SerializedName("caption") val caption: String?,
)

/** Best-effort file id for a photo message (Telegram returns multiple sizes; largest preferred). */
fun TelegramMessage.largestPhotoFileId(): String? {
    val photos = photo ?: return null
    if (photos.isEmpty()) return null
    return photos.maxByOrNull { it.width * it.height }?.fileId ?: photos.last().fileId
}

data class TelegramDocument(
    @SerializedName("file_id") val fileId: String,
    @SerializedName("file_size") val fileSize: Long?,
    @SerializedName("file_name") val fileName: String?,
    @SerializedName("mime_type") val mimeType: String?,
    @SerializedName("thumbnail") val thumbnail: TelegramPhotoSize?,
)

data class TelegramVideo(
    @SerializedName("file_id") val fileId: String,
    @SerializedName("file_size") val fileSize: Long?,
    @SerializedName("file_name") val fileName: String?,
    @SerializedName("mime_type") val mimeType: String?,
    @SerializedName("duration") val duration: Int,
    @SerializedName("thumbnail") val thumbnail: TelegramPhotoSize?,
)

data class TelegramPhotoSize(
    @SerializedName("file_id") val fileId: String,
    @SerializedName("file_size") val fileSize: Long?,
    @SerializedName("width") val width: Int,
    @SerializedName("height") val height: Int,
)

data class TelegramFile(
    @SerializedName("file_id") val fileId: String,
    @SerializedName("file_path") val filePath: String?,
    @SerializedName("file_size") val fileSize: Long?,
)

data class TelegramMe(
    @SerializedName("id") val id: Long,
    @SerializedName("username") val username: String?,
    @SerializedName("first_name") val firstName: String,
)

data class TelegramUpdate(
    @SerializedName("update_id") val updateId: Long,
    @SerializedName("message") val message: TelegramMessage?,
)

data class TelegramChatInfo(
    @SerializedName("id") val id: Long,
    @SerializedName("pinned_message") val pinnedMessage: TelegramMessage?,
)
