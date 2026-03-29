package com.ulap.ui.gallery

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.DataSource
import com.ulap.data.local.dao.ChunkMetadataDao
import com.ulap.data.remote.CHUNKED_FILE_ID_PREFIX
import com.ulap.data.remote.ParallelChunkDownloader
import com.ulap.data.remote.ParallelChunkDownloader.Companion.chunkDirFor
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
/** Max total size for completed `ulap_stream_*.mp4` files in cacheDir. */
internal const val STREAM_CACHE_MAX_BYTES = 500L * 1024 * 1024 // 500 MB (increased for larger files)

/** Completed stream files older than this are evicted regardless of total size. */
internal const val STREAM_CACHE_TTL_MS = 24L * 60 * 60 * 1000 // 24 hours

/** Sanitizes [itemId] to a filesystem-safe suffix (strips any path separators). */
private fun streamFileName(itemId: String) = "ulap_stream_${itemId.replace('/', '_').replace('\\', '_')}.mp4"
private fun streamMarkerName(itemId: String) = "ulap_stream_${itemId.replace('/', '_').replace('\\', '_')}.done"

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
    private val parallelDownloader: ParallelChunkDownloader,
    private val chunkMetadataDao: ChunkMetadataDao,
    private val downloadCloudItem: DownloadCloudItemUseCase,
    private val okHttpClient: okhttp3.OkHttpClient,
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
                        // Legacy chunked: JSON array "[...]"
                        val isLegacyChunked = fileId.trim().startsWith("[")
                        // New chunked: sentinel "chunked:N"
                        val isNewChunked = fileId.startsWith(CHUNKED_FILE_ID_PREFIX)
                        val isVideo = item.mediaType == MediaType.VIDEO

                        val itemToken = getCredentials.getTokenForBot(item.uploadBotIndex)
                        if (itemToken == null) {
                            _streamUrlsCache.value = _streamUrlsCache.value + (
                                item.id to StreamUrlsState.Error("Bot at index ${item.uploadBotIndex} not configured")
                            )
                            continue
                        }

                        when {
                            isNewChunked && isVideo -> {
                                startPrefetchingChunkedDownload(itemToken, item.id)
                            }
                            isLegacyChunked && isVideo -> {
                                startProgressiveChunkedDownload(itemToken, fileId, item.id)
                            }
                            else -> {
                                val urls = try {
                                    withContext(Dispatchers.IO) {
                                        downloader.resolveStreamUrls(itemToken, fileId)
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
                }
            } catch (_: Exception) {
                // Swallow flow errors to prevent crashing the app
            }
        }
    }

    /**
     * New-style progressive download for items using the chunk_metadata table.
     * Resolves chunk URLs in batches, creates [ChunkPrefetchEngine], and emits
     * [StreamUrlsState.ReadyProgressive] with a [PrefetchingVideoDataSource.Factory].
     */
    private fun startPrefetchingChunkedDownload(token: String, itemId: String) {
        val chunkDir = chunkDirFor(appContext.cacheDir, itemId)

        viewModelScope.launch(Dispatchers.IO) {
            evictStreamCache(activeItemId = itemId)

            try {
                val chunks = chunkMetadataDao.getChunksForMedia(itemId)
                if (chunks.isEmpty()) {
                    _streamUrlsCache.value = _streamUrlsCache.value + (
                        itemId to StreamUrlsState.Error("No chunk metadata found")
                    )
                    return@launch
                }

                val fileIds = chunks.map { it.telegramFileId }
                val urls = downloader.resolveStreamUrlsBatched(token, fileIds)

                if (urls.all { it == null }) {
                    _streamUrlsCache.value = _streamUrlsCache.value + (
                        itemId to StreamUrlsState.Error("Could not resolve chunk URLs")
                    )
                    return@launch
                }

                // Inject the OkHttpClient from the existing downloader's transport.
                // We obtain it via Hilt injection in the constructor.
                val prefetchEngine = ChunkPrefetchEngine(
                    chunkDir = chunkDir,
                    chunkMeta = chunks,
                    resolvedUrls = urls,
                    okHttpClient = okHttpClient,
                )

                val factory = PrefetchingVideoDataSource.Factory(chunkDir, chunks, prefetchEngine)
                _streamUrlsCache.value = _streamUrlsCache.value + (
                    itemId to StreamUrlsState.ReadyProgressive(
                        fileUri = chunkDir.toURI().toString(),
                        dataSourceFactory = factory,
                    )
                )
                // Start prefetch of first chunks.
                prefetchEngine.setPrefetchOrigin(0)

            } catch (e: Exception) {
                _streamUrlsCache.value = _streamUrlsCache.value + (
                    itemId to StreamUrlsState.Error(e.message ?: "Prefetch setup failed")
                )
            }
        }
    }

    /**
     * Resolves all chunk CDN URLs in parallel, then immediately emits
     * [StreamUrlsState.ReadyProgressive] and starts writing bytes — so ExoPlayer receives
     * its first data within milliseconds of opening the source, with no polling-against-zero gap.
     *
     * If the file was already fully downloaded in a previous session, emits
     * [StreamUrlsState.Ready] with a direct file URI instead — no network needed.
     */
    private fun startProgressiveChunkedDownload(token: String, fileId: String, itemId: String) {
        val tempFile = File(appContext.cacheDir, streamFileName(itemId))
        val markerFile = File(appContext.cacheDir, streamMarkerName(itemId))

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

        viewModelScope.launch(Dispatchers.IO) {
            // Evict old stream cache files on IO thread before writing a new one.
            evictStreamCache(activeItemId = itemId)

            try {
                // Resolve all chunk URLs in parallel (1 round-trip total instead of N serial).
                val chunkUrls = downloader.resolveStreamUrls(token, fileId)
                if (chunkUrls.isEmpty()) {
                    complete.set(true)
                    _streamUrlsCache.value = _streamUrlsCache.value + (
                        itemId to StreamUrlsState.Error("Could not resolve stream URLs")
                    )
                    return@launch
                }

                // Open the file and emit ReadyProgressive together: by the time ExoPlayer's
                // Loader thread calls read(), the download loop below is already writing bytes.
                // This eliminates the polling-against-zero gap that caused slow start.
                val fos = FileOutputStream(tempFile)
                _streamUrlsCache.value = _streamUrlsCache.value + (
                    itemId to StreamUrlsState.ReadyProgressive(fileUri, factory)
                )

                fos.use {
                    val progressStream = object : java.io.OutputStream() {
                        override fun write(b: Int) {
                            fos.write(b)
                            written.incrementAndGet()
                        }
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            fos.write(b, off, len)
                            written.addAndGet(len.toLong())
                        }
                        override fun flush() = fos.flush()
                        // close() intentionally left to fos.use {} — no double-close
                        override fun close() = Unit
                    }
                    val result = downloader.downloadFromUrls(chunkUrls, progressStream)
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

    /**
     * Evicts completed stream cache files to keep total size within [STREAM_CACHE_MAX_BYTES].
     * Also evicts completed files older than [STREAM_CACHE_TTL_MS].
     *
     * Only files with a `.done` marker are considered evictable. Files belonging to
     * [activeItemId] or any item currently Loading/ReadyProgressive/Ready in [_streamUrlsCache]
     * are protected from deletion while in use.
     *
     * Must be called from a background thread (performs blocking file I/O).
     */
    private fun evictStreamCache(activeItemId: String? = null) {
        val cacheDir = appContext.cacheDir
        val streamFiles = cacheDir.listFiles { f ->
            f.name.startsWith("ulap_stream_") && f.name.endsWith(".mp4")
        } ?: return

        val now = System.currentTimeMillis()
        val activeCache = _streamUrlsCache.value

        // Build set of protected item IDs (Loading, ReadyProgressive, or Ready = actively playing).
        val activeIds = buildSet<String> {
            if (activeItemId != null) add(activeItemId)
            activeCache.entries.forEach { (id, state) ->
                if (state is StreamUrlsState.Loading ||
                    state is StreamUrlsState.ReadyProgressive ||
                    state is StreamUrlsState.Ready) {
                    add(id)
                }
            }
        }
        // Convert to filenames for fast lookup (handles both old hash-based and new id-based names).
        val activeFileNames = activeIds.flatMap { id ->
            listOf(streamFileName(id), "ulap_stream_${id.hashCode()}.mp4")
        }.toSet()

        // TTL eviction: delete completed files older than the TTL, skipping active ones.
        for (f in streamFiles) {
            if (f.name in activeFileNames) continue
            val markerName = f.name.removeSuffix(".mp4") + ".done"
            val marker = File(cacheDir, markerName)
            if (marker.exists() && (now - f.lastModified()) > STREAM_CACHE_TTL_MS) {
                f.delete()
                marker.delete()
            }
        }

        // Size-cap eviction: enumerate remaining completed files and evict oldest first.
        val remaining = cacheDir.listFiles { f ->
            f.name.startsWith("ulap_stream_") && f.name.endsWith(".mp4")
        } ?: return

        // Only count completed (has .done marker) files toward the cap.
        val completed = remaining.filter { f ->
            val markerName = f.name.removeSuffix(".mp4") + ".done"
            File(cacheDir, markerName).exists()
        }

        var totalBytes = completed.sumOf { it.length() }
        if (totalBytes <= STREAM_CACHE_MAX_BYTES) return

        val evictable = completed
            .filter { f -> f.name !in activeFileNames }
            .sortedBy { it.lastModified() }

        for (f in evictable) {
            if (totalBytes <= STREAM_CACHE_MAX_BYTES) break
            val markerName = f.name.removeSuffix(".mp4") + ".done"
            val marker = File(cacheDir, markerName)
            val fileSize = f.length()
            if (f.delete()) {
                totalBytes -= fileSize
                marker.delete()
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
