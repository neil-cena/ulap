package com.ulap

import com.ulap.data.remote.CHUNKED_FILE_ID_PREFIX
import com.ulap.sync.shouldDeleteChunkMetadataAfterSuccessfulUpload
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineChunkMetadataCleanupTest {

    @Test
    fun chunked_sentinel_file_id_must_not_trigger_chunk_table_wipe() {
        assertFalse(shouldDeleteChunkMetadataAfterSuccessfulUpload("${CHUNKED_FILE_ID_PREFIX}12"))
    }

    @Test
    fun normal_telegram_file_id_triggers_stale_chunk_cleanup() {
        assertTrue(shouldDeleteChunkMetadataAfterSuccessfulUpload("AgACAgIAAxkBAAIExample"))
    }
}
