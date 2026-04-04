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
    /** Omitted or blank while Google is still processing the item. */
    @SerializedName("baseUrl") val baseUrl: String? = null,
    @SerializedName("mediaMetadata") val mediaMetadata: GooglePhotosMediaMetadata?,
)

data class GooglePhotosMediaMetadata(
    @SerializedName("creationTime") val creationTime: String?,
    /** Pixel width; API returns a string (e.g. `"4032"`). */
    @SerializedName("width") val width: String? = null,
    /** Pixel height; API returns a string (e.g. `"3024"`). */
    @SerializedName("height") val height: String? = null,
)
