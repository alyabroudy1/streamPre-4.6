package com.lagradost.cloudstream3.network

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log

/**
 * A WebViewClient wrapper that blocks dangerous URL schemes like intent://, market://, data:, etc.
 * 
 * Usage: webView.webViewClient = SafeWebViewClient(existingClient)
 * 
 * This is designed to be composable - wrap any existing WebViewClient and it will:
 * 1. Block non-HTTP scheme navigations
 * 2. Delegate all other callbacks to the wrapped client
 */
open class SafeWebViewClient(
    private val delegate: WebViewClient? = null
) : WebViewClient() {

    companion object {
        private const val TAG = "SafeWebViewClient"
        
        // Allowed URL schemes - only these can navigate
        private val ALLOWED_SCHEMES = setOf("http", "https", "about", "blob", "data", "javascript")
    }

    /**
     * Blocks navigation to non-HTTP URLs like intent://, market://, data:, javascript:, etc.
     */
    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url ?: return false
        val scheme = url.scheme?.lowercase()

        // Block non-HTTP schemes
        if (scheme != null && scheme !in ALLOWED_SCHEMES) {
            Log.w(TAG, "Blocked redirect to non-HTTP URL: $url (scheme: $scheme)")
            return true  // Consume the navigation, effectively blocking it
        }

        // Delegate to wrapped client if exists
        return delegate?.shouldOverrideUrlLoading(view, request) ?: false
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url != null) {
            val scheme = url.substringBefore("://").lowercase()
            if (scheme !in ALLOWED_SCHEMES) {
                Log.w(TAG, "Blocked redirect to non-HTTP URL: $url (scheme: $scheme)")
                return true
            }
        }
        @Suppress("DEPRECATION")
        return delegate?.shouldOverrideUrlLoading(view, url) ?: false
    }

    // Delegate all other WebViewClient callbacks to the wrapped client

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        delegate?.onPageStarted(view, url, favicon) 
            ?: super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        delegate?.onPageFinished(view, url) 
            ?: super.onPageFinished(view, url)
    }

    override fun onLoadResource(view: WebView?, url: String?) {
        delegate?.onLoadResource(view, url) 
            ?: super.onLoadResource(view, url)
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        return delegate?.shouldInterceptRequest(view, request) 
            ?: super.shouldInterceptRequest(view, request)
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        delegate?.onReceivedError(view, request, error) 
            ?: super.onReceivedError(view, request, error)
    }

    override fun onReceivedSslError(
        view: WebView?,
        handler: SslErrorHandler?,
        error: SslError?
    ) {
        delegate?.onReceivedSslError(view, handler, error) 
            ?: super.onReceivedSslError(view, handler, error)
    }
}
