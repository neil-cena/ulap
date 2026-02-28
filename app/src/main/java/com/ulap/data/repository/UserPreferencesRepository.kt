package com.ulap.data.repository

import android.content.SharedPreferences
import com.ulap.di.PlainPrefs
import com.ulap.ui.theme.ThemePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val KEY_THEME = "theme_preference"

@Singleton
class UserPreferencesRepository @Inject constructor(
    @PlainPrefs private val prefs: SharedPreferences,
) {
    private val _theme = MutableStateFlow(loadTheme())
    val theme: StateFlow<ThemePreference> = _theme.asStateFlow()

    fun setTheme(preference: ThemePreference) {
        prefs.edit().putString(KEY_THEME, preference.name).apply()
        _theme.value = preference
    }

    private fun loadTheme(): ThemePreference {
        val name = prefs.getString(KEY_THEME, null) ?: return ThemePreference.SYSTEM
        return runCatching { ThemePreference.valueOf(name) }.getOrDefault(ThemePreference.SYSTEM)
    }
}
