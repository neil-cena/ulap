package com.ulap.data.repository

import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ulap.di.PlainPrefs
import com.ulap.ui.gallery.TimelineViewMode
import com.ulap.ui.theme.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class UploadSpeedMode {
    /** 1.5 s base gap, 3 small-file workers. Good balance of speed and safety. */
    BALANCED,
    /** 3 s base gap, 1 small-file worker. For users who want maximum account safety. */
    CONSERVATIVE,
}

private const val KEY_THEME = "theme_preference"
private const val KEY_TIMELINE_VIEW_MODE = "timeline_view_mode"
private const val KEY_STRIP_EXIF = "strip_exif"
private const val KEY_WIFI_ONLY = "wifi_only_backup"
private const val KEY_PAUSE_ON_LOW_BATTERY = "pause_on_low_battery"
private const val KEY_UPLOAD_SPEED_MODE = "upload_speed_mode"
private const val KEY_TELEGRAM_LOGGING_ENABLED = "telegram_logging_enabled"
private const val KEY_TELEGRAM_LOGGING_CHAT_ID = "telegram_logging_chat_id"

private val GOOGLE_PHOTOS_NEXT_PAGE_TOKEN = stringPreferencesKey("google_photos_sync_token")
private val GOOGLE_PHOTOS_PICKER_SESSION_ID = stringPreferencesKey("google_photos_picker_session_id")

@Singleton
class UserPreferencesRepository @Inject constructor(
    @PlainPrefs private val prefs: SharedPreferences,
    private val dataStore: DataStore<Preferences>,
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

    private val _uploadSpeedMode = MutableStateFlow(loadUploadSpeedMode())
    val uploadSpeedMode: StateFlow<UploadSpeedMode> = _uploadSpeedMode.asStateFlow()

    private val _telegramLoggingEnabled = MutableStateFlow(prefs.getBoolean(KEY_TELEGRAM_LOGGING_ENABLED, false))
    val telegramLoggingEnabled: StateFlow<Boolean> = _telegramLoggingEnabled.asStateFlow()

    private val _telegramLoggingChatId = MutableStateFlow(prefs.getString(KEY_TELEGRAM_LOGGING_CHAT_ID, null))
    val telegramLoggingChatId: StateFlow<String?> = _telegramLoggingChatId.asStateFlow()

    val googlePhotosPageToken: Flow<String?> = dataStore.data.map { preferences ->
        preferences[GOOGLE_PHOTOS_NEXT_PAGE_TOKEN]
    }

    suspend fun updateGooglePhotosPageToken(token: String?) {
        dataStore.edit { preferences ->
            if (token == null) preferences.remove(GOOGLE_PHOTOS_NEXT_PAGE_TOKEN)
            else preferences[GOOGLE_PHOTOS_NEXT_PAGE_TOKEN] = token
        }
    }

    val pickerSessionId: Flow<String?> = dataStore.data.map { preferences ->
        preferences[GOOGLE_PHOTOS_PICKER_SESSION_ID]
    }

    suspend fun setPickerSessionId(sessionId: String?) {
        dataStore.edit { preferences ->
            if (sessionId == null) preferences.remove(GOOGLE_PHOTOS_PICKER_SESSION_ID)
            else preferences[GOOGLE_PHOTOS_PICKER_SESSION_ID] = sessionId
        }
    }

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

    fun setUploadSpeedMode(mode: UploadSpeedMode) {
        prefs.edit().putString(KEY_UPLOAD_SPEED_MODE, mode.name).apply()
        _uploadSpeedMode.value = mode
    }

    fun setTelegramLoggingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TELEGRAM_LOGGING_ENABLED, enabled).apply()
        _telegramLoggingEnabled.value = enabled
    }

    fun setTelegramLoggingChatId(chatId: String?) {
        prefs.edit().putString(KEY_TELEGRAM_LOGGING_CHAT_ID, chatId?.takeIf { it.isNotBlank() }).apply()
        _telegramLoggingChatId.value = chatId?.takeIf { it.isNotBlank() }
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

    private fun loadUploadSpeedMode(): UploadSpeedMode {
        val name = prefs.getString(KEY_UPLOAD_SPEED_MODE, null) ?: return UploadSpeedMode.BALANCED
        return runCatching { UploadSpeedMode.valueOf(name) }.getOrDefault(UploadSpeedMode.BALANCED)
    }
}
