package com.ulap.ui.gallery

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.DataSource
import com.ulap.data.remote.TelegramDownloader
import com.ulap.domain.model.BackupStatus
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.MediaType
import com.ulap.domain.usecase.DownloadCloudItemUseCase
import com.ulap.domain.usecase.GetCredentialsUseCase
import com.ulap.domain.usecase.GetTimelineUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

sealed class DownloadState {
    data object Idle : DownloadState()
    data object Downloading : DownloadState()
    data object Done : DownloadState()
    data class Error(val message: String) : DownloadState()
}

sealed class StreamUrlsState {
    data object None : StreamUrlsState()
    data object Loading : StreamUrlsState()
    data class Ready(val urls: List<String>) : StreamUrlsState()
    data class ReadyProgressive(
        val fileUri: String,
        val dataSourceFactory: DataSource.Factory,
    ) : StreamUrlsState()
    data class Error(val message: String) : StreamUrlsState()
}

@HiltViewModel
class MediaViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    getTimeline: GetTimelineUseCase,
    private val getCredentials: GetCredentialsUseCase,
    private val downloader: TelegramDownloader,
    private val downloadCloudItem: DownloadCloudItemUseCase,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val mediaId: String = savedStateHandle.get<String>("mediaId") ?: ""

    private val _downloadState = MutableStateFlow<DownloadState?>(null)
    val downloadState: StateFlow<DownloadState?> = _downloadState.asStateFlow()

    val allItems: StateFlow<List<MediaItem>> = getTimeline()
        .map { items -> items.sortedByDescending { it.dateTaken } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _currentPage = MutableStateFlow(0)
    private val _streamUrlsCache = MutableStateFlow<Map<String, StreamUrlsState>>(emptyMap())
    val streamUrlsCache: StateFlow<Map<String, StreamUrlsState>> = _streamUrlsCache.asStateFlow()

    fun setCurrentPage(page: Int) {
        _currentPage.value = page
    }

    init {
        viewModelScope.launch {
            try {
                combine(allItems, _currentPage) { items, currentPage ->
                    if (items.isEmpty()) null
                    else {
                        val start = (currentPage - 1).coerceAtLeast(0)
                        val end = (currentPage + 1).coerceAtMost(items.size - 1)
                        val windowIds = (start..end).mapNotNull { idx -> items.getOrNull(idx)?.id }
                        Triple(items, windowIds, _streamUrlsCache.value)
                    }
                }.collect { value ->
                    if (value == null) return@collect
                    val (items, windowIds, cache) = value
                    val token = getCredentials.getToken() ?: return@collect
                    val toFetch = windowIds.mapNotNull { id ->
                        items.find { it.id == id }
                    }.filter { item ->
                        item.contentUri.isBlank() &&
                            item.backupStatus == BackupStatus.CLOUD_ONLY &&
                            item.telegramFileId != null &&
                            cache[item.id] !is StreamUrlsState.Ready &&
                            cache[item.id] !is StreamUrlsState.ReadyProgressive &&
                            cache[item.id] !is StreamUrlsState.Loading
                    }
                    if (toFetch.isEmpty()) return@collect
                    var newCache = cache
                    for (item in toFetch) {
                        newCache = newCache + (item.id to StreamUrlsState.Loading)
                    }
                    _streamUrlsCache.value = newCache
                    for (item in toFetch) {
                        val fileId = item.telegramFileId!!
                        val isChunked = fileId.trim().startsWith("[")
                        val isVideo = item.mediaType == MediaType.VIDEO

                        if (isChunked && isVideo) {
                            startProgressiveChunkedDownload(token, fileId, item.id)
                        } else {
                            val urls = try {
                                withContext(Dispatchers.IO) {
                                    downloader.resolveStreamUrls(token, fileId)
                                }
                            } catch (_: Exception) {
                                emptyList()
                            }
                            val state: StreamUrlsState = if (urls.isEmpty()) {
                                StreamUrlsState.Error("Could not resolve stream URL")
                            } else {
                                StreamUrlsState.Ready(urls)
                            }
                            _streamUrlsCache.value = _streamUrlsCache.value + (item.id to state)
                        }
                    }
                }
            } catch (_: Exception) {
                // Swallow flow errors to prevent crashing the app
            }
        }
    }

    /**
     * Starts downloading chunks to a temp file in the background and immediately
     * emits a ReadyProgressive state so ExoPlayer can begin playback while the
     * file is still growing. If the file was already fully downloaded from a
     * previous session, it emits Ready with a simple file URI instead.
     */
    private fun startProgressiveChunkedDownload(token: String, fileId: String, itemId: String) {
        val tempFile = File(appContext.cacheDir, "ulap_stream_${itemId.hashCode()}.mp4")
        val markerFile = File(appContext.cacheDir, "ulap_stream_${itemId.hashCode()}.done")

        if (tempFile.exists() && markerFile.exists() && tempFile.length() > 0) {
            _streamUrlsCache.value = _streamUrlsCache.value + (
                itemId to StreamUrlsState.Ready(listOf(tempFile.toURI().toString()))
            )
            return
        }

        tempFile.delete()
        markerFile.delete()

        val written = AtomicLong(0L)
        val complete = AtomicBoolean(false)

        val factory = ChunkedVideoDataSource.Factory(tempFile, written::get, complete::get)
        val fileUri = tempFile.toURI().toString()

        _streamUrlsCache.value = _streamUrlsCache.value + (
            itemId to StreamUrlsState.ReadyProgressive(fileUri, factory)
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                FileOutputStream(tempFile).buffered().use { out ->
                    val result = downloader.download(
                        token = token,
                        fileId = fileId,
                        outputStream = object : java.io.OutputStream() {
                            private val delegate = out
                            override fun write(b: Int) {
                                delegate.write(b)
                                delegate.flush()
                                written.incrementAndGet()
                            }
                            override fun write(b: ByteArray, off: Int, len: Int) {
                                delegate.write(b, off, len)
                                delegate.flush()
                                written.addAndGet(len.toLong())
                            }
                            override fun flush() = delegate.flush()
                            override fun close() = delegate.close()
                        },
                    )
                    when (result) {
                        is com.ulap.data.remote.DownloadResult.Success -> {
                            complete.set(true)
                            markerFile.createNewFile()
                        }
                        is com.ulap.data.remote.DownloadResult.Error -> {
                            complete.set(true)
                            tempFile.delete()
                            _streamUrlsCache.value = _streamUrlsCache.value + (
                                itemId to StreamUrlsState.Error(
                                    result.cause.message ?: "Download failed"
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                complete.set(true)
                tempFile.delete()
                _streamUrlsCache.value = _streamUrlsCache.value + (
                    itemId to StreamUrlsState.Error(e.message ?: "Download failed")
                )
            }
        }
    }

    fun downloadItem(item: MediaItem) {
        if (item.contentUri.isNotBlank()) return
        if (item.telegramFileId == null) return
        viewModelScope.launch {
            _downloadState.value = DownloadState.Downloading
            val result = withContext(Dispatchers.IO) {
                downloadCloudItem(item.id)
            }
            _downloadState.value = when {
                result.isSuccess -> DownloadState.Done
                else -> DownloadState.Error(result.exceptionOrNull()?.message ?: "Download failed")
            }
        }
    }

    fun clearDownloadState() {
        _downloadState.value = null
    }
}
