package com.ulap.data.googlephotos

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PickerModelsTest {

    private val gson = Gson()

    // ---- PickerSession ----

    @Test
    fun `picker session deserializes all fields`() {
        val json = """
            {
              "id": "session-id-abc",
              "pickerUri": "https://photos.google.com/picker?session=abc",
              "pollingConfig": {
                "pollInterval": "5s",
                "timeoutIn": "600s"
              },
              "mediaItemsSet": false
            }
        """.trimIndent()
        val session = gson.fromJson(json, PickerSession::class.java)
        assertEquals("session-id-abc", session.id)
        assertEquals("https://photos.google.com/picker?session=abc", session.pickerUri)
        assertNotNull(session.pollingConfig)
        assertEquals("5s", session.pollingConfig?.pollInterval)
        assertEquals("600s", session.pollingConfig?.timeoutIn)
        assertFalse(session.mediaItemsSet)
    }

    @Test
    fun `picker session mediaItemsSet true after user selection`() {
        val json = """{"id":"s1","pickerUri":"https://p.g","mediaItemsSet":true}"""
        val session = gson.fromJson(json, PickerSession::class.java)
        assertTrue(session.mediaItemsSet)
    }

    @Test
    fun `picker session mediaItemsSet defaults to false when absent`() {
        val json = """{"id":"s1","pickerUri":"https://p.g"}"""
        val session = gson.fromJson(json, PickerSession::class.java)
        assertFalse(session.mediaItemsSet)
    }

    @Test
    fun `picker session pollingConfig absent is null`() {
        val json = """{"id":"s1","pickerUri":"https://p.g","mediaItemsSet":false}"""
        val session = gson.fromJson(json, PickerSession::class.java)
        assertNull(session.pollingConfig)
    }

    // ---- PollingConfig helpers ----

    @Test
    fun `pollIntervalMs parses seconds string correctly`() {
        val config = PollingConfig(pollInterval = "5s", timeoutIn = "600s")
        assertEquals(5_000L, config.pollIntervalMs())
    }

    @Test
    fun `pollIntervalMs returns default when null`() {
        val config = PollingConfig(pollInterval = null, timeoutIn = null)
        assertEquals(5_000L, config.pollIntervalMs())
    }

    @Test
    fun `pollIntervalMs returns default when unparseable`() {
        val config = PollingConfig(pollInterval = "PT5S", timeoutIn = null)
        assertEquals(5_000L, config.pollIntervalMs())
    }

    @Test
    fun `timeoutMs parses seconds string correctly`() {
        val config = PollingConfig(pollInterval = "5s", timeoutIn = "600s")
        assertEquals(600_000L, config.timeoutMs())
    }

    @Test
    fun `timeoutMs returns default when null`() {
        val config = PollingConfig(pollInterval = null, timeoutIn = null)
        assertEquals(600_000L, config.timeoutMs())
    }

    // ---- PickedMediaItemsResponse ----

    @Test
    fun `picked media items response deserializes correctly`() {
        val json = """
            {
              "mediaItems": [
                {
                  "id": "item-1",
                  "type": "PHOTO",
                  "createTime": "2023-01-15T10:30:00Z",
                  "mediaFile": {
                    "baseUrl": "https://lh3.googleusercontent.com/abc",
                    "mimeType": "image/jpeg",
                    "filename": "IMG_001.jpg",
                    "mediaFileMetadata": {
                      "width": 4032,
                      "height": 3024
                    }
                  }
                }
              ],
              "nextPageToken": "token-xyz"
            }
        """.trimIndent()
        val response = gson.fromJson(json, PickedMediaItemsResponse::class.java)
        assertEquals(1, response.mediaItems?.size)
        assertEquals("token-xyz", response.nextPageToken)
        val item = response.mediaItems!![0]
        assertEquals("item-1", item.id)
        assertEquals("PHOTO", item.type)
        assertEquals("2023-01-15T10:30:00Z", item.createTime)
        assertEquals("https://lh3.googleusercontent.com/abc", item.mediaFile.baseUrl)
        assertEquals("image/jpeg", item.mediaFile.mimeType)
        assertEquals("IMG_001.jpg", item.mediaFile.filename)
        assertEquals(4032, item.mediaFile.mediaFileMetadata?.width)
        assertEquals(3024, item.mediaFile.mediaFileMetadata?.height)
    }

    @Test
    fun `picked media items response handles null mediaItems`() {
        val json = """{"nextPageToken":null}"""
        val response = gson.fromJson(json, PickedMediaItemsResponse::class.java)
        assertNull(response.mediaItems)
        assertNull(response.nextPageToken)
    }

    @Test
    fun `picked media item with no filename deserializes correctly`() {
        val json = """
            {
              "id": "vid-1",
              "type": "VIDEO",
              "mediaFile": {
                "baseUrl": "https://lh3.googleusercontent.com/vid",
                "mimeType": "video/mp4"
              }
            }
        """.trimIndent()
        val item = gson.fromJson(json, PickedMediaItem::class.java)
        assertEquals("VIDEO", item.type)
        assertNull(item.mediaFile.filename)
        assertNull(item.mediaFile.mediaFileMetadata)
    }
}
