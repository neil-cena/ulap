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
private const val KEY_STRIP_EXIF = "strip_exif"
private const val KEY_WIFI_ONLY = "wifi_only_backup"
private const val KEY_PAUSE_ON_LOW_BATTERY = "pause_on_low_battery"

@Singleton
class UserPreferencesRepository @Inject constructor(
    @PlainPrefs private val prefs: SharedPreferences,
) {
    private val _theme = MutableStateFlow(loadTheme())
    val theme: StateFlow<ThemePreference> = _theme.asStateFlow()

    private val _timelineViewMode = MutableStateFlow(loadTimelineViewMode())
    val timelineViewMode: StateFlow<TimelineViewMode> = _timelineViewMode.asStateFlow()

    private val _stripExif = MutableStateFlow(loadStripExif())
    val stripExif: StateFlow<Boolean> = _stripExif.asStateFlow()

    private val _wifiOnly = MutableStateFlow(prefs.getBoolean(KEY_WIFI_ONLY, false))
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _pauseOnLowBattery = MutableStateFlow(prefs.getBoolean(KEY_PAUSE_ON_LOW_BATTERY, false))
    val pauseOnLowBattery: StateFlow<Boolean> = _pauseOnLowBattery.asStateFlow()

    fun setTheme(preference: ThemePreference) {
        prefs.edit().putString(KEY_THEME, preference.name).apply()
        _theme.value = preference
    }

    fun setTimelineViewMode(mode: TimelineViewMode) {
        prefs.edit().putString(KEY_TIMELINE_VIEW_MODE, mode.name).apply()
        _timelineViewMode.value = mode
    }

    fun setStripExif(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_STRIP_EXIF, enabled).apply()
        _stripExif.value = enabled
    }

    fun setWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, enabled).apply()
        _wifiOnly.value = enabled
    }

    fun setPauseOnLowBattery(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSE_ON_LOW_BATTERY, enabled).apply()
        _pauseOnLowBattery.value = enabled
    }

    private fun loadTheme(): ThemePreference {
        val name = prefs.getString(KEY_THEME, null) ?: return ThemePreference.SYSTEM
        return runCatching { ThemePreference.valueOf(name) }.getOrDefault(ThemePreference.SYSTEM)
    }

    private fun loadTimelineViewMode(): TimelineViewMode {
        val name = prefs.getString(KEY_TIMELINE_VIEW_MODE, null) ?: return TimelineViewMode.GRID
        return runCatching { TimelineViewMode.valueOf(name) }.getOrDefault(TimelineViewMode.GRID)
    }

    private fun loadStripExif(): Boolean = prefs.getBoolean(KEY_STRIP_EXIF, false)
}
