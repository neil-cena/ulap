package com.ulap.data.auth

import android.content.SharedPreferences

class OAuthTokenStore(private val prefs: SharedPreferences) {

    companion object {
        private const val KEY_ACCESS_TOKEN = "oauth_access_token"
        private const val KEY_REFRESH_TOKEN = "oauth_refresh_token"
        private const val KEY_EXPIRES_AT = "oauth_expires_at_millis"
    }

    fun saveTokens(accessToken: String, refreshToken: String?, expiresAtMillis: Long) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .apply { if (refreshToken != null) putString(KEY_REFRESH_TOKEN, refreshToken) }
            .putLong(KEY_EXPIRES_AT, expiresAtMillis)
            .apply()
    }

    fun updateAccessToken(accessToken: String, expiresAtMillis: Long) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_EXPIRES_AT, expiresAtMillis)
            .apply()
    }

    fun getAccessToken(): String? {
        if (isAccessTokenExpired()) return null
        return getRawAccessToken()
    }

    fun getRawAccessToken(): String? = prefs.getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun hasRefreshToken(): Boolean = !getRefreshToken().isNullOrBlank()

    fun isAccessTokenExpired(): Boolean {
        val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        if (expiresAt == 0L) return true
        return System.currentTimeMillis() >= expiresAt - 300_000L
    }

    fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_EXPIRES_AT)
            .apply()
    }

    fun expireAccessToken() {
        prefs.edit()
            .putLong(KEY_EXPIRES_AT, 0L)
            .apply()
    }
}
