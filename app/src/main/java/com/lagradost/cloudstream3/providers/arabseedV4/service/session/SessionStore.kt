package com.lagradost.cloudstream3.providers.arabseedV4.service.session

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.api.Log
import org.json.JSONObject

/**
 * Persistence layer for SessionState.
 */
class SessionStore(
    context: Context,
    private val providerName: String
) {
    private val TAG = "SessionStore"
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "session_${providerName.lowercase()}", 
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val KEY_USER_AGENT = "user_agent"
        private const val KEY_COOKIES = "cookies_json"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_COOKIE_TIMESTAMP = "cookie_timestamp"
        private const val KEY_FROM_WEBVIEW = "from_webview"
    }
    
    fun save(state: SessionState) {
        try {
            val cookiesJson = JSONObject().apply {
                state.cookies.forEach { (key, value) -> put(key, value) }
            }.toString()
            
            prefs.edit()
                .putString(KEY_USER_AGENT, state.userAgent)
                .putString(KEY_COOKIES, cookiesJson)
                .putString(KEY_DOMAIN, state.domain)
                .putLong(KEY_COOKIE_TIMESTAMP, state.cookieTimestamp)
                .putBoolean(KEY_FROM_WEBVIEW, state.fromWebView)
                .apply()
            
            Log.d(TAG, "Saved session: domain=${state.domain}, cookies=${state.cookies.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save session: ${e.message}")
        }
    }
    
    fun load(fallbackDomain: String): SessionState? {
        return try {
            val userAgent = prefs.getString(KEY_USER_AGENT, null) ?: SessionState.DEFAULT_UA
            val cookiesJson = prefs.getString(KEY_COOKIES, null) ?: "{}"
            val domain = prefs.getString(KEY_DOMAIN, fallbackDomain) ?: fallbackDomain
            val cookieTimestamp = prefs.getLong(KEY_COOKIE_TIMESTAMP, 0L)
            val fromWebView = prefs.getBoolean(KEY_FROM_WEBVIEW, false)
            
            val cookies = mutableMapOf<String, String>()
            val json = JSONObject(cookiesJson)
            json.keys().forEach { key ->
                cookies[key] = json.getString(key)
            }
            
            if (cookies.isEmpty()) {
                 return null
            }
            
            SessionState(
                userAgent = userAgent,
                cookies = cookies,
                domain = domain,
                cookieTimestamp = cookieTimestamp,
                fromWebView = fromWebView
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load session: ${e.message}")
            null
        }
    }
    
    fun clear() {
        prefs.edit().clear().apply()
    }
}
