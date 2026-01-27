package com.lagradost.cloudstream3.providers.arabseed.service.domain

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.*
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
    
    /**
     * BLOCKING initialization - called before first request.
     * Fetches latest domain from GitHub and waits for response.
     */
    suspend fun ensureInitialized() {
        if (isInitialized) return
        
        // Load persisted first (fast)
        currentDomain = prefs.getString("domain", fallbackDomain) ?: fallbackDomain
        Log.d(TAG, "Loaded persisted domain: $currentDomain")
        
        // Fetch from GitHub (BLOCKING, with timeout)
        try {
            withTimeout(5000L) {
                val response = app.get(githubConfigUrl)
                if (response.isSuccessful) {
                    val config = JSONObject(response.text)
                    val remoteDomain = config.optString("domain", "")
                    
                    if (remoteDomain.isNotBlank() && remoteDomain != currentDomain) {
                        Log.i(TAG, "Domain updated from GitHub: $currentDomain → $remoteDomain")
                        // Note: Cookie clearing is handled by ProviderHttpService.updateDomain()
                        updateDomain(remoteDomain)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "GitHub config fetch timed out, using persisted: $currentDomain")
        } catch (e: Exception) {
            Log.w(TAG, "GitHub config fetch failed: ${e.message}, using persisted: $currentDomain")
        }
        
        isInitialized = true
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
