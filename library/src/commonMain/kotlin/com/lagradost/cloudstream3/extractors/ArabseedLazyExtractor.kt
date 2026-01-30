package com.lagradost.cloudstream3.extractors

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
// import org.json.JSONObject - JSON is usually available via Jackson or kotlinx.serialization in commonMain, or org.json if JVM
// CloudStream uses standard JSON usually or Strings.
// app.post returns a NiceResponse which has .text (String).
// I'll use regex for JSON parsing to be safe and dependency-free in commonMain
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

// Define alias for the fetcher function
typealias JsonFetcher = suspend (url: String, data: Map<String, String>, referer: String) -> String?

class ArabseedLazyExtractor(
    private val jsonFetcher: JsonFetcher? = null
) : ExtractorApi() {
    override val name = "ArabseedLazy"
    override val mainUrl = "https://arabseed.show"
    override val requiresReferer = true

    companion object {
        private const val TAG = "ArabseedLazyExtractor"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36"
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // ... implementation unchanged ...
        Log.d(TAG, "[getUrl] Processing: $url")
        
        // Match only virtual URLs
        if (!url.contains("/get__watch__server/")) return

        // Parse virtual URL parameters using Regex (Platform agnostic)
        val postId = getQueryParam(url, "post_id") ?: return
        val quality = getQueryParam(url, "quality")?.toIntOrNull() ?: 720
        val server = getQueryParam(url, "server") ?: "0"
        val csrfToken = getQueryParam(url, "csrf_token") ?: ""
        val rawReferer = getQueryParam(url, "referer")
        // Manual URL decoding for basic characters if needed, or assume clean
        val pageReferer = rawReferer?.replace("%3A", ":")?.replace("%2F", "/") ?: referer ?: ""
        
        // Derive base URL from the virtual URL to match the current domain (e.g. asd.pics)
        val baseUrl = url.substringBefore("/get__watch__server")
        
        Log.d(TAG, "[getUrl] postId=$postId, quality=$quality, server=$server, baseUrl=$baseUrl")
        
        // Make POST request to get embed URL
        val embedUrl = fetchEmbedUrl(baseUrl, postId, quality.toString(), server, csrfToken, pageReferer)
        
        if (embedUrl.isBlank()) {
            Log.e(TAG, "[getUrl] Failed to get embed URL")
            return
        }
        
        Log.d(TAG, "[getUrl] Got embed URL: $embedUrl")
        
        // Try CloudStream's loadExtractor first
        var foundVideo = false
        loadExtractor(embedUrl, pageReferer, subtitleCallback) { link ->
            Log.d(TAG, "[getUrl] loadExtractor found: ${link.url}")
            callback(link)
            foundVideo = true
        }
        
        // If loadExtractor didn't find anything, try manual extraction
        if (!foundVideo) {
            Log.d(TAG, "[getUrl] loadExtractor failed, trying manual extraction...")
            val directUrl = extractDirectVideoUrl(embedUrl)
            if (directUrl.isNotBlank()) {
                Log.d(TAG, "[getUrl] Manual extraction found: $directUrl")
                callback(
                    newExtractorLink(
                        source = "ArabSeed", 
                        name = "ArabSeed ${quality}p",
                        url = directUrl,
                        type = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = embedUrl
                        this.quality = quality
                    }
                )
            } else {
                Log.e(TAG, "[getUrl] Manual extraction also failed")
            }
        }
    }
    
    private fun getQueryParam(url: String, key: String): String? {
        return Regex("[?&]$key=([^&]+)").find(url)?.groupValues?.get(1)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun fetchEmbedUrl(
        baseUrl: String,
        postId: String,
        quality: String,
        server: String,
        csrfToken: String,
        referer: String
    ): String {
        Log.d(TAG, "[fetchEmbedUrl] Requesting: $baseUrl/get__watch__server/")
        try {
            val data = mapOf(
                "post_id" to postId,
                "quality" to quality,
                "server" to server,
                "csrf_token" to csrfToken
            )

            // Use delegated fetcher if available (e.g. from ProviderHttpService)
            if (jsonFetcher != null) {
                Log.d(TAG, "[fetchEmbedUrl] Using delegated JsonFetcher with relative path")
                // Pass relative path so ProviderHttpService can prepend the correct/current domain
                val json = jsonFetcher.invoke("/get__watch__server/", data, referer)
                if (!json.isNullOrBlank()) {
                     return parseEmbedUrlFromJson(json)
                }
                // Fallback or return empty?
                Log.w(TAG, "[fetchEmbedUrl] JsonFetcher returned null/empty")
                return ""
            }

            // Fallback to default app.post
            val endpoint = "$baseUrl/get__watch__server/"
            Log.d(TAG, "[fetchEmbedUrl] Using default app.post with: $endpoint")
            val response = app.post(
                endpoint,
                headers = mapOf(
                    "User-Agent" to com.lagradost.cloudstream3.USER_AGENT,
                    "Referer" to referer,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = data
            )
            
            val json = response.text
             return parseEmbedUrlFromJson(json)

        } catch (e: Throwable) {
            Log.e(TAG, "[fetchEmbedUrl] Error: ${e.message}")
            e.printStackTrace()
        }
        return ""
    }
    
    @OptIn(ExperimentalEncodingApi::class)
    private fun parseEmbedUrlFromJson(json: String): String {
        Log.d(TAG, "[parseEmbedUrlFromJson] Response: ${json.take(200)}")
        // Simple string parsing for JSON using Regex
        if (json.contains("\"type\":\"success\"") || json.contains("\"type\": \"success\"")) {
            // Extract server value
            val serverMatch = Regex("\"server\"\\s*:\\s*\"([^\"]+)\"").find(json)
            var serverUrl = serverMatch?.groupValues?.get(1) ?: ""
            
            // Handle base64 encoded URLs
            if (serverUrl.isNotBlank() && !serverUrl.startsWith("http")) {
                try {
                    val decodedBytes = Base64.decode(serverUrl)
                    serverUrl = decodedBytes.decodeToString()
                } catch (e: Exception) {
                    // Not base64
                }
            }
            
            // Remove backslashes from JSON string (e.g. \/)
            serverUrl = serverUrl.replace("\\/", "/")
            
            return serverUrl
        }
        return ""
    }
    
    private suspend fun extractDirectVideoUrl(embedUrl: String): String {
        try {
            val html = app.get(
                embedUrl,
                headers = mapOf("User-Agent" to USER_AGENT)
            ).text
            
            // Pattern 1: <source src="...mp4">
            val sourcePattern = """<source[^>]+src=["']([^"']+\.mp4)["']""".toRegex(RegexOption.IGNORE_CASE)
            sourcePattern.find(html)?.groupValues?.get(1)?.let { return it }
            
            // Pattern 2: file: "..."
            val filePattern = """file:\s*["']([^"']+\.mp4)["']""".toRegex(RegexOption.IGNORE_CASE)
            filePattern.find(html)?.groupValues?.get(1)?.let { return it }
            
            // Pattern 3: sources: [{file:"..."}]
            val sourcesPattern = """sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""".toRegex(RegexOption.IGNORE_CASE)
            sourcesPattern.find(html)?.groupValues?.get(1)?.let { return it }
            
            // Pattern 4: source: "..."
            val sourcePattern2 = """source:\s*["']([^"']+\.mp4)["']""".toRegex(RegexOption.IGNORE_CASE)
            sourcePattern2.find(html)?.groupValues?.get(1)?.let { return it }
            
        } catch (e: Exception) {
            Log.e(TAG, "[extractDirectVideoUrl] Error: ${e.message}")
        }
        return ""
    }
}
