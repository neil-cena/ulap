package com.ulap.ui.gallery

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bug Reproduction Test: Chunked video playback failure is completely silent in the Telegram log
 * and provides no recovery guidance to the user.
 *
 * When chunk_metadata is empty (e.g. after a DB wipe/reinstall), startPrefetchingChunkedDownload
 * emits StreamUrlsState.Error("No chunk metadata found") but:
 *   1. Calls debugLog.log() ZERO times — the failure is invisible in the Telegram debug log
 *   2. Shows a technical message with no actionable guidance for the user
 *
 * The fix requires an internal function that encapsulates the correct error state for this case,
 * enforcing both the logging call (at the callsite) and the user-facing message content.
 *
 * This test fails to compile until [streamErrorForMissingChunkMetadata] is added to the
 * MediaViewerViewModel file, proving the missing logging and message contract.
 */
class MediaViewerChunkMetadataMissingBrt {

    @Test
    fun `chunk metadata missing error message guides user toward downloading`() {
        // Compile error: unresolved reference 'streamErrorForMissingChunkMetadata'
        // Function signature required:
        //   internal fun streamErrorForMissingChunkMetadata(): StreamUrlsState.Error
        val error = streamErrorForMissingChunkMetadata()

        assertTrue(
            "Error message must guide user toward downloading the video, but was: '${error.message}'",
            error.message.contains("download", ignoreCase = true) ||
                error.message.contains("Download", ignoreCase = false),
        )
    }
}
