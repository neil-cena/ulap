package com.ulap.ui.gallery

import android.net.Uri
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@Composable
fun VideoPlayerView(
    uris: List<Uri>,
    dataSourceFactory: DataSource.Factory? = null,
    /** Called whenever the player's built-in controls become visible (true) or hidden (false). */
    onControllerVisibilityChanged: (visible: Boolean) -> Unit = {},
    /** Called when the user taps the fullscreen button inside the player controls. */
    onFullscreenClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val exoPlayer = remember(uris, dataSourceFactory) {
        ExoPlayer.Builder(context).build().apply {
            if (dataSourceFactory != null && uris.size == 1) {
                val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uris.first()))
                setMediaSource(mediaSource)
            } else {
                setMediaItems(uris.map { MediaItem.fromUri(it) })
            }
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(uris, dataSourceFactory) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                    onControllerVisibilityChanged(visibility == android.view.View.VISIBLE)
                })
                if (onFullscreenClick != null) {
                    setFullscreenButtonClickListener { onFullscreenClick() }
                }
            }
        },
    )
}
