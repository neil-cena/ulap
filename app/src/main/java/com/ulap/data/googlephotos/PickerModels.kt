package com.ulap.data.googlephotos

import com.google.gson.annotations.SerializedName

data class PickerSession(
    @SerializedName("id") val id: String,
    @SerializedName("pickerUri") val pickerUri: String,
    @SerializedName("pollingConfig") val pollingConfig: PollingConfig?,
    @SerializedName("mediaItemsSet") val mediaItemsSet: Boolean = false,
)

data class PollingConfig(
    @SerializedName("pollInterval") val pollInterval: String?,
    @SerializedName("timeoutIn") val timeoutIn: String?,
)

/** Parse a Google API Duration string like "5s" into milliseconds. */
fun PollingConfig?.pollIntervalMs(default: Long = 5_000L): Long {
    val raw = this?.pollInterval ?: return default
    return raw.trimEnd('s').toLongOrNull()?.times(1_000L) ?: default
}

/** Parse a Google API Duration string like "600s" into milliseconds. */
fun PollingConfig?.timeoutMs(default: Long = 600_000L): Long {
    val raw = this?.timeoutIn ?: return default
    return raw.trimEnd('s').toLongOrNull()?.times(1_000L) ?: default
}

data class PickedMediaItemsResponse(
    @SerializedName("mediaItems") val mediaItems: List<PickedMediaItem>?,
    @SerializedName("nextPageToken") val nextPageToken: String?,
)

data class PickedMediaItem(
    @SerializedName("id") val id: String,
    /** "PHOTO" or "VIDEO" */
    @SerializedName("type") val type: String,
    @SerializedName("mediaFile") val mediaFile: PickedMediaFile,
    @SerializedName("createTime") val createTime: String?,
)

data class PickedMediaFile(
    @SerializedName("baseUrl") val baseUrl: String,
    @SerializedName("mimeType") val mimeType: String,
    @SerializedName("filename") val filename: String?,
    @SerializedName("mediaFileMetadata") val mediaFileMetadata: PickedMediaFileMetadata?,
)

data class PickedMediaFileMetadata(
    @SerializedName("width") val width: Int?,
    @SerializedName("height") val height: Int?,
    @SerializedName("cameraMake") val cameraMake: String?,
    @SerializedName("cameraModel") val cameraModel: String?,
)

/**
 * Converts a [PickedMediaItem] from the Picker API to a [GooglePhotosMediaItem] so the
 * existing import pipeline ([GooglePhotosImportManager], [GooglePhotosImportEntityFactory]) can
 * process it without modification.
 *
 * Pixel dimensions are stored as strings to match the Library API's REST response format.
 */
fun PickedMediaItem.toGooglePhotosMediaItem(): GooglePhotosMediaItem = GooglePhotosMediaItem(
    id = id,
    mimeType = mediaFile.mimeType,
    filename = mediaFile.filename,
    baseUrl = mediaFile.baseUrl,
    mediaMetadata = GooglePhotosMediaMetadata(
        creationTime = createTime,
        width = mediaFile.mediaFileMetadata?.width?.toString(),
        height = mediaFile.mediaFileMetadata?.height?.toString(),
    ),
)
