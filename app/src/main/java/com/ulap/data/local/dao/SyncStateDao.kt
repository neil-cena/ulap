package com.ulap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ulap.data.local.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun observe(): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncStateEntity?

    @Query("UPDATE sync_state SET lastFullScanAt = :ts WHERE id = 1")
    suspend fun setLastFullScan(ts: Long)

    @Query("UPDATE sync_state SET lastIncrementalScanAt = :ts WHERE id = 1")
    suspend fun setLastIncrementalScan(ts: Long)

    @Query("UPDATE sync_state SET currentQueueSize = :size WHERE id = 1")
    suspend fun setQueueSize(size: Int)
}
