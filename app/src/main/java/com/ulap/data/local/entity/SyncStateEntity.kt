package com.ulap.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val lastFullScanAt: Long? = null,
    val lastIncrementalScanAt: Long? = null,
    val currentQueueSize: Int = 0,
    val lastEnabledBucketsKey: String? = null,
)
