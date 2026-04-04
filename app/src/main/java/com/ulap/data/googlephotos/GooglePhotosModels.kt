package com.ulap.data.googlephotos

import com.google.gson.annotations.SerializedName

data class GooglePhotosMediaItemsResponse(
    @SerializedName("mediaItems") val mediaItems: List<GooglePhotosMediaItem>?,
    @SerializedName("nextPageToken") val nextPageToken: String?,
)

data class GooglePhotosMediaItem(
    @SerializedName("id") val id: String,
    @SerializedName("mimeType") val mimeType: String,
    @SerializedName("filename") val filename: String?,
    @SerializedName("baseUrl") val baseUrl: String,
    @SerializedName("mediaMetadata") val mediaMetadata: GooglePhotosMediaMetadata?,
)

data class GooglePhotosMediaMetadata(
    @SerializedName("creationTime") val creationTime: String?,
)
