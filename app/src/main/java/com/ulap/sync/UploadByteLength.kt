package com.ulap.sync

import android.content.ContentResolver
import android.net.Uri
import java.io.File

/**
 * Resolves the byte length to declare for HTTP upload bodies so [okhttp3.RequestBody.contentLength]
 * matches bytes actually read from the stream (avoids "expected … bytes but received …" failures
 * when MediaStore [com.ulap.data.local.entity.MediaItemEntity.size] disagrees with the openable asset).
 */
internal fun resolveUploadByteLength(
    tempExif: File?,
    contentUri: Uri,
    entitySize: Long,
    contentResolver: ContentResolver,
): Long {
    if (tempExif != null && tempExif.exists()) {
        return tempExif.length()
    }
    return coalesceStatSize(entitySize, contentResolver.queryOpenableStatSize(contentUri))
}

/** Prefer [android.os.ParcelFileDescriptor.getStatSize] when the resolver exposes it; else [entitySize]. */
internal fun coalesceStatSize(entitySize: Long, statSize: Long?): Long =
    statSize?.takeIf { it > 0 } ?: entitySize

/**
 * Resolves the file size to use for the empty-file guard when [entitySize] may be stale.
 *
 * MediaStore can report `SIZE = 0` for a file that was just created (race condition — the
 * scanner ran before the bytes were fully flushed). [statSize] is the result of a
 * [ContentResolver.queryOpenableStatSize] re-query and reflects the live on-device size.
 *
 * - If [entitySize] is already positive, it is returned as-is (no re-query needed).
 * - If [entitySize] is 0 and [statSize] is positive, [statSize] wins — the file has real bytes.
 * - If [entitySize] is 0 and [statSize] is null or 0, the file is truly empty — returns 0.
 */
internal fun resolveEmptyFileSize(entitySize: Long, statSize: Long?): Long =
    if (entitySize > 0L) entitySize
    else statSize?.takeIf { it > 0L } ?: 0L

/**
 * Returns the size in bytes of the openable asset behind [uri], or null if unavailable.
 */
internal fun ContentResolver.queryOpenableStatSize(uri: Uri): Long? {
    return try {
        openFileDescriptor(uri, "r")?.use { pfd ->
            val s = pfd.statSize
            if (s > 0) s else null
        }
    } catch (_: Exception) {
        null
    }
}
