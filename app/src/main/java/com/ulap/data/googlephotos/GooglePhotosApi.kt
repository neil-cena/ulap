package com.ulap.data.googlephotos

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Marker header for [GooglePhotosApi.streamMedia] CDN requests.
 * The OkHttp client strips this and does **not** add `Authorization` (lh3.googleusercontent.com rejects Bearer on raw downloads).
 */
internal const val GOOGLE_PHOTOS_NO_AUTH_HEADER = "No-Auth"

interface GooglePhotosApi {
    @GET("mediaItems")
    suspend fun listMediaItems(
        @Query("pageSize") pageSize: Int = 50,
        @Query("pageToken") pageToken: String?,
    ): GooglePhotosMediaItemsResponse

    /**
     * Streams raw bytes from a Google Photos download URL (typically [GooglePhotosUrls.downloadVideoUrl] with `=dv`).
     * Use [ResponseBody.byteStream]; the caller must close the body.
     *
     * [GOOGLE_PHOTOS_NO_AUTH_HEADER] tells the client interceptor to omit Bearer auth for this call.
     */
    @Streaming
    @GET
    @Headers("${GOOGLE_PHOTOS_NO_AUTH_HEADER}: true")
    suspend fun streamMedia(@Url url: String): Response<ResponseBody>
}
