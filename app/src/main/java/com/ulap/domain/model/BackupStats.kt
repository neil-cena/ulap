package com.ulap.domain.model

data class BackupStats(
    val total: Int,
    val backedUp: Int,
    val pending: Int,
    val failed: Int,
    val excluded: Int,
    /**
     * Subset of [excluded]: items whose stored size is strictly greater than the single-file
     * backup cap (see [com.ulap.domain.backup.BackupSingleFileLimitPolicy]). Used only for the
     * 2 GB limit hint — not for folder-disabled / not-on-device exclusions.
     */
    val excludedOverSingleFileLimit: Int = 0,
    val cloudOnly: Int = 0,
    val backedUpBytes: Long = 0L,
    val pendingBytes: Long = 0L,
    val cloudOnlyBytes: Long = 0L,
) {
    val progress: Float get() = if (total == 0) 0f else backedUp.toFloat() / total
}
