package com.ulap.data.remote

/**
 * Byte span per chunk for a chunked upload, matching [TelegramUploader] / [BackupIndexManager] layout.
 */
object ChunkMetadataLayout {

    fun byteLengthsForChunkedFile(
        totalSize: Long,
        chunkCount: Int,
        chunkUploadSize: Long = CHUNK_UPLOAD_SIZE,
        fastStartChunkSize: Long? = null,
    ): List<Int> {
        require(chunkCount > 0) { "chunkCount must be positive" }
        require(totalSize >= 0) { "totalSize must be non-negative" }
        val chunkSize = chunkUploadSize.toInt()
        val out = ArrayList<Int>(chunkCount)
        var offset = 0L
        repeat(chunkCount) { idx ->
            val isLast = idx == chunkCount - 1
            val len = when {
                isLast -> (totalSize - offset).coerceAtLeast(0).coerceAtMost(chunkSize.toLong()).toInt()
                idx == 0 && fastStartChunkSize != null -> fastStartChunkSize.toInt()
                else -> chunkSize
            }
            out.add(len)
            offset += len
        }
        return out
    }

    /** Parses `chunked:<n>` sentinel on [MediaItemEntity.telegramFileId]. */
    fun totalChunksFromSentinel(telegramFileId: String?): Int? {
        val raw = telegramFileId ?: return null
        if (!raw.startsWith(CHUNKED_FILE_ID_PREFIX)) return null
        return raw.removePrefix(CHUNKED_FILE_ID_PREFIX).toIntOrNull()?.takeIf { it > 0 }
    }
}
