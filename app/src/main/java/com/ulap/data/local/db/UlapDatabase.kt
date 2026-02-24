package com.ulap.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.ulap.data.local.dao.BackupFolderDao
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.local.dao.SyncStateDao
import com.ulap.data.local.entity.BackupFolderEntity
import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.local.entity.MediaItemEntity
import com.ulap.data.local.entity.MediaType
import com.ulap.data.local.entity.SyncStateEntity

class MediaTypeConverter {
    @TypeConverter fun fromMediaType(value: MediaType): String = value.name
    @TypeConverter fun toMediaType(value: String): MediaType = MediaType.valueOf(value)
}

class BackupStatusConverter {
    @TypeConverter fun fromBackupStatus(value: BackupStatus): String = value.name
    @TypeConverter fun toBackupStatus(value: String): BackupStatus = BackupStatus.valueOf(value)
}

@Database(
    entities = [MediaItemEntity::class, BackupFolderEntity::class, SyncStateEntity::class],
    version = 4,
    exportSchema = true,
)
@TypeConverters(MediaTypeConverter::class, BackupStatusConverter::class)
abstract class UlapDatabase : RoomDatabase() {
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun backupFolderDao(): BackupFolderDao
    abstract fun syncStateDao(): SyncStateDao
}
