package com.lagradost.cloudstream3.providers.arabseed.service.domain

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Manages provider domain with GitHub fetch and persistence.
 * 
 * SIMPLIFIED: Cookie management is now handled by SessionState.
 * This class only handles domain persistence and GitHub sync.
 */
class DomainManager(
    private val context: Context,
    private val providerName: String,
    private val fallbackDomain: String,
    private val githubConfigUrl: String,
    private val syncWorkerUrl: String? = null
) {
    private val TAG = "DomainManager"
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "domain_$providerName", 
        Context.MODE_PRIVATE
    )
    
    var currentDomain: String = fallbackDomain
        private set
    
    private var isInitialized = false
    private val mutex = Mutex()
    
    /**
     * BLOCKING initialization - called before first request.
     * Fetches latest domain from GitHub and waits for response.
     */
    suspend fun ensureInitialized() {
        if (isInitialized) return
        
        mutex.withLock {
            if (isInitialized) return@withLock
            
            // Load persisted first (fast)
            val persisted = prefs.getString("domain", null)
            if (persisted != null) {
                 currentDomain = persisted
                 Log.i(TAG, "🟢 [Lifecycle] Loaded PERSISTED domain from disk: $currentDomain")
            } else {
                 currentDomain = fallbackDomain
                 Log.i(TAG, "🟡 [Lifecycle] No persisted domain found, using FALLBACK: $currentDomain")
            }
            
            // Fetch from GitHub (BLOCKING, with timeout)
            Log.d(TAG, "🔵 [Lifecycle] Starting GitHub fetch from: $githubConfigUrl")
            try {
                withTimeout(5000L) {
                    val response = app.get(githubConfigUrl)
                    Log.d(TAG, "🔵 [Lifecycle] GitHub response code: ${response.code}")
                    
                    if (response.isSuccessful) {
                        val responseText = response.text
                        Log.d(TAG, "🔵 [Lifecycle] GitHub raw response: $responseText")
                        
                        val config = JSONObject(responseText)
                        val remoteDomain = config.optString("domain", "")
                        Log.i(TAG, "🔵 [Lifecycle] Parsed remote domain: '$remoteDomain'")
                        
                        val normalizedRemote = remoteDomain
                            .removePrefix("http://")
                            .removePrefix("https://")
                            .trimEnd('/')
                        
                        if (normalizedRemote.isNotBlank() && normalizedRemote != currentDomain) {
                            Log.w(TAG, "⚠️ [Lifecycle] Domain CHANGE detected: $currentDomain → $normalizedRemote")
                            // Note: Cookie clearing is handled by ProviderHttpService.updateDomain()
                            updateDomain(normalizedRemote)
                        } else {
                            Log.i(TAG, "🟢 [Lifecycle] Domain is up to date. Keeping: $currentDomain")
                        }
                    } else {
                        Log.e(TAG, "🔴 [Lifecycle] GitHub fetch FAILED. Code: ${response.code}")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "🟠 [Lifecycle] GitHub config fetch TIMED OUT (5s), keeping: $currentDomain")
            } catch (e: Exception) {
                Log.e(TAG, "🔴 [Lifecycle] GitHub config fetch ERROR: ${e.message}\n${e.stackTraceToString()}")
            }
            
            isInitialized = true
        }
    }
    
    /**
     * Update domain and persist.
     */
    fun updateDomain(newDomain: String) {
        val normalized = newDomain
            .removePrefix("http://")
            .removePrefix("https://")
            .trimEnd('/')
        
        if (normalized != currentDomain) {
            Log.i(TAG, "Domain updated: $currentDomain → $normalized")
            currentDomain = normalized
            prefs.edit().putString("domain", normalized).apply()
        }
    }

    /**
     * Sync domain change back to GitHub via Cloudflare Worker.
     */
    fun syncToRemote() {
        if (syncWorkerUrl == null) return
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val configName = "${providerName.lowercase()}.json"
                
                val payload = JSONObject().apply {
                    put("provider", providerName.lowercase())
                    put("configFile", configName)
                    put("newDomain", "https://$currentDomain")
                    put("currentVersion", 0)
                }
                
                val jsonBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                
                app.post(
                    syncWorkerUrl,
                    requestBody = jsonBody
                )
                
                Log.d(TAG, "Domain synced to remote: $currentDomain")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to sync domain: ${e.message}")
            }
        }
    }
}
