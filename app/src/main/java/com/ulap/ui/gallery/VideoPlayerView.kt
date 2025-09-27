package com.ulap.ui.gallery // ExoPlayer

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView

@Composable
fun VideoPlayerView(
    uris: List<Uri>,
    dataSourceFactory: DataSource.Factory? = null,
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
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
            }
        }
    )
}
