package com.ulap.ui.gallery // download button

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.ulap.domain.model.MediaType
import kotlin.math.sqrt

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
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { page -> allItems.getOrNull(page)?.id ?: page },
            ) { page ->
                val item = allItems.getOrNull(page) ?: return@HorizontalPager
                when {
                    item.contentUri.isNotBlank() -> {
                        if (item.mediaType == MediaType.VIDEO) {
                            VideoPlayerView(uris = listOf(Uri.parse(item.contentUri)))
                        } else {
                            ZoomableImage(uri = Uri.parse(item.contentUri))
                        }
                    }
                    else -> {
                        val state = streamUrlsCache[item.id] ?: StreamUrlsState.Loading
                        when (state) {
                            is StreamUrlsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color.White)
                            }
                            is StreamUrlsState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(state.message, color = Color.White, modifier = Modifier.fillMaxWidth(0.8f))
                            }
                            is StreamUrlsState.Ready -> {
                                val urls = state.urls
                                if (item.mediaType == MediaType.VIDEO) {
                                    VideoPlayerView(uris = urls.map { Uri.parse(it) })
                                } else {
                                    ZoomableImage(uri = Uri.parse(urls.first()))
                                }
                            }
                            is StreamUrlsState.ReadyProgressive -> {
                                VideoPlayerView(
                                    uris = listOf(Uri.parse(state.fileUri)),
                                    dataSourceFactory = state.dataSourceFactory,
                                )
                            }
                            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color.White)
                            }
                        }
                    }
                }
            }
            val currentItem = allItems.getOrNull(pagerState.currentPage)
            val canDownload = currentItem != null &&
                currentItem.contentUri.isBlank() &&
                currentItem.telegramFileId != null
            val isDownloading = downloadState is DownloadState.Downloading
            if (canDownload) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    if (isDownloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = { currentItem?.let { viewModel.downloadItem(it) } }) {
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
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
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
private fun ZoomableImage(uri: Uri) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var lastCentroid by remember { mutableStateOf(Offset.Zero) }
    var lastDist by remember { mutableFloatStateOf(0f) }
    var lastSingle by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(scale) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.changes.size) {
                            1 -> {
                                val pos = event.changes.first().position
                                if (scale > 1f) {
                                    offset += (pos - lastSingle)
                                    event.changes.forEach { it.consume() }
                                }
                                lastSingle = pos
                            }
                            else -> if (event.changes.size >= 2) {
                                val p1 = event.changes[0].position
                                val p2 = event.changes[1].position
                                val centroid = Offset((p1.x + p2.x) / 2, (p1.y + p2.y) / 2)
                                val dist = sqrt((p2.x - p1.x) * (p2.x - p1.x) + (p2.y - p1.y) * (p2.y - p1.y))
                                if (lastDist > 0f) {
                                    val zoom = dist / lastDist
                                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                                    scale = newScale
                                    if (newScale > 1f) {
                                        offset += (centroid - lastCentroid)
                                    } else {
                                        offset = Offset.Zero
                                    }
                                }
                                lastCentroid = centroid
                                lastDist = dist
                                event.changes.forEach { it.consume() }
                            }
                        }
                        when (event.type) {
                            PointerEventType.Press -> {
                                when (event.changes.size) {
                                    1 -> lastSingle = event.changes[0].position
                                    else -> if (event.changes.size >= 2) {
                                        val p1 = event.changes[0].position
                                        val p2 = event.changes[1].position
                                        lastCentroid = Offset((p1.x + p2.x) / 2, (p1.y + p2.y) / 2)
                                        lastDist = sqrt((p2.x - p1.x) * (p2.x - p1.x) + (p2.y - p1.y) * (p2.y - p1.y))
                                    }
                                }
                            }
                            PointerEventType.Release -> {
                                lastDist = 0f
                            }
                            else -> { }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
