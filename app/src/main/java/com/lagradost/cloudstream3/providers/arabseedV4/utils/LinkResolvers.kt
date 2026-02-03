package com.lagradost.cloudstream3.providers.arabseedV4.utils

import android.util.Base64
import java.nio.charset.StandardCharsets

/**
 * Shared logic for resolving common link interactions.
 */
object LinkResolvers {

    /**
     * Decode standard Base64 URLs often used in "url=" parameters.
     */
    fun decodeBase64(encoded: String): String? {
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            String(bytes, StandardCharsets.UTF_8).trim()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract URL from common "url=" query parameter pattern.
     */
    fun extractUrlParam(serverUrl: String): String? {
        // Regex to find url=... considering constraints
        // Simple substring logic is often more robust for basics
        if (serverUrl.contains("url=")) {
            val param = serverUrl.substringAfter("url=")
            // If it looks base64 (no slashes, ends with =), try decoding
            if (!param.contains("/") && (param.endsWith("=") || param.length > 20)) {
                decodeBase64(param)?.let { return it }
            }
            return param
        }
        return null
    }
    
    /**
     * Extract .m3u8 or .mp4 links from JWPlayer sources: [...] block
     */
    fun extractJwPlayerSources(html: String): List<String> {
        val sources = mutableListOf<String>()
        val fileRegex = """["']?file["']?\s*:\s*["']([^"']+)["']""".toRegex()
        
        fileRegex.findAll(html).forEach { match ->
            val url = match.groupValues[1]
            if (url.contains(".m3u8") || url.contains(".mp4")) {
                sources.add(url)
            }
        }
        return sources.distinct()
    }
}
