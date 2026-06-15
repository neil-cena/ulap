package com.ulap.ui.gallery

import android.content.Context
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ulap.R
import com.ulap.data.local.ThumbnailUrlCache
import com.ulap.data.remote.TelegramDownloader
import com.ulap.data.repository.UserPreferencesRepository
import com.ulap.debug.DebugLogBuffer
import com.ulap.domain.model.BackupStatus
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.TimelineGroup
import com.ulap.domain.usecase.DownloadCloudItemUseCase
import com.ulap.domain.usecase.FetchIndexFromPinnedMessageUseCase
import com.ulap.domain.usecase.GetCredentialsUseCase
import com.ulap.domain.usecase.GetTimelineUseCase
import com.ulap.domain.usecase.MarkAsCloudOnlyUseCase
import com.ulap.domain.usecase.RefreshFoldersUseCase
import com.ulap.domain.usecase.RemoveLocalMediaFileUseCase
import com.ulap.domain.usecase.RemoveLocalMediaOutcome
import com.ulap.domain.usecase.ScanMediaUseCase
import com.ulap.domain.usecase.DeleteFileFromTelegramUseCase
import com.ulap.domain.usecase.DeleteFileResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

data class RemoveFromDeviceConfirmationState(
    val deleteSender: IntentSender? = null,
    val pendingMediaItemId: String? = null,
)

