package com.ulap.domain.backup

/**
 * Single-file size limit aligned with the user-facing copy in [com.ulap.R.string.backup_stat_excluded_hint]
 * ("2 GB limit"). Files strictly larger than this value are treated as over the per-file backup cap.
 */
object BackupSingleFileLimitPolicy {

    /** Binary 2 GiB — matches backup UI messaging. */
    const val MAX_SINGLE_FILE_BYTES: Long = 2L * 1024 * 1024 * 1024

    /**
     * Count of excluded items that should appear in the "exceed the 2 GB limit" hint.
     * Size-based only: unknown/null size and non-positive sizes are not counted.
     */
    fun excludedOversizeHintCount(sizes: List<Long?>): Int =
        sizes.count { s ->
            val v = s ?: return@count false
            if (v <= 0L) return@count false
            v > MAX_SINGLE_FILE_BYTES
        }
}
