package com.ulap.di

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ulap.data.local.db.MIGRATION_4_5
import com.ulap.data.local.db.MIGRATION_5_6
import com.ulap.data.local.db.MIGRATION_6_7
import com.ulap.data.local.db.MIGRATION_7_8
import com.ulap.data.local.db.MIGRATION_8_9
import com.ulap.data.local.db.UlapDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE media_items ADD COLUMN thumbnailFileId TEXT")
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sync_state ADD COLUMN lastEnabledBucketsKey TEXT")
        }
    }

    private val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE media_items ADD COLUMN uploadedChunks TEXT")
            db.execSQL("ALTER TABLE media_items ADD COLUMN uploadedChunkCount INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): UlapDatabase =
        Room.databaseBuilder(context, UlapDatabase::class.java, "ulap.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideMediaItemDao(db: UlapDatabase) = db.mediaItemDao()

    @Provides
    fun provideBackupFolderDao(db: UlapDatabase) = db.backupFolderDao()

    @Provides
    fun provideSyncStateDao(db: UlapDatabase) = db.syncStateDao()

    @Provides
    fun provideChunkMetadataDao(db: UlapDatabase) = db.chunkMetadataDao()

    @Provides
    @Singleton
    fun provideEncryptedSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            "ulap_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Provides
    fun provideContentResolver(@ApplicationContext context: Context): ContentResolver =
        context.contentResolver

    @Provides
    @Singleton
    @PlainPrefs
    fun providePlainSharedPreferences(@ApplicationContext context: Context): SharedPreferences =
        context.getSharedPreferences("ulap_prefs", Context.MODE_PRIVATE)
}
