package com.ulap.ui.gallery

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.datasource.DataSource
import com.ulap.data.local.dao.ChunkMetadataDao
import com.ulap.data.local.dao.MediaItemDao
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
import com.ulap.domain.usecase.RepairCorruptChunkMetadataFromPinnedIndexUseCase
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

// #region agent log
private fun dbg(loc: String, msg: String, vararg kv: Pair<String, Any?>) {
    val data = kv.joinToString(", ") { "${it.first}=${it.second}" }
    Log.w("DBG_5f6b53", "[$loc] $msg | $data | thread=${Thread.currentThread().name}")
}
// #endregion
/** Max total size for completed `ulap_stream_*.mp4` files in cacheDir. */
internal const val STREAM_CACHE_MAX_BYTES = 500L * 1024 * 1024 // 500 MB (increased for larger files)

/** Completed stream files older than this are evicted regardless of total size. */
internal const val STREAM_CACHE_TTL_MS = 24L * 60 * 60 * 1000 // 24 hours

/** Sanitizes [itemId] to a filesystem-safe suffix (strips any path separators). */
private fun streamFileName(itemId: String) = "ulap_stream_${itemId.replace('/', '_').replace('\\', '_')}.mp4"
private fun streamMarkerName(itemId: String) = "ulap_stream_${itemId.replace('/', '_').replace('\\', '_')}.done"

