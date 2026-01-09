package com.lagradost.cloudstream3.providers.fasel

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*

/**
 * FaselHD Video Extractor using WebViewResolver with JWPlayer source extraction.
 * Extracts all quality options (Auto, 1080p, 720p, etc.) from the JWPlayer sources array.
 */
open class FaselSniffer : ExtractorApi() {
    override val name = "FaselHD"
    override val mainUrl = "https://faselhd.biz"
    override val requiresReferer = false

    companion object {
        private const val TAG = "FaselSniffer"
        private const val MIN_VIDEO_URL_LENGTH = 50
        
        // Regex to match video stream URLs (m3u8, mp4, etc.)
        // Note: Explicitly excludes .ts, .js, .css, .png etc. by only allowing specific video extensions.
        // This matches the efficiency of the reference SnifferActivity.java implementation.
        private val videoUrlRegex = Regex(
            """\.(m3u8|mp4|mkv|avi|webm|mov)(\?|${'$'})""",
            RegexOption.IGNORE_CASE
        )
        
        /**
         * JavaScript to:
         * 1. Auto-click play buttons and skip ads
         * 2. Wait for JWPlayer to load
         * 3. Extract sources array with quality labels
         * 4. Return sources via SnifferAndroid.onSourcesFound()
         */
        private val extractSourcesScript = """
            (function() {
                console.log('[FaselSniffer] Source extraction script initialized');
                var sourcesSent = false;
                
                // Ad-skip: speed up short videos, click skip buttons
                function skipAds() {
                    try {
                        // Speed up and skip short videos (ads)
                        document.querySelectorAll('video').forEach(function(v) {
                            if (v.duration > 0 && v.duration < 30) {
                                v.playbackRate = 16;
                                v.muted = true;
                                v.currentTime = v.duration - 0.5;
                            }
                        });
                        
                        // Click skip buttons
                        var skipSelectors = ['.jw-skip', '.skip-button', '.skip-ad', '[class*="skip"]', '[class*="close"]'];
                        skipSelectors.forEach(function(sel) {
                            var btn = document.querySelector(sel);
                            if (btn && btn.offsetParent) btn.click();
                        });
                        
                        // JWPlayer ad skip
                        if (typeof jwplayer !== 'undefined') {
                            var player = jwplayer();
                            if (player && player.getDuration && player.getDuration() > 0 && player.getDuration() < 30) {
                                player.seek(player.getDuration() - 0.5);
                                player.setMute(true);
                            }
                        }
                    } catch(e) {}
                }
                
                // Auto-click play buttons
                function autoPlay() {
                    try {
                        if (typeof jwplayer !== 'undefined') {
                            var player = jwplayer();
                            if (player) {
                                if (player.setMute) player.setMute(true);
                                if (player.play) player.play();
                            }
                        }
                        
                        // Click play buttons
                        var playSelectors = ['.jw-icon-playback', '.jw-display-icon-container', '.vjs-big-play-button', '.play-button'];
                        playSelectors.forEach(function(sel) {
                            var btn = document.querySelector(sel);
                            if (btn && btn.offsetParent) btn.click();
                        });
                        
                        document.querySelectorAll('video').forEach(function(v) {
                            if (v.paused) {
                                v.muted = true; // Key fix: Mute before playing
                                v.play();
                            }
                        });
                    } catch(e) {}
                }
                
                // Extract JWPlayer sources
                function extractSources() {
                    if (sourcesSent) return;
                    
                    try {
                        if (typeof jwplayer !== 'undefined') {
                            var player = jwplayer();
                            if (player && player.getPlaylistItem) {
                                var item = player.getPlaylistItem();
                                if (item && item.sources && item.sources.length > 0) {
                                    var sources = [];
                                    item.sources.forEach(function(src) {
                                        if (src.file && src.file.length > 40) {
                                            sources.push({
                                                url: src.file,
                                                label: src.label || 'Auto',
                                                type: src.type || 'unknown'
                                            });
                                        }
                                    });
                                    
                                    if (sources.length > 0) {
                                        sourcesSent = true;
                                        console.log('[FaselSniffer] Found ' + sources.length + ' sources');
                                        // Pass data in URL to ensure we receive it in the interceptor
                                        var json = JSON.stringify(sources);
                                        window.location.href = "https://faselhd.biz/sniffer_done?sources=" + encodeURIComponent(json); 
                                        return json;
                                    }
                                }
                            }
                        }
                    } catch(e) {
                        console.log('[FaselSniffer] Extract error: ' + e);
                    }
                    return null;
                }
                
                // Run immediately and periodically
                autoPlay();
                skipAds();
                
                setInterval(function() {
                    autoPlay();
                    skipAds();
                    extractSources();
                }, 1000);
                
                // Return sources if available
                return extractSources();
            })();
        """.trimIndent()
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.i(TAG, "Starting JWPlayer source extraction for: $url")
        
        var sourcesJson: String? = null
        var capturedVideoUrls = mutableListOf<Pair<String, Map<String, String>>>()

        val resolver = WebViewResolver(
            interceptUrl = videoUrlRegex,
            additionalUrls = listOf(videoUrlRegex, Regex("""https://faselhd\.biz/sniffer_done""")),
            userAgent = null,
            useOkhttp = false,
            script = extractSourcesScript,
            scriptCallback = { result ->
                if (result != "null" && result.startsWith("[")) {
                     // Backup: still capture callback result if available
                    sourcesJson = result
                }
            },
            timeout = 35_000L
        )

        try {
            var masterPlaylistUrl: String? = null
            var masterHeaders: Map<String, String> = emptyMap()
            
            val (request, additionalRequests) = resolver.resolveUsingWebView(
                url = url,
                referer = referer,
                method = "GET"
            ) { capturedRequest ->
                val reqUrl = capturedRequest.url.toString()
                
                // 1. Check for JS completion signal with data
                if (reqUrl.contains("/sniffer_done")) {
                    Log.i(TAG, "🚀 JS extraction signal received!")
                    val uri = android.net.Uri.parse(reqUrl)
                    val sources = uri.getQueryParameter("sources")
                    if (!sources.isNullOrEmpty()) {
                        Log.i(TAG, "📦 Retrieved sources from URL")
                        sourcesJson = sources
                    }
                    return@resolveUsingWebView true // STOP immediately
                }
                
                // 2. Check for Master/Video URLs
                if (videoUrlRegex.containsMatchIn(reqUrl) && reqUrl.length >= MIN_VIDEO_URL_LENGTH) {
                    
                    // Check if this looks like a MASTER playlist (no quality indicators)
                    val qualityIndicators = listOf("hd1080", "hd720", "hd480", "1080p", "720p", "480p", "360p", "_hd", "_sd")
                    val isVariantPlaylist = qualityIndicators.any { reqUrl.lowercase().contains(it) }
                    
                    if (!isVariantPlaylist && reqUrl.contains(".m3u8")) {
                        Log.i(TAG, "✅ Master playlist detected: $reqUrl")
                        masterPlaylistUrl = reqUrl
                        masterHeaders = capturedRequest.headers.toMap()
                        return@resolveUsingWebView true // STOP immediately
                    } else {
                        capturedVideoUrls.add(reqUrl to capturedRequest.headers.toMap())
                        return@resolveUsingWebView false
                    }
                }
                false
            }

            // 1. Priority: Use extracted JWPlayer sources (contains explicit labels)
            if (!sourcesJson.isNullOrEmpty()) {
                Log.i(TAG, "Using extracted JWPlayer sources JSON")
                try {
                    // Raw JSON: [{"url":"...","label":"..."},{"url":"...","label":"..."}]
                    // Remove outer brackets and parse objects
                    val cleanJson = sourcesJson!!.trim()
                        .removePrefix("\"") // Remove potential wrapping quotes from evaluation result
                        .removeSuffix("\"")
                        .replace("\\\"", "\"") // Unescape quotes if stringified
                        .removePrefix("[")
                        .removeSuffix("]")
                    
                    val objects = cleanJson.split("},{").map { 
                        var obj = it
                        if (!obj.startsWith("{")) obj = "{$obj"
                        if (!obj.endsWith("}")) obj = "$obj}"
                        obj
                    }
                    
                    var sourcesFound = false
                    objects.forEach { obj ->
                        // Match "url":"..." and "label":"..."
                        val urlMatch = Regex(""""file|url":"([^"]+)"""").find(obj)
                        val labelMatch = Regex(""""label":"([^"]+)"""").find(obj)
                        
                        var videoUrl = urlMatch?.groupValues?.get(1)?.replace("\\/", "/")
                        val label = labelMatch?.groupValues?.get(1) ?: "Auto"
                        
                        if (videoUrl != null && videoUrl.length > MIN_VIDEO_URL_LENGTH) {
                            val quality = label.replace("p", "").toIntOrNull() ?: Qualities.Unknown.value
                            Log.i(TAG, "Extracted source: $label -> $videoUrl")
                            sourcesFound = true
                            
                            callback(
                                newExtractorLink(
                                    name,
                                    "$name - $label",
                                    videoUrl,
                                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = referer ?: url
                                    this.quality = quality
                                    this.headers = masterHeaders.ifEmpty { mapOf("User-Agent" to (resolver.userAgent ?: "")) }
                                }
                            )
                        }
                    }
                    if (sourcesFound) return // Success via JS extraction
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing sources JSON: ${e.message}")
                }
            }

            // 2. Fallback: Use captured network URLs (Master Playlist / Variants)
            // Prefer master playlist if found
            val finalUrl = masterPlaylistUrl ?: capturedVideoUrls.firstOrNull()?.first
            val finalHeaders = if (masterPlaylistUrl != null) masterHeaders else capturedVideoUrls.firstOrNull()?.second ?: emptyMap()
            
            if (finalUrl != null) {
                Log.i(TAG, "Fallback: Delivering network-captured URL: $finalUrl")
                
                callback(
                    newExtractorLink(
                        name,
                        name, // Default name if we don't know quality
                        finalUrl,
                        type = if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = referer ?: url
                        this.headers = finalHeaders
                    }
                )
            } else {
                Log.w(TAG, "No video URLs captured for: $url")
            }

        } catch (e: Exception) {
            Log.e(TAG, "WebViewResolver error: ${e.message}")
        }
    }
}
