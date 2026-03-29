package com.ulap

/**
 * Shared rules for Telegram backup pre-upload checks and failure-summary text.
 * Kept free of Android framework types so unit tests can depend on it from JVM tests.
 */
object TelegramBackupPolicy {

    const val TELEGRAM_PHOTO_MAX_EDGE_PX = 10_000

    const val MSG_COULD_NOT_OPEN_FILE = "Could not open file"

    const val MSG_EMPTY_FILE = "Empty file"

    const val SUMMARY_COULD_NOT_BACK_UP = "couldn't be backed up"

    const val GENERIC_UPLOAD_FAILED_MESSAGE = "Upload failed"

    fun dimensionsOkForPhoto(width: Int?, height: Int?): Boolean {
        if (width == null || height == null) return true
        if (width <= 0 || height <= 0) return false
        return width <= TELEGRAM_PHOTO_MAX_EDGE_PX && height <= TELEGRAM_PHOTO_MAX_EDGE_PX
    }

    /**
     * After a successful [android.graphics.BitmapFactory] inJustDecodeBounds (positive dimensions).
     */
    fun shouldSendPhotoAsDocumentByDecodedBounds(outWidth: Int, outHeight: Int): Boolean =
        outWidth > TELEGRAM_PHOTO_MAX_EDGE_PX || outHeight > TELEGRAM_PHOTO_MAX_EDGE_PX

    sealed class PreBackupEvaluation {
        data object Success : PreBackupEvaluation()

        data class FileOpenFailed(val displayName: String) : PreBackupEvaluation()

        data class EmptyFile(val displayName: String) : PreBackupEvaluation()
    }

    fun classifyBackupFailure(
        displayName: String,
        uriOpenable: Boolean,
        sizeBytes: Long,
        widthPx: Int?,
        heightPx: Int?,
    ): PreBackupEvaluation = when {
        !uriOpenable -> PreBackupEvaluation.FileOpenFailed(displayName)
        sizeBytes == 0L -> PreBackupEvaluation.EmptyFile(displayName)
        !dimensionsOkForPhoto(widthPx, heightPx) -> PreBackupEvaluation.Success
        else -> PreBackupEvaluation.Success
    }

    sealed class FailureSummaryLine {
        data class ApiError(val headline: String) : FailureSummaryLine()

        data class Labeled(val headline: String, val fileLabel: String) : FailureSummaryLine()
    }

    fun buildFailureSummary(lines: List<FailureSummaryLine>): String {
        val n = lines.size
        val head = "$n file(s) $SUMMARY_COULD_NOT_BACK_UP"
        if (n == 0) return head
        val body = lines.map { l ->
            when (l) {
                is FailureSummaryLine.ApiError -> l.headline
                is FailureSummaryLine.Labeled -> "${l.headline}: ${l.fileLabel}"
            }
        }
        return (listOf(head) + body).joinToString("\n")
    }
}
