package com.ulap.ui.gallery // Coil thumbnails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ulap.data.local.ThumbnailUrlCache
import com.ulap.data.remote.TelegramDownloader
import com.ulap.domain.model.BackupStatus
import com.ulap.domain.model.MediaItem
import com.ulap.domain.usecase.FetchIndexFromPinnedMessageUseCase
import com.ulap.domain.usecase.GetCredentialsUseCase
import com.ulap.domain.usecase.GetTimelineUseCase
import com.ulap.domain.usecase.RefreshFoldersUseCase
import com.ulap.domain.usecase.ScanMediaUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class TimelineGroup(val label: String, val items: List<MediaItem>)

@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val getTimeline: GetTimelineUseCase,
    private val scanMedia: ScanMediaUseCase,
    private val getCredentials: GetCredentialsUseCase,
    private val downloader: TelegramDownloader,
    private val fetchIndex: FetchIndexFromPinnedMessageUseCase,
    private val refreshFolders: RefreshFoldersUseCase,
    private val thumbnailUrlCache: ThumbnailUrlCache,
) : ViewModel() {

    private val streamUrlCache = MutableStateFlow<Map<String, String>>(thumbnailUrlCache.getAll())

    val groups: StateFlow<List<TimelineGroup>> = combine(
        getTimeline().map { groupByMonth(it) },
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
            val currentItems = try { getTimeline().first() } catch (_: Exception) { emptyList() }
            if (getCredentials.hasCredentials() && currentItems.isEmpty()) {
                try { fetchIndex() } catch (_: Exception) { }
                try { refreshFolders() } catch (_: Exception) { }
            }
            try { scanMedia(fullScan = false) } catch (_: Exception) { }
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
                        kotlinx.coroutines.delay(300)
                    }
                }
            } catch (_: Exception) {
                // Swallow flow collection errors to prevent crashing the app
            }
        }
    }

    fun refresh() {
        viewModelScope.launch { scanMedia(fullScan = true) }
    }

    private fun groupByMonth(items: List<MediaItem>): List<TimelineGroup> {
        val fmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        return items
            .groupBy { fmt.format(Date(it.dateTaken)) }
            .map { (label, grouped) -> TimelineGroup(label, grouped) }
    }
}
