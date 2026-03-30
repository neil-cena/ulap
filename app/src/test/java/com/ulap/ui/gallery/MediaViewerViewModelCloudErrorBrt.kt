package com.ulap.ui.gallery

import androidx.media3.common.PlaybackException
import com.ulap.domain.model.MediaItem
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Bug Reproduction Test: Cloud video playback has no error recovery path.
 *
 * When ExoPlayer fires a [PlaybackException] for a [StreamUrlsState.Ready] cloud streaming URL,
 * there is no handler in [MediaViewerViewModel] to transition the state to [StreamUrlsState.Error]
 * or trigger a retry. The UI stays black/buffering indefinitely.
 *
 * This test fails to compile until [MediaViewerViewModel.onCloudPlaybackError] is added,
 * proving the missing recovery path.
 */
class MediaViewerViewModelCloudErrorBrt {

    @Test
    fun `MediaViewerViewModel has onCloudPlaybackError for cloud streaming error recovery`() {
        // This reference will produce "Unresolved reference: onCloudPlaybackError" at compile time
        // until the method is added to MediaViewerViewModel.
        // Method signature required: fun onCloudPlaybackError(item: MediaItem, error: PlaybackException)
        val methodRef: MediaViewerViewModel.(MediaItem, PlaybackException) -> Unit =
            MediaViewerViewModel::onCloudPlaybackError

        assertNotNull("onCloudPlaybackError must exist on MediaViewerViewModel", methodRef)
    }
}
