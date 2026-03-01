package com.ulap.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ulap.data.local.ThumbnailUrlCache
import com.ulap.data.remote.TelegramDownloader
import com.ulap.domain.model.BackupStatus
import com.ulap.domain.model.MediaItem
import com.ulap.domain.model.MediaType
import com.ulap.domain.model.TimelineGroup
import com.ulap.domain.usecase.GetCredentialsUseCase
import com.ulap.domain.usecase.GetMediaByTypeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MediaTypeViewModel @Inject constructor(
    private val getMediaByType: GetMediaByTypeUseCase,
    private val downloader: TelegramDownloader,
    private val thumbnailUrlCache: ThumbnailUrlCache,
    private val getCredentials: GetCredentialsUseCase,
) : ViewModel() {

    private val _selectedType = MutableStateFlow(MediaType.IMAGE)
    val selectedType: StateFlow<MediaType> = _selectedType.asStateFlow()

    private val streamUrlCache = MutableStateFlow<Map<String, String>>(thumbnailUrlCache.getAll())

    val groups: StateFlow<List<TimelineGroup>> = combine(
        _selectedType.flatMapLatest { type ->
            getMediaByType(type).map { groupByLabel(it) }
        },
        streamUrlCache,
    ) { list, cache ->
        list.map { group ->
            TimelineGroup(
                label = group.label,
                items = group.items.map { it.copy(streamUrl = cache[it.id]) },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectType(type: MediaType) {
        _selectedType.value = type
    }

    init {
        viewModelScope.launch {
            try {
                _selectedType.flatMapLatest { type ->
                    getMediaByType(type).transformLatest<List<MediaItem>, Unit> { items ->
                        val token = getCredentials.getToken() ?: return@transformLatest
                        var cache = streamUrlCache.value
                        val uncached = items
                            .filter {
                                it.backupStatus == BackupStatus.CLOUD_ONLY &&
                                    (it.thumbnailFileId != null || it.telegramFileId != null) &&
                                    cache[it.id] == null
                            }
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
                                    thumbnailUrlCache.put(item.id, url)
                                }
                            }
                            streamUrlCache.value = cache
                            delay(300)
                        }
                    }
                }.collect {}
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { }
        }
    }

    private fun groupByLabel(items: List<MediaItem>): List<TimelineGroup> {
        val zone = ZoneId.systemDefault()
        val now = Instant.now().atZone(zone)
        val weekFields = WeekFields.ISO
        val currentWeek = now.get(weekFields.weekOfWeekBasedYear())
        val currentWeekYear = now.get(weekFields.weekBasedYear())
        val currentMonth = YearMonth.from(now)
        val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
        val labelThisWeek = "This week"
        val labelThisMonth = "This month"

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
        grouped.keys
            .filter { it != labelThisWeek && it != labelThisMonth }
            .sortedByDescending { grouped[it]!!.first().dateTaken }
            .forEach { label -> ordered.add(TimelineGroup(label, grouped[label].orEmpty())) }
        return ordered
    }
}
