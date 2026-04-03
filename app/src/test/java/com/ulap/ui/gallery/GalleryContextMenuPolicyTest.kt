package com.ulap.ui.gallery

import com.ulap.domain.model.BackupStatus
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryContextMenuPolicyTest {

    private fun item(
        contentUri: String = "",
        backupStatus: BackupStatus = BackupStatus.PENDING,
        telegramFileId: String? = null,
        streamUrl: String? = null,
        fileName: String = "f.jpg",
        errorMessage: String? = null,
    ): MediaItem =
        MediaItem(
            id = "id",
            path = "",
            contentUri = contentUri,
            fileName = fileName,
            mimeType = "image/jpeg",
            size = 0L,
            dateModified = 0L,
            dateTaken = 0L,
            bucketName = "",
            mediaType = MediaType.IMAGE,
            durationMs = null,
            backupStatus = backupStatus,
            telegramFileId = telegramFileId,
            streamUrl = streamUrl,
            errorMessage = errorMessage,
        )

    @Test
    fun contextMenuVisibility_truthTable() {
        data class Row(
            val u: Boolean,
            val c: Boolean,
            val t: Boolean,
            val expectRemove: Boolean,
            val expectDownload: Boolean,
        )
        val rows =
            listOf(
                Row(false, false, false, false, false),
                Row(false, false, true, false, false),
                Row(false, true, false, false, false),
                Row(false, true, true, false, true),
                Row(true, false, false, true, false),
                Row(true, false, true, true, false),
                Row(true, true, false, false, false),
                Row(true, true, true, false, true),
            )
        for (r in rows) {
            val uri = if (r.u) "content://media/1" else ""
            val status = if (r.c) BackupStatus.CLOUD_ONLY else BackupStatus.BACKED_UP
            val tid = if (r.t) "tg_file" else null
            val v = GalleryContextMenuPolicy.contextMenuVisibility(item(uri, status, tid))
            assertTrue(v.showInfo)
            assertTrue(v.showShare)
            assertEquals("remove U=${r.u} C=${r.c} T=${r.t}", r.expectRemove, v.showRemoveFromDevice)
            assertEquals("download U=${r.u} C=${r.c} T=${r.t}", r.expectDownload, v.showDownload)
            assertFalse(v.showRemoveFromDevice && v.showDownload)
        }
    }

    @Test
    fun removeAndDownload_neverBothTrue_forAllBackupStatuses() {
        val uriBlank = item("", BackupStatus.PENDING, null)
        val uriSet = item("content://x", BackupStatus.PENDING, null)
        for (status in BackupStatus.entries) {
            for (base in listOf(uriBlank, uriSet)) {
                val it = base.copy(backupStatus = status, telegramFileId = if (status == BackupStatus.CLOUD_ONLY) "tid" else null)
                val v = GalleryContextMenuPolicy.contextMenuVisibility(it)
                assertFalse(v.showRemoveFromDevice && v.showDownload)
            }
        }
    }

    @Test
    fun infoMetadataLines_neverExpose_streamUrl_telegramHost_orBotTokenPath() {
        val toxic = "https://api.telegram.org/bot123456:FAKE_TOKEN_HERE/getFile?file_id=abc"
        val stream = "https://evil.example/leak?x=1"
        val lines =
            GalleryContextMenuPolicy.infoMetadataLinesForDisplay(
                item(
                    contentUri = "content://local/1",
                    backupStatus = BackupStatus.BACKED_UP,
                    streamUrl = stream,
                    telegramFileId = null,
                    errorMessage = toxic,
                ),
            )
        for (line in lines) {
            assertFalse(line.contains(stream))
            assertFalse(line.contains("api.telegram.org", ignoreCase = true))
            assertFalse(Regex("""/bot\d+:[A-Za-z0-9_-]+/""").containsMatchIn(line))
        }
    }
}
