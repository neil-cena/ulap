package com.ulap.ui.gallery

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.ulap.domain.model.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun MediaViewerScreen(
    mediaId: String,
    onBack: () -> Unit,
    viewModel: MediaViewerViewModel = hiltViewModel(),
) {
    val allItems by viewModel.allItems.collectAsState()
    val streamUrlsCache by viewModel.streamUrlsCache.collectAsState()
    val downloadState by viewModel.downloadState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var overlayVisible by remember { mutableStateOf(true) }
    var isFullscreen by remember { mutableStateOf(false) }

    val activity = LocalContext.current as Activity
    val window = activity.window
    val insetsController = remember(window) {
        WindowCompat.getInsetsController(window, window.decorView)
    }

    // Enter / exit immersive mode when fullscreen state changes.
    LaunchedEffect(isFullscreen) {
        if (isFullscreen) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insetsController.apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Always restore orientation when leaving this screen.
    DisposableEffect(Unit) {
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(downloadState) {
        when (val s = downloadState) {
            is DownloadState.Done -> {
                snackbarHostState.showSnackbar("Saved to Pictures / Ulap Restore")
                viewModel.clearDownloadState()
            }
            is DownloadState.Error -> {
                snackbarHostState.showSnackbar(s.message)
                viewModel.clearDownloadState()
            }
            else -> { }
        }
    }
    LaunchedEffect(overlayVisible) {
        if (overlayVisible) {
            delay(3_000)
            overlayVisible = false
        }
    }

    // Wrap onBack so we always exit fullscreen first.
    val handleBack = remember(onBack) {
        {
            if (isFullscreen) {
                isFullscreen = false
            } else {
                onBack()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (allItems.isEmpty()) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        } else {
            val startIndex = allItems.indexOfFirst { it.id == mediaId }.coerceAtLeast(0)
            val pagerState = rememberPagerState(
                initialPage = startIndex,
                pageCount = { allItems.size },
            )
            LaunchedEffect(mediaId, allItems.size) {
                viewModel.setCurrentPage(allItems.indexOfFirst { it.id == mediaId }.coerceAtLeast(0))
            }
            LaunchedEffect(pagerState.currentPage) {
                viewModel.setCurrentPage(pagerState.currentPage)
            }
            val onToggleOverlay = remember { { overlayVisible = !overlayVisible } }

            // Callback passed to VideoPlayerView: sync overlay with player controller visibility.
            val onControllerVisibilityChanged: (Boolean) -> Unit = remember {
                { visible -> if (visible) overlayVisible = true }
            }
            val onFullscreenClick: () -> Unit = remember { { isFullscreen = !isFullscreen } }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { page -> allItems.getOrNull(page)?.id ?: page },
            ) { page ->
                val item = allItems.getOrNull(page) ?: return@HorizontalPager
                    when {
                        // If the ViewModel has claimed this item for cloud resolution (e.g. stale
                        // local URI detected), prefer the cloud path even if contentUri is set.
                        item.contentUri.isNotBlank() && streamUrlsCache[item.id] == null -> {
                            if (item.mediaType == MediaType.VIDEO) {
                                VideoPlayerView(
                                    uris = listOf(Uri.parse(item.contentUri)),
                                    onControllerVisibilityChanged = onControllerVisibilityChanged,
                                    onFullscreenClick = onFullscreenClick,
                                    onError = { err ->
                                        viewModel.onLocalPlaybackError(item)
                                        viewModel.onVideoError(item, err)
                                    },
                                    onVideoOpened = { viewModel.onVideoOpened(item) },
                                    onVideoReleased = { viewModel.onVideoClosed(item) },
                                    onPlayerStateChanged = { desc -> viewModel.onVideoPlayerState(item, desc) },
                                )
                            } else {
                                ZoomableImage(
                                    uri = Uri.parse(item.contentUri),
                                    contentDescription = item.fileName,
                                    onTap = onToggleOverlay,
                                )
                            }
                        }
                        else -> {
                            val state = streamUrlsCache[item.id] ?: StreamUrlsState.Loading
                            when (state) {
                                is StreamUrlsState.Loading -> Box(
                                    Modifier.fillMaxSize().pointerInput(Unit) {
                                        detectTapGestures(onTap = { onToggleOverlay() })
                                    },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(color = Color.White)
                                }
                                is StreamUrlsState.Error -> Box(
                                    Modifier.fillMaxSize().pointerInput(Unit) {
                                        detectTapGestures(onTap = { onToggleOverlay() })
                                    },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(state.message, color = Color.White, modifier = Modifier.fillMaxWidth(0.8f))
                                }
                                is StreamUrlsState.Ready -> {
                                    val urls = state.urls
                                    if (item.mediaType == MediaType.VIDEO) {
                                        VideoPlayerView(
                                            uris = urls.map { Uri.parse(it) },
                                            onControllerVisibilityChanged = onControllerVisibilityChanged,
                                            onFullscreenClick = onFullscreenClick,
                                            onVideoOpened = { viewModel.onVideoOpened(item) },
                                            onVideoReleased = { viewModel.onVideoClosed(item) },
                                            onPlayerStateChanged = { desc -> viewModel.onVideoPlayerState(item, desc) },
                                            onError = { err ->
                                                viewModel.onCloudPlaybackError(item, err)
                                                viewModel.onVideoError(item, err)
                                            },
                                        )
                                    } else {
                                        ZoomableImage(
                                            uri = Uri.parse(urls.first()),
                                            contentDescription = item.fileName,
                                            onTap = onToggleOverlay,
                                        )
                                    }
                                }
                                is StreamUrlsState.ReadyProgressive -> {
                                    VideoPlayerView(
                                        uris = listOf(Uri.parse(state.fileUri)),
                                        dataSourceFactory = state.dataSourceFactory,
                                        onControllerVisibilityChanged = onControllerVisibilityChanged,
                                        onFullscreenClick = onFullscreenClick,
                                        onVideoOpened = { viewModel.onVideoOpened(item) },
                                        onVideoReleased = { viewModel.onVideoClosed(item) },
                                        onPlayerStateChanged = { desc -> viewModel.onVideoPlayerState(item, desc) },
                                    )
                                }
                                else -> Box(
                                    Modifier.fillMaxSize().pointerInput(Unit) {
                                        detectTapGestures(onTap = { onToggleOverlay() })
                                    },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(color = Color.White)
                                }
                            }
                        }
                    }
            }
            val currentItem = allItems.getOrNull(pagerState.currentPage)
            val isCurrentVideo = currentItem?.mediaType == MediaType.VIDEO
            val canDownload = currentItem != null &&
                currentItem.contentUri.isBlank() &&
                currentItem.telegramFileId != null
            val isDownloading = downloadState is DownloadState.Downloading
            AnimatedVisibility(
                visible = overlayVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    IconButton(
                        onClick = handleBack,
                        modifier = Modifier
                            .background(Color(0x66000000), CircleShape)
                            .padding(4.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }

                    Box(modifier = Modifier.weight(1f))

                    if (isCurrentVideo) {
                        IconButton(
                            onClick = onFullscreenClick,
                            modifier = Modifier
                                .background(Color(0x66000000), CircleShape)
                                .padding(4.dp),
                        ) {
                            Icon(
                                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                                tint = Color.White,
                            )
                        }
                    }

                    if (canDownload) {
                        IconButton(
                            onClick = { if (!isDownloading) currentItem?.let { viewModel.downloadItem(it) } },
                            modifier = Modifier
                                .background(Color(0x66000000), CircleShape)
                                .padding(4.dp),
                        ) {
                            if (isDownloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = "Download",
                                    tint = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

@Composable
private fun ZoomableImage(uri: Uri, contentDescription: String? = null, onTap: () -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val scale = remember { Animatable(1f) }
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    fun clampOffset(current: Offset, currentScale: Float): Offset {
        if (containerSize == IntSize.Zero || currentScale <= 1f) return Offset.Zero
        val maxX = (containerSize.width.toFloat() * (currentScale - 1f)) / 2f
        val maxY = (containerSize.height.toFloat() * (currentScale - 1f)) / 2f
        return Offset(
            x = current.x.coerceIn(-maxX, maxX),
            y = current.y.coerceIn(-maxY, maxY),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(uri) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        scope.launch {
                            scale.stop()
                            offset.stop()
                            launch { scale.animateTo(1f) }
                            launch { offset.animateTo(Offset.Zero) }
                        }
                    },
                )
            }
            .pointerInput(uri) {
                // Only intercept and consume pan/zoom gestures when the image is zoomed in.
                // At scale 1×, horizontal swipes must propagate to the parent HorizontalPager.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var pan = Offset.Zero
                    var pastTouchSlop = false

                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (canceled) break

                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()

                        val isZoomed = scale.value > 1f
                        if (!pastTouchSlop) {
                            pan += panChange
                            val panDistance = pan.getDistance()
                            // Trigger gesture handling when zoomed or when pinching.
                            if (panDistance > viewConfiguration.touchSlop || zoomChange != 1f) {
                                pastTouchSlop = true
                            }
                        }

                        if (pastTouchSlop) {
                            val newScale = (scale.value * zoomChange).coerceIn(1f, 5f)
                            val updatedOffset = if (newScale <= 1f) Offset.Zero
                            else clampOffset(offset.value + panChange, newScale)

                            // Only disallow parent interception (blocking pager swipe) when zoomed.
                            if (isZoomed || zoomChange != 1f) {
                                event.changes.forEach { it.consume() }
                            }

                            scope.launch { scale.snapTo(newScale) }
                            scope.launch { offset.snapTo(updatedOffset) }
                        }
                    } while (event.changes.any { it.pressed })

                    // Reset offset when scale snaps back to 1× after a pinch.
                    if (scale.value <= 1f && offset.value != Offset.Zero) {
                        scope.launch { offset.snapTo(Offset.Zero) }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = uri,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    translationX = offset.value.x
                    translationY = offset.value.y
                },
        )
    }
}
