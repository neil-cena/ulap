package com.ulap.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.ulap.data.local.dao.BackupFolderDao
import com.ulap.data.local.dao.ChunkMetadataDao
import com.ulap.data.local.dao.MediaItemDao
import com.ulap.data.local.dao.SyncStateDao
import com.ulap.data.local.entity.BackupFolderEntity
import com.ulap.data.local.entity.BackupStatus
import com.ulap.data.local.entity.ChunkMetadataEntity
import com.ulap.data.local.entity.ChunkStatus
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

class ChunkStatusConverter {
    @TypeConverter fun fromChunkStatus(value: ChunkStatus): String = value.name
    @TypeConverter fun toChunkStatus(value: String): ChunkStatus = ChunkStatus.valueOf(value)
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_items ADD COLUMN thumbnailMessageId INTEGER")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_items ADD COLUMN chunkMessageIds TEXT")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_items ADD COLUMN contentHash TEXT")
    }
}

val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Re-enable items that were excluded solely due to the old 2GB limit
        db.execSQL("""
            UPDATE media_items
            SET backupStatus = 'PENDING', errorMessage = NULL
            WHERE backupStatus = 'EXCLUDED'
            AND errorMessage = 'File exceeds 2GB limit'
        """)
    }
}

val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS chunk_metadata (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                mediaItemId TEXT NOT NULL,
                chunkIndex INTEGER NOT NULL,
                telegramFileId TEXT NOT NULL,
                telegramMessageId INTEGER NOT NULL,
                byteOffset INTEGER NOT NULL,
                byteLength INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'UPLOADED',
                FOREIGN KEY (mediaItemId) REFERENCES media_items(id) ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_chunk_media_index ON chunk_metadata(mediaItemId, chunkIndex)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chunk_media ON chunk_metadata(mediaItemId)")
    }
}

/** Max items per Room IN-clause batch. SQLite's SQLITE_MAX_VARIABLE_NUMBER defaults to 999. */
const val ROOM_BATCH_SIZE = 500

val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_items ADD COLUMN uploadBotIndex INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [MediaItemEntity::class, BackupFolderEntity::class, SyncStateEntity::class, ChunkMetadataEntity::class],
    version = 10,
    exportSchema = true,
)
@TypeConverters(MediaTypeConverter::class, BackupStatusConverter::class, ChunkStatusConverter::class)
abstract class UlapDatabase : RoomDatabase() {
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun backupFolderDao(): BackupFolderDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun chunkMetadataDao(): ChunkMetadataDao
}
