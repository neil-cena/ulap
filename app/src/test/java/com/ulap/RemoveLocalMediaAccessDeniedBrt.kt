package com.ulap

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.ulap.domain.model.BackupStats
import com.ulap.domain.model.BackupStatus
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.MediaType
import com.ulap.domain.repository.MediaRepository
import com.ulap.domain.usecase.MarkAsCloudOnlyUseCase
import com.ulap.domain.usecase.RemoveLocalMediaFileUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BRT: "Remove from device" must not end as a terminal failure with the raw MediaStore
 * scoped-storage access denial when [ContentResolver.delete] throws [SecurityException].
 *
 * RED: Current use case returns [Result.failure] with that [SecurityException].
 * GREEN: User-consent delete path (e.g. [android.provider.MediaStore.createDeleteRequest]) or
 * a dedicated outcome the UI maps to the system delete confirmation — not bare access denial.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RemoveLocalMediaAccessDeniedBrt {

    @Test
    fun removeFromDevice_whenDeleteThrowsScopedStorageAccessDenied_mustNotReturnBareSecurityFailure() =
        runTest {
            val accessDeniedMessage =
                "com.ulap has no access to content://media/external/images/media/62237"
            val resolver = mock<ContentResolver>()
            whenever(
                resolver.delete(
                    any<Uri>(),
                    anyOrNull(),
                    anyOrNull(),
                ),
            ).thenThrow(SecurityException(accessDeniedMessage))

            val context = mock<Context>()
            whenever(context.contentResolver).thenReturn(resolver)

            val useCase =
                RemoveLocalMediaFileUseCase(
                    context = context,
                    markAsCloudOnly = MarkAsCloudOnlyUseCase(NoopMediaRepository()),
                )

            val item =
                MediaItem(
                    id = "local-1",
                    path = "/ignored",
                    contentUri = "content://media/external/images/media/62237",
                    fileName = "IMG_62237.jpg",
                    mimeType = "image/jpeg",
                    size = 1L,
                    dateModified = 0L,
                    dateTaken = 0L,
                    bucketName = "Camera",
                    mediaType = MediaType.IMAGE,
                    backupStatus = BackupStatus.BACKED_UP,
                )

            val result = useCase(item)

            val ex = result.exceptionOrNull()
            val isBareAccessDeniedFailure =
                result.isFailure &&
                    ex is SecurityException &&
                    ex.message?.contains("has no access", ignoreCase = true) == true

            assertFalse(
                "Remove-from-device must not surface raw MediaStore access denial as a terminal " +
                    "SecurityException failure; use a user-consent delete request (e.g. " +
                    "MediaStore.createDeleteRequest) or an equivalent non-terminal outcome.",
                isBareAccessDeniedFailure,
            )
        }

    private class NoopMediaRepository : MediaRepository {
        override fun observeTimeline(): Flow<List<MediaItem>> = emptyFlow()
        override fun observeByFolder(bucketName: String): Flow<List<MediaItem>> = emptyFlow()
        override fun observeByMediaType(type: MediaType): Flow<List<MediaItem>> = emptyFlow()
        override fun observeBackupStats(): Flow<BackupStats> = emptyFlow()
        override suspend fun scanAndSync(fullScan: Boolean) {}
        override suspend fun getItemById(id: String): MediaItem? = null
        override suspend fun getBackedUpWithLocal(): List<MediaItem> = emptyList()
        override suspend fun markAsCloudOnly(ids: List<String>) {}
        override fun observeFailedItems(): Flow<List<MediaItem>> = emptyFlow()
        override fun observeCorruptChunkedBackupCount(): Flow<Int> = emptyFlow()
    }
}
