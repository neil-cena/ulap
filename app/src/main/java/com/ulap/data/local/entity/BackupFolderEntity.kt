package com.ulap.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "backup_folders")
data class BackupFolderEntity(
    @PrimaryKey val bucketName: String,
    val displayName: String,
    val isEnabled: Boolean = false,
    val itemCount: Int = 0,
    val backedUpCount: Int = 0,
)
