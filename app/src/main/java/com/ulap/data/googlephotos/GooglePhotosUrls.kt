package com.ulap.data.googlephotos

/**
 * Google Photos [mediaItem.baseUrl](https://developers.google.com/photos/library/reference/rest/v1/mediaItems)
 * accepts size modifiers; `=d` requests the full-resolution still image.
 */
object GooglePhotosUrls {
    fun fullResolutionImageUrl(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd()
        return if (trimmed.endsWith("=d")) trimmed else "$trimmed=d"
    }

    /** Download URL for video bytes (`=dv` modifier per Google Photos baseUrl semantics). */
    fun downloadVideoUrl(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd()
        return if (trimmed.endsWith("=dv")) trimmed else "$trimmed=dv"
    }
}
