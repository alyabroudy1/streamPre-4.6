package com.lagradost.cloudstream3.providers.arabseedV4.service.http

object CloudflareDetector {
    fun isBlocked(code: Int, html: String?): Boolean {
        if (code == 403 || code == 503) {
            val h = html ?: ""
            return h.contains("Just a moment...") || 
                   h.contains("Enable JavaScript and cookies to continue") ||
                   h.contains("cloudflare")
        }
        return false
    }
    
    fun isCloudflareChallenge(html: String?): Boolean {
        val h = html ?: return false
        return h.contains("Just a moment...") || 
               h.contains("Enable JavaScript and cookies to continue") ||
               h.contains("challenge-platform")
    }
}
