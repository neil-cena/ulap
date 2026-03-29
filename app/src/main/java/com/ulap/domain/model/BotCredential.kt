package com.ulap.domain.model

/**
 * Represents one bot in the pool. [index] 0 is always the primary bot; additional bots
 * start from 1. Stored alongside each [com.ulap.data.local.entity.MediaItemEntity] as
 * [com.ulap.data.local.entity.MediaItemEntity.uploadBotIndex] so downloads can be routed
 * back to the same token that originally performed the upload.
 */
data class BotCredential(
    val index: Int,
    val token: String,
    val label: String = "",
)
