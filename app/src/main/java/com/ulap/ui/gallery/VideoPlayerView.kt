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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@Composable
fun VideoPlayerView(
    uris: List<Uri>,
    dataSourceFactory: DataSource.Factory? = null,
    /** Position (ms) to seek to on player creation; 0 means start from the beginning. */
    startPositionMs: Long = 0L,
    /** Whether the player should start playing immediately (default true). */
    startPlayWhenReady: Boolean = true,
    /**
     * Invoked just before the player is released with the last known position (ms) and
     * play/pause intent. Wire this to [MediaViewerViewModel.saveVideoPosition] so that
     * position survives Activity recreation (process death, theme change, etc.).
     */
    onPositionChanged: ((positionMs: Long, isPlaying: Boolean) -> Unit)? = null,
    /** Called whenever the player's built-in controls become visible (true) or hidden (false). */
    onControllerVisibilityChanged: (visible: Boolean) -> Unit = {},
    /** Called when the user taps the fullscreen button inside the player controls. */
    onFullscreenClick: (() -> Unit)? = null,
    /** Called when ExoPlayer encounters a fatal playback error. */
    onError: ((PlaybackException) -> Unit)? = null,
    /** Called when the player transitions to a ready/playing state for the first time. */
    onVideoOpened: (() -> Unit)? = null,
    /** Called when the player is released (video closed / navigated away). */
    onVideoReleased: (() -> Unit)? = null,
    /** Called on every significant player state change for logging. Receives a human-readable description. */
    onPlayerStateChanged: ((description: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val exoPlayer = remember(uris, dataSourceFactory) {
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
        ExoPlayer.Builder(context, renderersFactory).build().apply {
            if (dataSourceFactory != null && uris.size == 1) {
                val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uris.first()))
                setMediaSource(mediaSource)
            } else {
                setMediaItems(uris.map { MediaItem.fromUri(it) })
            }
            if (startPositionMs > 0L) seekTo(startPositionMs)

            if (onError != null || onVideoOpened != null || onPlayerStateChanged != null) {
                addListener(object : Player.Listener {

                    override fun onPlayerError(error: PlaybackException) {
                        onPlayerStateChanged?.invoke("ERROR: ${error.errorCodeName} — ${error.message}")
                        onError?.invoke(error)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val stateName = when (playbackState) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> {
                                onVideoOpened?.invoke()
                                "READY"
                            }
                            Player.STATE_ENDED -> "ENDED"
                            else -> "UNKNOWN($playbackState)"
                        }
                        onPlayerStateChanged?.invoke("playbackState=$stateName")
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        onPlayerStateChanged?.invoke("isPlaying=$isPlaying")
                    }

                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int,
                    ) {
                        val reasonName = when (reason) {
                            Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
                            Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
                            Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
                            Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
                            Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
                            else -> "UNKNOWN($reason)"
                        }
                        onPlayerStateChanged?.invoke(
                            "discontinuity reason=$reasonName " +
                                "pos=${oldPosition.positionMs}ms→${newPosition.positionMs}ms"
                        )
                    }
                })
            }

            prepare()
            playWhenReady = startPlayWhenReady
        }
    }

    DisposableEffect(uris, dataSourceFactory) {
        // #region agent log
        android.util.Log.w("DBG_5f6b53", "[VideoPlayerView] DisposableEffect STARTED uris=${uris.size} hasFactory=${dataSourceFactory != null} | thread=${Thread.currentThread().name}")
        // #endregion
        onDispose {
            // #region agent log
            android.util.Log.w("DBG_5f6b53", "[VideoPlayerView] DisposableEffect onDispose FIRING uris=${uris.size} | thread=${Thread.currentThread().name}")
            // #endregion
            onPositionChanged?.invoke(exoPlayer.currentPosition, exoPlayer.playWhenReady)
            onVideoReleased?.invoke()
            exoPlayer.release()
        }
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
