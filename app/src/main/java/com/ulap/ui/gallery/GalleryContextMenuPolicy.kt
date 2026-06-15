package com.ulap.ui.gallery

import com.ulap.domain.model.BackupStatus
import com.ulap.domain.model.MediaItem

data class GalleryContextMenuVisibility(
    val showInfo: Boolean,
    val showShare: Boolean,
    val showRemoveFromDevice: Boolean,
    val showDownload: Boolean,
    val showDelete: Boolean,
)

object GalleryContextMenuPolicy {
    private val botTokenPathRegex = Regex("""/bot\d+:[A-Za-z0-9_-]+/""")

    fun contextMenuVisibility(item: MediaItem): GalleryContextMenuVisibility {
        val contentUriNonBlank = item.contentUri.trim().isNotEmpty()
        val cloudOnly = item.backupStatus == BackupStatus.CLOUD_ONLY
        val telegramIdNonBlank = item.telegramFileId?.trim()?.isNotEmpty() == true
        val hasMessageId = item.telegramMessageId != null && item.telegramMessageId != 0L
        return GalleryContextMenuVisibility(
            showInfo = true,
            showShare = true,
            showRemoveFromDevice = contentUriNonBlank && !cloudOnly,
            showDownload = cloudOnly && telegramIdNonBlank,
            showDelete = hasMessageId,
        )
    }

    fun infoMetadataLinesForDisplay(item: MediaItem): List<String> {
        val candidates = mutableListOf<String>()
        if (item.fileName.isNotBlank()) {
            candidates += item.fileName
        }
        item.mimeType.takeIf { it.isNotBlank() }?.let { candidates += it }
        candidates += "Status: ${item.backupStatus.name}"
        if (item.size > 0L) {
            candidates += "Size: ${item.size} B"
        }
        item.errorMessage?.trim()?.takeIf { it.isNotEmpty() }?.let { candidates += it }
        return candidates.mapNotNull { line -> sanitizeMetadataLine(line, item) }
    }

    /**
     * Drops or rejects lines that would expose [MediaItem.streamUrl], Telegram API hosts, or
     * bot-token URL path segments in the Info UI.
     */
    private fun sanitizeMetadataLine(line: String, item: MediaItem): String? {
        val stream = item.streamUrl?.trim()
        if (!stream.isNullOrEmpty() && line.contains(stream)) {
            return null
        }
        if (line.contains("api.telegram.org", ignoreCase = true)) {
            return null
        }
        if (botTokenPathRegex.containsMatchIn(line)) {
            return null
        }
        return line
    }
}
