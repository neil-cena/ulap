package com.ulap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ulap.data.local.entity.BackupFolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupFolderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(folders: List<BackupFolderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: BackupFolderEntity)

    @Update
    suspend fun update(folder: BackupFolderEntity)

    @Query("SELECT * FROM backup_folders ORDER BY displayName ASC")
    fun observeAll(): Flow<List<BackupFolderEntity>>

    @Query("SELECT * FROM backup_folders WHERE isEnabled = 1")
    fun observeEnabled(): Flow<List<BackupFolderEntity>>

    @Query("SELECT * FROM backup_folders WHERE isEnabled = 1")
    suspend fun getEnabled(): List<BackupFolderEntity>

    @Query("SELECT * FROM backup_folders WHERE bucketName = :bucketName")
    suspend fun findByBucket(bucketName: String): BackupFolderEntity?

    @Query("UPDATE backup_folders SET isEnabled = :enabled WHERE bucketName = :bucketName")
    suspend fun setEnabled(bucketName: String, enabled: Boolean)

    @Query(
        """
        UPDATE backup_folders
        SET itemCount = :total, backedUpCount = :backed
        WHERE bucketName = :bucketName
        """
    )
    suspend fun updateCounts(bucketName: String, total: Int, backed: Int)
}
