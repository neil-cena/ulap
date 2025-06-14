package com.ulap.domain.model

data class BackupFolder(
    val bucketName: String,
    val displayName: String,
    val isEnabled: Boolean,
    val itemCount: Int,
    val backedUpCount: Int,
) {
    val pendingCount: Int get() = itemCount - backedUpCount
    val progress: Float get() = if (itemCount == 0) 0f else backedUpCount.toFloat() / itemCount
}