/** Canonical error for when chunk_metadata is empty during playback (DB wipe / missing sync). */
internal fun streamErrorForMissingChunkMetadata(): StreamUrlsState.Error =
    StreamUrlsState.Error("Chunk data missing — please re-download this video.")

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
    private val mediaItemDao: MediaItemDao,
    private val downloadCloudItem: DownloadCloudItemUseCase,
    private val repairChunkMetadata: RepairCorruptChunkMetadataFromPinnedIndexUseCase,
    private val okHttpClient: okhttp3.OkHttpClient,
    @ApplicationContext private val appContext: Context,
    private val debugLog: com.ulap.debug.DebugLogBuffer,
    private val telegramLogger: com.ulap.data.remote.TelegramLogger,
) : ViewModel() {

    private val mediaId: String = savedStateHandle.get<String>("mediaId") ?: ""

    private val playbackStateStore = VideoPlaybackStateStore(savedStateHandle)

    fun saveVideoPosition(itemId: String, positionMs: Long, isPlaying: Boolean) {
        playbackStateStore.save(itemId, positionMs, isPlaying)
    }

    fun getVideoStartPosition(itemId: String): Long = playbackStateStore.getPosition(itemId)

    fun getVideoStartIsPlaying(itemId: String): Boolean = playbackStateStore.getIsPlaying(itemId)

    private val _downloadState = MutableStateFlow<DownloadState?>(null)
    val downloadState: StateFlow<DownloadState?> = _downloadState.asStateFlow()

    val allItems: StateFlow<List<MediaItem>> = getTimeline()
        .map { items -> items.sortedByDescending { it.dateTaken } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _currentPage = MutableStateFlow(0)
    private val _streamUrlsCache = MutableStateFlow<Map<String, StreamUrlsState>>(emptyMap())
    val streamUrlsCache: StateFlow<Map<String, StreamUrlsState>> = _streamUrlsCache.asStateFlow()

    /** The single active prefetch engine; released when the user navigates away or the viewer closes. */
    private var activePrefetchEngine: ChunkPrefetchEngine? = null
    private var activePrefetchItemId: String? = null

    fun setCurrentPage(page: Int) {
        // #region agent log
        dbg("VM.setCurrentPage", "page=$page")
        // #endregion
        _currentPage.value = page
    }

    init {
        viewModelScope.launch {
            // #region agent log
            dbg("VM.init", "FLOW_LAUNCH_START")
            // #endregion
            try {
                combine(allItems, _currentPage) { items, currentPage ->
                    // #region agent log
                    dbg("VM.init.transform", "COMBINE_EMIT", "itemCount" to items.size, "currentPage" to currentPage)
                    // #endregion
                    if (items.isEmpty()) null
                    else {
                        // Only the active page: avoid prefetching / URL resolution for adjacent items.
                        val idx = currentPage.coerceIn(0, items.lastIndex)
                        val windowIds = listOf(items[idx].id)
                        Triple(items, windowIds, _streamUrlsCache.value)
                    }
                }.collect { value ->
                    // #region agent log
                    dbg("VM.init.collect", "COLLECT_CALLED", "valueNull" to (value == null))
                    // #endregion
                    if (value == null) return@collect
                    val (items, windowIds, cache) = value
                    // Guard: primary token must exist for any resolution to work.
                    getCredentials.getToken() ?: return@collect

                    // #region agent log
                    val currentItem = windowIds.firstOrNull()?.let { wid -> items.find { it.id == wid } }
                    if (currentItem != null) {
                        dbg("VM.init.collect", "ITEM_STATE",
                            "id" to currentItem.id.takeLast(20),
                            "hasFileId" to (currentItem.telegramFileId != null),
                            "contentUri" to currentItem.contentUri.take(30),
                            "backupStatus" to currentItem.backupStatus,
                            "mediaType" to currentItem.mediaType,
                            "cacheState" to cache[currentItem.id]?.javaClass?.simpleName
                        )
                    }
                    // #endregion

                    // All candidates: in the viewport window, have a fileId, and are not already
                    // being resolved or successfully resolved.
                    val candidates = windowIds.mapNotNull { id -> items.find { it.id == id } }
                        .filter { item ->
                            item.telegramFileId != null &&
                                cache[item.id] !is StreamUrlsState.Ready &&
                                cache[item.id] !is StreamUrlsState.ReadyProgressive &&
                                cache[item.id] !is StreamUrlsState.Loading
                        }

                    val unplayable = windowIds.mapNotNull { id -> items.find { it.id == id } }
                        .filter { item ->
                            item.telegramFileId == null &&
                                item.contentUri.isBlank() &&
                                cache[item.id] !is StreamUrlsState.Error
                        }
                    if (unplayable.isNotEmpty()) {
                        var newC = _streamUrlsCache.value
                        for (item in unplayable) {
                            newC = newC + (item.id to StreamUrlsState.Error("No cloud backup available"))
                        }
                        _streamUrlsCache.value = newC
                    }

                    // Items that are already marked as cloud-only with no local file.
                    val clearCloudOnly = candidates.filter { item ->
                        item.contentUri.isBlank() && item.backupStatus == BackupStatus.CLOUD_ONLY
                    }

                    // Items that still have a local URI but may have been deleted from the device.
                    val potentiallyStale = candidates.filter { item ->
                        item.contentUri.isNotBlank() && item.backupStatus == BackupStatus.BACKED_UP
                    }

                    // Probe accessibility on IO; fix the DB for any confirmed-stale items so
                    // future opens skip the local-file branch immediately.
                    val staleItems: List<MediaItem> = if (potentiallyStale.isNotEmpty()) {
                        withContext(Dispatchers.IO) {
                            val stale = potentiallyStale.filter { !isContentUriAccessible(it.contentUri) }
                            if (stale.isNotEmpty()) {
                                mediaItemDao.markAsCloudOnly(stale.map { it.id })
                            }
                            stale
                        }
                    } else emptyList()

                    val toFetch = clearCloudOnly + staleItems
                    if (toFetch.isEmpty()) {
                        // #region agent log
                        val firstItem = windowIds.firstOrNull()?.let { wid -> items.find { it.id == wid } }
                        dbg("VM.init.collect", "EMPTY_FETCH",
                            "candidates" to candidates.size,
                            "clearCloudOnly" to clearCloudOnly.size,
                            "potentiallyStale" to potentiallyStale.size,
                            "staleItems" to staleItems.size,
                            "firstItemId" to (firstItem?.id?.takeLast(20) ?: "null"),
                            "firstItemFileId" to (firstItem?.telegramFileId != null),
                            "firstItemUri" to (firstItem?.contentUri?.take(30) ?: "null"),
                            "firstItemBackup" to (firstItem?.backupStatus?.toString() ?: "null"),
                            "firstItemCacheState" to (cache[firstItem?.id]?.javaClass?.simpleName ?: "null")
                        )
                        // #endregion
                        return@collect
                    }
                    // #region agent log
                    dbg("VM.init", "TO_FETCH", "count" to toFetch.size, "ids" to toFetch.map { it.id.takeLast(20) }.toString())
                    // #endregion

                    var newCache = cache
                    for (item in toFetch) {
                        newCache = newCache + (item.id to StreamUrlsState.Loading)
                    }
                    _streamUrlsCache.value = newCache

                    for (item in toFetch) {
                        resolveStreamUrlsForItem(item)
                    }
                }
            } catch (e: Exception) {
                // #region agent log
                dbg("VM.init", "FLOW_EXCEPTION", "type" to e.javaClass.simpleName, "msg" to (e.message?.take(100) ?: "null"))
                // #endregion
            }
        }
    }

    /**
     * Dispatches cloud URL resolution for [item] based on its [MediaItem.telegramFileId] format.
     * Spawns its own coroutine via [viewModelScope] in all branches so callers are non-blocking.
     *
     * Layer 4 (token fallback): if the item's specific bot is not configured, falls back to the
     * primary bot token rather than immediately emitting an error.
     */
    private fun resolveStreamUrlsForItem(item: MediaItem) {
        val fileId = item.telegramFileId ?: return
        val isLegacyChunked = fileId.trim().startsWith("[")
        val isNewChunked = fileId.startsWith(CHUNKED_FILE_ID_PREFIX)
        val isVideo = item.mediaType == MediaType.VIDEO
        Log.d("UlapChunkPlay", "resolveStreamUrlsForItem id=${item.id} fileId=${fileId.take(30)} isNewChunked=$isNewChunked isVideo=$isVideo bot=${item.uploadBotIndex}")
        debugLog.log("ChunkPlay", "resolve id=${item.id} fileId=${fileId.take(30)} isNewChunked=$isNewChunked")
        // #region agent log
        dbg("VM.resolveStreamUrls", "ENTER", "itemId" to item.id, "isNewChunked" to isNewChunked, "isLegacyChunked" to isLegacyChunked, "isVideo" to isVideo, "fileIdPrefix" to fileId.take(20), "backupStatus" to item.backupStatus, "contentUri" to item.contentUri.take(30))
        // #endregion

        val itemToken = getCredentials.getTokenForBot(item.uploadBotIndex)
            ?: getCredentials.getToken()
        if (itemToken == null) {
            Log.e("UlapChunkPlay", "No token for bot index ${item.uploadBotIndex}")
            _streamUrlsCache.value = _streamUrlsCache.value + (
                item.id to StreamUrlsState.Error("No bot token configured")
            )
            return
        }
        // #region agent log
        dbg("VM.resolveStreamUrls", "TOKEN_RESOLVED", "itemId" to item.id.takeLast(20), "botIndex" to item.uploadBotIndex, "tokenPrefix" to itemToken.take(10), "usedFallback" to (getCredentials.getTokenForBot(item.uploadBotIndex) == null))
        // #endregion

        when {
            isNewChunked && isVideo -> {
                startPrefetchingChunkedDownload(itemToken, item.id)
            }
            isLegacyChunked && isVideo -> {
                startProgressiveChunkedDownload(itemToken, fileId, item.id)
            }
            else -> viewModelScope.launch {
                var urls = try {
                    withContext(Dispatchers.IO) {
                        downloader.resolveStreamUrls(itemToken, fileId)
                    }
                } catch (_: Exception) {
                    emptyList()
                }
                if (urls.isEmpty()) {
                    for (botIdx in 0..5) {
                        val tryToken = getCredentials.getTokenForBot(botIdx) ?: continue
                        if (tryToken == itemToken) continue
                        urls = try {
                            withContext(Dispatchers.IO) {
                                downloader.resolveStreamUrls(tryToken, fileId)
                            }
                        } catch (_: Exception) { emptyList() }
                        if (urls.isNotEmpty()) break
                    }
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

    /**
     * Called when ExoPlayer reports a playback error for a local-URI item (Layer 2 fallback).
     *
     * Immediately shows a spinner (Loading state) so the UI is responsive, then fixes the
     * database by marking the item CLOUD_ONLY, and dispatches cloud URL resolution.
     * If the item has no Telegram backup, a permanent error is shown instead.
     */
    fun onLocalPlaybackError(item: MediaItem) {
        if (item.telegramFileId == null) return
        // Switch UI to spinner immediately so the black ExoPlayer surface disappears.
        _streamUrlsCache.value = _streamUrlsCache.value + (item.id to StreamUrlsState.Loading)
        // Persist the corrected state so future opens skip the dead local-file branch.
        viewModelScope.launch(Dispatchers.IO) {
            mediaItemDao.markAsCloudOnly(listOf(item.id))
        }
        resolveStreamUrlsForItem(item)
    }

    /**
     * Called when ExoPlayer reports a playback error for a cloud-streaming item (StreamUrlsState.Ready path).
     *
     * Re-resolves the CDN URL immediately, which obtains a fresh link from Telegram's API.
     * This handles the most common failure mode: stale/expired CDN URLs.
     * If re-resolution also fails, resolveStreamUrlsForItem emits StreamUrlsState.Error.
     */
    fun onCloudPlaybackError(item: MediaItem, error: androidx.media3.common.PlaybackException) {
        Log.e("UlapChunkPlay", "onCloudPlaybackError id=${item.id} code=${error.errorCodeName} msg=${error.message}", error)
        // #region agent log
        dbg("VM.onCloudPlaybackError", "ENTER", "itemId" to item.id, "errorCode" to error.errorCodeName, "errorMsg" to error.message, "causeMsg" to error.cause?.message)
        // #endregion
        if (item.telegramFileId == null) {
            _streamUrlsCache.value = _streamUrlsCache.value + (item.id to StreamUrlsState.Error("Video unavailable"))
            return
        }

        val currentState = _streamUrlsCache.value[item.id]
        if (currentState is StreamUrlsState.Loading) return

        // Chunked (ReadyProgressive) items cannot be fixed by re-resolving URLs — the DataSource
        // reads from local chunk files, so a retry would just hit the same error. Surface it.
        if (currentState is StreamUrlsState.ReadyProgressive) {
            val msg = friendlyPlaybackErrorMessage(error)
            _streamUrlsCache.value = _streamUrlsCache.value + (
                item.id to StreamUrlsState.Error(msg)
            )
            debugLog.log("VideoPlayer", "Chunked playback failed id=${item.id} code=${error.errorCodeName}")
            viewModelScope.launch {
                telegramLogger.flushNow()
            }
            return
        }

        // For single-URL streaming (Ready), re-resolve the CDN URL (it may have expired).
        _streamUrlsCache.value = _streamUrlsCache.value + (item.id to StreamUrlsState.Loading)
        debugLog.log("VideoPlayer", "Cloud playback error id=${item.id} code=${error.errorCodeName}")
        viewModelScope.launch { telegramLogger.flushNow() }
        resolveStreamUrlsForItem(item)
    }

    /**
     * Returns true if [uriString] points to a file the ContentResolver can open.
     * Must be called from a background (IO) thread.
     */
    private fun isContentUriAccessible(uriString: String): Boolean {
        return try {
            appContext.contentResolver.openInputStream(Uri.parse(uriString))?.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * New-style progressive download for items using the chunk_metadata table.
     * Resolves chunk URLs in batches, creates [ChunkPrefetchEngine], and emits
     * [StreamUrlsState.ReadyProgressive] with a [PrefetchingVideoDataSource.Factory].
     */
    private fun startPrefetchingChunkedDownload(token: String, itemId: String) {
        val chunkDir = chunkDirFor(appContext.cacheDir, itemId)
        debugLog.log("ChunkPrefetch", "start itemId=$itemId chunkDir=$chunkDir")
        // #region agent log
        dbg("VM.startPrefetch", "ENTER", "itemId" to itemId, "activeEngineId" to activePrefetchItemId)
        // #endregion

        viewModelScope.launch(Dispatchers.IO) {
            evictStreamCache(activeItemId = itemId)

            try {
                var chunks = chunkMetadataDao.getChunksForMedia(itemId)
                debugLog.log("ChunkPrefetch", "chunks from DB: ${chunks.size} for itemId=$itemId")
                // #region agent log
                dbg("VM.startPrefetch", "CHUNKS_FROM_DB", "itemId" to itemId, "count" to chunks.size)
                if (chunks.isNotEmpty()) {
                    val first = chunks.first()
                    val last = chunks.last()
                    dbg("VM.startPrefetch", "CHUNK_DETAIL",
                        "firstIdx" to first.chunkIndex, "firstOffset" to first.byteOffset, "firstLen" to first.byteLength, "firstFileId" to first.telegramFileId.take(30),
                        "lastIdx" to last.chunkIndex, "lastOffset" to last.byteOffset, "lastLen" to last.byteLength, "lastFileId" to last.telegramFileId.take(30),
                        "totalSize" to chunks.sumOf { it.byteLength.toLong() }
                    )
                }
                // #endregion
                if (chunks.isEmpty()) {
                    runCatching { repairChunkMetadata() }
                    chunks = chunkMetadataDao.getChunksForMedia(itemId)
                    debugLog.log("ChunkPrefetch", "after repair: ${chunks.size} chunks for itemId=$itemId")
                }
                if (chunks.isEmpty()) {
                    debugLog.log("ChunkPrefetch", "chunk metadata empty for itemId=$itemId")
                    // #region agent log
                    dbg("VM.startPrefetch", "NO_CHUNKS", "itemId" to itemId)
                    // #endregion
                    viewModelScope.launch { telegramLogger.flushNow() }
                    _streamUrlsCache.value = _streamUrlsCache.value + (
                        itemId to streamErrorForMissingChunkMetadata()
                    )
                    return@launch
                }

                var resolvedToken = token
                val urlResolver: suspend (Int) -> String = resolver@{ index ->
                    val fileId = chunks.getOrNull(index)?.telegramFileId
                        ?: throw java.io.IOException("No metadata for chunk $index")
                    val url = downloader.resolveStreamUrl(resolvedToken, fileId)
                    if (url != null) return@resolver url

                    // #region agent log
                    dbg("VM.urlResolver", "FALLBACK_START", "index" to index, "assignedToken" to resolvedToken.take(15))
                    // #endregion
                    for (botIdx in 0..5) {
                        val tryToken = getCredentials.getTokenForBot(botIdx) ?: continue
                        if (tryToken == resolvedToken) continue
                        val fallbackUrl = downloader.resolveStreamUrl(tryToken, fileId)
                        if (fallbackUrl != null) {
                            // #region agent log
                            dbg("VM.urlResolver", "FALLBACK_HIT", "index" to index, "botIdx" to botIdx, "tokenPrefix" to tryToken.take(15))
                            // #endregion
                            resolvedToken = tryToken
                            return@resolver fallbackUrl
                        }
                    }
                    throw java.io.IOException("Failed to resolve URL for chunk $index (all bots tried)")
                }
                val prefetchEngine = ChunkPrefetchEngine(
                    chunkDir = chunkDir,
                    chunkMeta = chunks,
                    urlResolver = urlResolver,
                    okHttpClient = okHttpClient,
                    logCallback = { msg -> debugLog.log("ChunkDL", msg) },
                )

                // #region agent log
                dbg("VM.startPrefetch", "BEFORE_RELEASE_OLD_ENGINE", "itemId" to itemId, "oldActiveId" to activePrefetchItemId, "oldEngineNull" to (activePrefetchEngine == null))
                // #endregion
                releaseActivePrefetchEngine()
                activePrefetchEngine = prefetchEngine
                activePrefetchItemId = itemId
                // #region agent log
                dbg("VM.startPrefetch", "ENGINE_STORED", "itemId" to itemId)
                // #endregion

                val factory = PrefetchingVideoDataSource.Factory(chunkDir, chunks, prefetchEngine)
                debugLog.log("ChunkPrefetch", "emitting ReadyProgressive for itemId=$itemId chunkCount=${chunks.size} totalSize=${chunks.sumOf { it.byteLength.toLong() }}")
                // #region agent log
                dbg("VM.startPrefetch", "EMITTING_READY_PROGRESSIVE", "itemId" to itemId, "chunkCount" to chunks.size)
                // #endregion
                viewModelScope.launch { telegramLogger.flushNow() }
                _streamUrlsCache.value = _streamUrlsCache.value + (
                    itemId to StreamUrlsState.ReadyProgressive(
                        fileUri = chunkDir.toURI().toString(),
                        dataSourceFactory = factory,
                    )
                )

            } catch (e: Exception) {
                Log.e("UlapChunkPlay", "prefetch setup failed for itemId=$itemId", e)
                debugLog.log("ChunkPrefetch", "EXCEPTION for itemId=$itemId: ${e.javaClass.simpleName}: ${e.message}")
                // #region agent log
                dbg("VM.startPrefetch", "EXCEPTION", "itemId" to itemId, "error" to "${e.javaClass.simpleName}: ${e.message}")
                // #endregion
                viewModelScope.launch { telegramLogger.flushNow() }
                _streamUrlsCache.value = _streamUrlsCache.value + (
                    itemId to StreamUrlsState.Error("Video could not be prepared. Please try again.")
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

    fun onVideoOpened(item: com.ulap.domain.model.MediaItem) {
        debugLog.log("VideoPlayer", "VIDEO OPENED: id=${item.id} name=${item.fileName} type=${item.mediaType}")
        viewModelScope.launch { telegramLogger.flushNow() }
    }

    fun onVideoClosed(item: com.ulap.domain.model.MediaItem) {
        debugLog.log("VideoPlayer", "VIDEO CLOSED: id=${item.id} name=${item.fileName}")
        // #region agent log
        dbg("VM.onVideoClosed", "ENTER", "itemId" to item.id, "activeEngineId" to activePrefetchItemId, "match" to (activePrefetchItemId == item.id))
        // #endregion
        if (activePrefetchItemId == item.id) {
            releaseActivePrefetchEngine()
        }
        viewModelScope.launch { telegramLogger.flushNow() }
    }

    fun onVideoPlayerState(item: com.ulap.domain.model.MediaItem, description: String) {
        debugLog.log("VideoPlayer", "state id=${item.id}: $description")
    }

    fun onVideoError(item: com.ulap.domain.model.MediaItem, error: androidx.media3.common.PlaybackException) {
        Log.e("UlapChunkPlay", "onVideoError id=${item.id} code=${error.errorCodeName} msg=${error.message}", error)
        // #region agent log
        dbg("VM.onVideoError", "ERROR", "itemId" to item.id, "errorCode" to error.errorCodeName, "msg" to error.message, "causeMsg" to error.cause?.message, "causeOfCauseMsg" to error.cause?.cause?.message)
        // #endregion
        debugLog.log("VideoPlayer", "ERROR id=${item.id} code=${error.errorCodeName} msg=${error.message}")
        viewModelScope.launch { telegramLogger.flushNow() }
    }

    private fun friendlyPlaybackErrorMessage(error: androidx.media3.common.PlaybackException): String {
        val raw = error.message ?: ""
        if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
            && raw.contains("NO_EXCEEDS_CAPABILITIES", ignoreCase = true)
        ) {
            return "This video's resolution exceeds your device's playback capabilities.\n\nTry downloading it instead."
        }
        if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
            return "Your device cannot decode this video format.\n\nTry downloading it instead."
        }
        return "Playback failed: ${error.errorCodeName}"
    }

    private fun releaseActivePrefetchEngine() {
        // #region agent log
        dbg("VM.releaseEngine", "ENTER", "activeId" to activePrefetchItemId, "engineNull" to (activePrefetchEngine == null))
        // #endregion
        activePrefetchEngine?.let { engine ->
            debugLog.log("ChunkPrefetch", "releasing engine for itemId=$activePrefetchItemId")
            // #region agent log
            dbg("VM.releaseEngine", "CALLING_RELEASE", "activeId" to activePrefetchItemId)
            // #endregion
            engine.release()
        }
        activePrefetchEngine = null
        activePrefetchItemId = null
    }

    override fun onCleared() {
        releaseActivePrefetchEngine()
        super.onCleared()
    }
}
