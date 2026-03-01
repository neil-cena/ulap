package com.ulap.data.repository

import android.content.SharedPreferences
import com.ulap.di.PlainPrefs
import com.ulap.ui.gallery.TimelineViewMode
import com.ulap.ui.theme.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_THEME = "theme_preference"
private const val KEY_TIMELINE_VIEW_MODE = "timeline_view_mode"

@Singleton
class UserPreferencesRepository @Inject constructor(
    @PlainPrefs private val prefs: SharedPreferences,
) {
    private val _theme = MutableStateFlow(loadTheme())
    val theme: StateFlow<ThemePreference> = _theme.asStateFlow()

    private val _timelineViewMode = MutableStateFlow(loadTimelineViewMode())
    val timelineViewMode: StateFlow<TimelineViewMode> = _timelineViewMode.asStateFlow()

    fun setTheme(preference: ThemePreference) {
        prefs.edit().putString(KEY_THEME, preference.name).apply()
        _theme.value = preference
    }

    fun setTimelineViewMode(mode: TimelineViewMode) {
        prefs.edit().putString(KEY_TIMELINE_VIEW_MODE, mode.name).apply()
        _timelineViewMode.value = mode
    }

    private fun loadTheme(): ThemePreference {
        val name = prefs.getString(KEY_THEME, null) ?: return ThemePreference.SYSTEM
        return runCatching { ThemePreference.valueOf(name) }.getOrDefault(ThemePreference.SYSTEM)
    }

    private fun loadTimelineViewMode(): TimelineViewMode {
        val name = prefs.getString(KEY_TIMELINE_VIEW_MODE, null) ?: return TimelineViewMode.GRID
        return runCatching { TimelineViewMode.valueOf(name) }.getOrDefault(TimelineViewMode.GRID)
    }
}
