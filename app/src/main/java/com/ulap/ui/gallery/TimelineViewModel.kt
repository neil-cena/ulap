package com.ulap.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ulap.data.local.ThumbnailUrlCache
import com.ulap.data.remote.TelegramDownloader
import com.ulap.debug.DebugLogBuffer
import com.ulap.domain.model.BackupStatus
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.TimelineGroup
import com.ulap.domain.usecase.FetchIndexFromPinnedMessageUseCase
import com.ulap.domain.usecase.GetCredentialsUseCase
import com.ulap.domain.usecase.GetTimelineUseCase
import com.ulap.domain.usecase.RefreshFoldersUseCase
import com.ulap.domain.usecase.ScanMediaUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val getTimeline: GetTimelineUseCase,
    private val scanMedia: ScanMediaUseCase,
    private val getCredentials: GetCredentialsUseCase,
    private val downloader: TelegramDownloader,
    private val fetchIndex: FetchIndexFromPinnedMessageUseCase,
    private val refreshFolders: RefreshFoldersUseCase,
    private val thumbnailUrlCache: ThumbnailUrlCache,
    private val debugLog: DebugLogBuffer,
) : ViewModel() {

    private val streamUrlCache = MutableStateFlow<Map<String, String>>(thumbnailUrlCache.getAll())
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()
    private val _refreshCompleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshCompleted: SharedFlow<Unit> = _refreshCompleted.asSharedFlow()

    val groups: StateFlow<List<TimelineGroup>> = combine(
        getTimeline().map { groupByTimelineLabel(it) },
        streamUrlCache,
    ) { list, cache ->
        list.map { group ->
            TimelineGroup(
                label = group.label,
                items = group.items.map { it.copy(streamUrl = cache[it.id]) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            // Keep the loading spinner visible until both the init work and the first
            // groups emission have resolved, preventing a flash of the empty-state UI.
            try {
                val currentItems = try { getTimeline().first() } catch (_: Exception) { emptyList() }
                debugLog.log("Timeline", "init: ${currentItems.size} items in DB")
                if (getCredentials.hasCredentials() && currentItems.isEmpty()) {
                    debugLog.log("Timeline", "init: DB empty with credentials — fetching index")
                    try { fetchIndex() } catch (e: Exception) { debugLog.log("Timeline", "init fetchIndex error: ${e.message}") }
                    try { refreshFolders() } catch (e: Exception) { debugLog.log("Timeline", "init refreshFolders error: ${e.message}") }
                }
                debugLog.log("Timeline", "init: starting scanMedia(fullScan=false)")
                try { scanMedia(fullScan = false) } catch (e: Exception) { debugLog.log("Timeline", "init scanMedia error: ${e.message}") }
                debugLog.log("Timeline", "init: waiting for first groups emission")
                groups.first { it.isNotEmpty() || !getCredentials.hasCredentials() }
                debugLog.log("Timeline", "init: complete — ${groups.value.sumOf { it.items.size }} total items across ${groups.value.size} groups")
            } finally {
                _isLoading.value = false
            }
        }
        viewModelScope.launch {
            try {
                getTimeline().collect { items ->
                    val token = getCredentials.getToken() ?: return@collect
                    var cache = streamUrlCache.value
                    val uncached = items
                        .filter { it.backupStatus == BackupStatus.CLOUD_ONLY && (it.thumbnailFileId != null || it.telegramFileId != null) && cache[it.id] == null }
                        .distinctBy { it.id }
                    for (batch in uncached.chunked(5)) {
                        batch.forEach { item ->
                            val fileId = item.thumbnailFileId ?: item.telegramFileId ?: return@forEach
                            val url = try {
                                withContext(Dispatchers.IO) {
                                    downloader.resolveStreamUrls(token, fileId).firstOrNull()
                                }
                            } catch (_: Exception) {
                                null
                            }
                            if (url != null) {
                                cache = cache + (item.id to url)
                                viewModelScope.launch { thumbnailUrlCache.put(item.id, url) }
                            }
                        }
                        streamUrlCache.value = cache
                        delay(300)
                    }
                }
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

    private fun groupByTimelineLabel(items: List<MediaItem>): List<TimelineGroup> {
        val zone = ZoneId.systemDefault()
        val now = Instant.now().atZone(zone)
        val weekFields = WeekFields.ISO
        val currentWeek = now.get(weekFields.weekOfWeekBasedYear())
        val currentWeekYear = now.get(weekFields.weekBasedYear())
        val currentMonth = YearMonth.from(now)
        val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
        // Sort descending so the first item in each group has the latest dateTaken.
        val sortedItems = items.sortedByDescending { it.dateTaken }

        val grouped = linkedMapOf<String, MutableList<MediaItem>>()
        sortedItems.forEach { item ->
            val itemDate = Instant.ofEpochMilli(item.dateTaken).atZone(zone)
            val label = when {
                itemDate.get(weekFields.weekOfWeekBasedYear()) == currentWeek &&
                    itemDate.get(weekFields.weekBasedYear()) == currentWeekYear -> "This week"
                YearMonth.from(itemDate) == currentMonth -> "This month"
                else -> itemDate.format(monthFormatter)
            }
            grouped.getOrPut(label) { mutableListOf() }.add(item)
        }

        val ordered = mutableListOf<TimelineGroup>()
        grouped["This week"]?.let { ordered.add(TimelineGroup("This week", it)) }
        grouped["This month"]?.let { ordered.add(TimelineGroup("This month", it)) }
        // Since sortedItems is already descending, the first element of each group carries
        // the latest dateTaken for that group — no separate tracking map needed.
        grouped.keys
            .filter { it != "This week" && it != "This month" }
            .sortedByDescending { grouped[it]!!.first().dateTaken }
            .forEach { label ->
                ordered.add(TimelineGroup(label, grouped[label].orEmpty()))
            }
        return ordered
    }
}