@HiltViewModel
class TimelineViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val getTimeline: GetTimelineUseCase,
    private val scanMedia: ScanMediaUseCase,
    private val getCredentials: GetCredentialsUseCase,
    private val downloader: TelegramDownloader,
    private val fetchIndex: FetchIndexFromPinnedMessageUseCase,
    private val refreshFolders: RefreshFoldersUseCase,
    private val thumbnailUrlCache: ThumbnailUrlCache,
    private val debugLog: DebugLogBuffer,
    private val userPrefs: UserPreferencesRepository,
    private val downloadCloudItem: DownloadCloudItemUseCase,
    private val removeLocalMediaFile: RemoveLocalMediaFileUseCase,
    private val markAsCloudOnly: MarkAsCloudOnlyUseCase,
    private val deleteFileFromTelegram: DeleteFileFromTelegramUseCase,
) : ViewModel() {

    private val streamUrlCache = MutableStateFlow<Map<String, String>>(thumbnailUrlCache.getAll())
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private val _refreshCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshCompleted: SharedFlow<Unit> = _refreshCompleted.asSharedFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    private val _removeFromDeviceConfirmation = MutableStateFlow(RemoveFromDeviceConfirmationState())
    val removeFromDeviceConfirmation: StateFlow<RemoveFromDeviceConfirmationState> =
        _removeFromDeviceConfirmation.asStateFlow()

    val viewMode: StateFlow<TimelineViewMode> = userPrefs.timelineViewMode

    val groups: StateFlow<List<TimelineGroup>> = combine(
        getTimeline().map { groupByTimelineLabel(it) },
        streamUrlCache,
    ) { list, cache ->
        list.map { group ->
            TimelineGroup(
                label = group.label,
                items = group.items.map { item ->
                    val resolved = cache[item.id] ?: item.remoteThumbnailUrl
                    item.copy(streamUrl = resolved)
                },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setViewMode(mode: TimelineViewMode) {
        userPrefs.setTimelineViewMode(mode)
    }

    init {
        // Defer blocking so first paint happens quickly (splash → main screen); show loading
        // skeleton until groups flow emits. Heavy work (fetchIndex, scanMedia) runs in the
        // second launch below.
        viewModelScope.launch {
            kotlinx.coroutines.delay(100)
            _isLoading.value = false
        }
        // Defer heavy work so first paint is not blocked.
        viewModelScope.launch {
            try {
                val currentItems = try { getTimeline().first() } catch (_: Exception) { emptyList() }
                debugLog.log("Timeline", "init: background — ${currentItems.size} items in DB")
                if (getCredentials.hasCredentials() && currentItems.isEmpty()) {
                    debugLog.log("Timeline", "init: fetching index")
                    try { fetchIndex() } catch (e: Exception) { debugLog.log("Timeline", "init fetchIndex error: ${e.message}") }
                    try { refreshFolders() } catch (e: Exception) { debugLog.log("Timeline", "init refreshFolders error: ${e.message}") }
                }
                debugLog.log("Timeline", "init: scanMedia(fullScan=true)")
                try { scanMedia(fullScan = true) } catch (e: Exception) { debugLog.log("Timeline", "init scanMedia error: ${e.message}") }
                debugLog.log("Timeline", "init: background complete — ${groups.value.sumOf { it.items.size }} total items")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
        // transformLatest cancels any in-flight batch when a new DB emission arrives,
        // preventing overlapping fetch loops on rapid updates (e.g. during full scan).
        viewModelScope.launch {
            try {
                getTimeline().transformLatest<List<MediaItem>, Unit> { items ->
                    val primaryToken = getCredentials.getToken() ?: return@transformLatest
                    var cache = streamUrlCache.value
                    val uncached = items
                        .filter {
                            it.backupStatus == BackupStatus.CLOUD_ONLY &&
                                (it.thumbnailFileId != null || it.telegramFileId != null) &&
                                cache[it.id] == null &&
                                it.remoteThumbnailUrl.isNullOrBlank()
                        }
                        .distinctBy { it.id }
                    for (batch in uncached.chunked(5)) {
                        val results = coroutineScope {
                            batch.map { item ->
                                async(Dispatchers.IO) {
                                    val fileId = item.thumbnailFileId ?: item.telegramFileId ?: return@async null
                                    // Use the bot that uploaded this item; fall back to primary.
                                    val token = getCredentials.getTokenForBot(item.uploadBotIndex)
                                        ?: primaryToken
                                    val url = try {
                                        downloader.resolveStreamUrls(token, fileId).firstOrNull()
                                    } catch (_: Exception) { null }
                                    if (url != null) item.id to url else null
                                }
                            }.awaitAll().filterNotNull()
                        }
                        if (results.isNotEmpty()) {
                            for ((id, url) in results) {
                                thumbnailUrlCache.put(id, url)
                                cache = cache + (id to url)
                            }
                            streamUrlCache.value = cache
                        }
                        delay(300)
                    }
                }.collect {}
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Swallow flow collection errors to prevent crashing the app
            }
        }
    }

    fun refresh() {
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                // Pull the latest backup index from Telegram first so items backed up on
                // other devices are merged into the local DB before we scan MediaStore.
                if (getCredentials.hasCredentials()) {
                    debugLog.log("Timeline", "refresh: fetching index from Telegram")
                    try {
                        val result = fetchIndex()
                        debugLog.log("Timeline", "refresh: fetchIndex result=${result}")
                    } catch (e: Exception) {
                        debugLog.log("Timeline", "refresh: fetchIndex error: ${e.message}")
                    }
                }
                debugLog.log("Timeline", "refresh: starting scanMedia(fullScan=true)")
                scanMedia(fullScan = true)
                debugLog.log("Timeline", "refresh: complete — ${groups.value.sumOf { it.items.size }} total items")
                _refreshCompleted.tryEmit(Unit)
            } catch (e: Exception) {
                debugLog.log("Timeline", "refresh: error: ${e.message}")
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private val _downloadingIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingIds: StateFlow<Set<String>> = _downloadingIds.asStateFlow()

    fun downloadFromGallery(item: MediaItem) {
        if (item.contentUri.isNotBlank()) return
        if (item.telegramFileId.isNullOrBlank()) return
        viewModelScope.launch {
            _downloadingIds.update { it + item.id }
            _snackbarMessages.emit(context.getString(R.string.gallery_download_started))
            try {
                val result = downloadCloudItem(item.id)
                if (result.isSuccess) {
                    _snackbarMessages.emit(context.getString(R.string.gallery_download_saved))
                } else {
                    _snackbarMessages.emit(
                        result.exceptionOrNull()?.message
                            ?: context.getString(R.string.gallery_download_failed),
                    )
                }
            } finally {
                _downloadingIds.update { it - item.id }
            }
        }
    }

    fun removeFromDevice(item: MediaItem) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { removeLocalMediaFile(item) }
            if (result.isSuccess) {
                when (val outcome = result.getOrNull()) {
                    is RemoveLocalMediaOutcome.DeletedLocally ->
                        _snackbarMessages.emit(context.getString(R.string.gallery_removed_from_device))
                    is RemoveLocalMediaOutcome.NeedsDeleteConfirmation ->
                        _removeFromDeviceConfirmation.value =
                            RemoveFromDeviceConfirmationState(
                                deleteSender = outcome.intentSender,
                                pendingMediaItemId = outcome.mediaItemId,
                            )
                    null -> { }
                }
            } else {
                val ex = result.exceptionOrNull()
                val message =
                    if (ex is SecurityException) {
                        context.getString(R.string.gallery_remove_failed)
                    } else {
                        ex?.message?.takeIf { it.isNotBlank() }
                            ?: context.getString(R.string.gallery_remove_failed)
                    }
                _snackbarMessages.emit(message)
            }
        }
    }

    fun consumeRemoveFromDeviceDeleteSender() {
        _removeFromDeviceConfirmation.update { it.copy(deleteSender = null) }
    }

    fun onRemoveFromDeviceConfirmed() {
        val id = _removeFromDeviceConfirmation.value.pendingMediaItemId ?: return
        viewModelScope.launch {
            markAsCloudOnly(listOf(id))
            _removeFromDeviceConfirmation.value = RemoveFromDeviceConfirmationState()
            _snackbarMessages.emit(context.getString(R.string.gallery_removed_from_device))
        }
    }

    fun dismissRemoveFromDeviceConfirmation() {
        _removeFromDeviceConfirmation.value = RemoveFromDeviceConfirmationState()
    }

    fun deleteFromTelegram(item: MediaItem) {
        val messageId = item.telegramMessageId ?: return
        viewModelScope.launch {
            _snackbarMessages.emit("Deleting from Telegram…")
            try {
                val result = deleteFileFromTelegram(messageId)
                when (result) {
                    is DeleteFileResult.Success -> {
                        _snackbarMessages.emit("Deleted from Telegram")
                    }
                    is DeleteFileResult.Error -> {
                        _snackbarMessages.emit("Delete failed: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                _snackbarMessages.emit("Delete failed: ${e.message ?: "Unknown error"}")
            }
        }
    }

    private fun groupByTimelineLabel(items: List<MediaItem>): List<TimelineGroup> {
        val zone = ZoneId.systemDefault()
        val now = Instant.now().atZone(zone)
        val weekFields = WeekFields.ISO
        val currentWeek = now.get(weekFields.weekOfWeekBasedYear())
        val currentWeekYear = now.get(weekFields.weekBasedYear())
        val currentMonth = YearMonth.from(now)
        val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
        val labelThisWeek = context.getString(com.ulap.R.string.timeline_group_this_week)
        val labelThisMonth = context.getString(com.ulap.R.string.timeline_group_this_month)
        // Sort descending so the first item in each group has the latest dateTaken.
        val sortedItems = items.sortedByDescending { it.dateTaken }

        val grouped = linkedMapOf<String, MutableList<MediaItem>>()
        sortedItems.forEach { item ->
            val itemDate = Instant.ofEpochMilli(item.dateTaken).atZone(zone)
            val label = when {
                itemDate.get(weekFields.weekOfWeekBasedYear()) == currentWeek &&
                    itemDate.get(weekFields.weekBasedYear()) == currentWeekYear -> labelThisWeek
                YearMonth.from(itemDate) == currentMonth -> labelThisMonth
                else -> itemDate.format(monthFormatter)
            }
            grouped.getOrPut(label) { mutableListOf() }.add(item)
        }

        val ordered = mutableListOf<TimelineGroup>()
        grouped[labelThisWeek]?.let { ordered.add(TimelineGroup(labelThisWeek, it)) }
        grouped[labelThisMonth]?.let { ordered.add(TimelineGroup(labelThisMonth, it)) }
        // Since sortedItems is already descending, the first element of each group carries
        // the latest dateTaken for that group — no separate tracking map needed.
        grouped.keys
            .filter { it != labelThisWeek && it != labelThisMonth }
            .sortedByDescending { grouped[it]!!.first().dateTaken }
            .forEach { label ->
                ordered.add(TimelineGroup(label, grouped[label].orEmpty()))
            }
        return ordered
    }
}
