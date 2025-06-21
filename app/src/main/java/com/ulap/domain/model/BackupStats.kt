package com.ulap.domain.model

data class BackupStats(
    val total: Int,
    val backedUp: Int,
    val pending: Int,
    val failed: Int,
    val excluded: Int,
    val cloudOnly: Int = 0,
) {
    val progress: Float get() = if (total == 0) 0f else backedUp.toFloat() / total
}
