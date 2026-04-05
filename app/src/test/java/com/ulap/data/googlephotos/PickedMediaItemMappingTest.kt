package com.ulap.data.googlephotos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PickedMediaItemMappingTest {

    private fun makeImageItem(
        id: String = "item-1",
        filename: String? = "photo.jpg",
        baseUrl: String = "https://lh3.googleusercontent.com/abc",
        mimeType: String = "image/jpeg",
        width: Int? = 4032,
        height: Int? = 3024,
        createTime: String? = "2023-06-01T12:00:00Z",
    ) = PickedMediaItem(
        id = id,
        type = "PHOTO",
        createTime = createTime,
        mediaFile = PickedMediaFile(
            baseUrl = baseUrl,
            mimeType = mimeType,
            filename = filename,
            mediaFileMetadata = if (width != null || height != null) {
                PickedMediaFileMetadata(
                    width = width,
                    height = height,
                    cameraMake = null,
                    cameraModel = null,
                )
            } else {
                null
            },
        ),
    )

    @Test
    fun `toGooglePhotosMediaItem maps id correctly`() {
        val item = makeImageItem(id = "abc-123")
        assertEquals("abc-123", item.toGooglePhotosMediaItem().id)
    }

    @Test
    fun `toGooglePhotosMediaItem maps mimeType from mediaFile`() {
        val item = makeImageItem(mimeType = "image/heic")
        assertEquals("image/heic", item.toGooglePhotosMediaItem().mimeType)
    }

    @Test
    fun `toGooglePhotosMediaItem maps filename from mediaFile`() {
        val item = makeImageItem(filename = "sunset.jpg")
        assertEquals("sunset.jpg", item.toGooglePhotosMediaItem().filename)
    }

    @Test
    fun `toGooglePhotosMediaItem maps null filename`() {
        val item = makeImageItem(filename = null)
        assertNull(item.toGooglePhotosMediaItem().filename)
    }

    @Test
    fun `toGooglePhotosMediaItem maps baseUrl from mediaFile`() {
        val item = makeImageItem(baseUrl = "https://lh3.googleusercontent.com/xyz")
        assertEquals("https://lh3.googleusercontent.com/xyz", item.toGooglePhotosMediaItem().baseUrl)
    }

    @Test
    fun `toGooglePhotosMediaItem maps creationTime from createTime`() {
        val item = makeImageItem(createTime = "2023-06-01T12:00:00Z")
        assertEquals("2023-06-01T12:00:00Z", item.toGooglePhotosMediaItem().mediaMetadata?.creationTime)
    }

    @Test
    fun `toGooglePhotosMediaItem maps null createTime`() {
        val item = makeImageItem(createTime = null)
        assertNull(item.toGooglePhotosMediaItem().mediaMetadata?.creationTime)
    }

    @Test
    fun `toGooglePhotosMediaItem maps pixel dimensions as strings`() {
        val item = makeImageItem(width = 4032, height = 3024)
        val media = item.toGooglePhotosMediaItem()
        assertEquals("4032", media.mediaMetadata?.width)
        assertEquals("3024", media.mediaMetadata?.height)
    }

    @Test
    fun `toGooglePhotosMediaItem maps null pixel dimensions`() {
        val item = makeImageItem(width = null, height = null)
        val meta = item.toGooglePhotosMediaItem().mediaMetadata
        assertNull(meta?.width)
        assertNull(meta?.height)
    }

    @Test
    fun `toGooglePhotosMediaItem works for video items`() {
        val item = PickedMediaItem(
            id = "vid-1",
            type = "VIDEO",
            createTime = null,
            mediaFile = PickedMediaFile(
                baseUrl = "https://lh3.googleusercontent.com/vid",
                mimeType = "video/mp4",
                filename = "clip.mp4",
                mediaFileMetadata = null,
            ),
        )
        val mapped = item.toGooglePhotosMediaItem()
        assertEquals("vid-1", mapped.id)
        assertEquals("video/mp4", mapped.mimeType)
        assertEquals("clip.mp4", mapped.filename)
    }
}
