package com.ulap.data.googlephotos

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

interface GooglePhotosPickerApi {

    /**
     * Creates a new picker session. Returns a [PickerSession] with a [PickerSession.pickerUri]
     * the user must open in Google Photos to select media items.
     *
     * Requires an empty JSON body per the Picker API spec.
     */
    @POST("sessions")
    suspend fun createSession(@Body body: RequestBody): PickerSession

    /**
     * Returns the current state of a session. Poll this until [PickerSession.mediaItemsSet] is
     * true, using [PickerSession.pollingConfig] for recommended intervals.
     */
    @GET("sessions/{sessionId}")
    suspend fun getSession(@Path("sessionId") sessionId: String): PickerSession

    /**
     * Deletes a session after import is complete. Best-effort — caller should swallow errors.
     */
    @DELETE("sessions/{sessionId}")
    suspend fun deleteSession(@Path("sessionId") sessionId: String): Response<ResponseBody>

    /**
     * Lists media items selected by the user in the given session.
     * Paginate using [pageToken] until [PickedMediaItemsResponse.nextPageToken] is null.
     */
    @GET("mediaItems")
    suspend fun listMediaItems(
        @Query("sessionId") sessionId: String,
        @Query("pageSize") pageSize: Int = 100,
        @Query("pageToken") pageToken: String? = null,
    ): PickedMediaItemsResponse

    /**
     * Returns a single media item with a fresh [PickedMediaFile.baseUrl].
     * Used to re-fetch an expired baseUrl without re-paging the full session.
     */
    @GET("mediaItems/{mediaItemId}")
    suspend fun getMediaItem(
        @Path("mediaItemId") mediaItemId: String,
        @Query("sessionId") sessionId: String,
    ): PickedMediaItem

    /**
     * Streams raw bytes from a Picker API media URL (e.g. [PickedMediaFile.baseUrl] with `=d` or
     * `=dv` suffix). Unlike the old Library API CDN, Picker API base URLs require a valid
     * Authorization header — which this client's interceptor always provides.
     */
    @Streaming
    @GET
    suspend fun streamMedia(@Url url: String): Response<ResponseBody>
}
