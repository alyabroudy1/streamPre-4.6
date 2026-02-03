package com.lagradost.cloudstream3.providers.arabseedV4.service.http

data class RequestResult(
    val success: Boolean,
    val html: String?,
    val error: Throwable? = null,
    val responseCode: Int = 0,
    val finalUrl: String? = null
) {
    val isCloudflareBlocked: Boolean
        get() = CloudflareDetector.isBlocked(responseCode, html)

    companion object {
        fun success(html: String, code: Int, finalUrl: String): RequestResult = 
            RequestResult(true, html, null, code, finalUrl)
            
        fun failure(error: Throwable): RequestResult = 
            RequestResult(false, null, error)
            
        fun failure(message: String, code: Int = 0): RequestResult =
            RequestResult(false, null, Exception(message), code)
            
        fun cloudflareBlocked(code: Int, finalUrl: String): RequestResult =
            RequestResult(false, null, Exception("Cloudflare Blocked"), code, finalUrl)
    }
}


