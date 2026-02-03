package com.lagradost.cloudstream3.providers.arabseedV4.service.webview

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.*
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.providers.arabseedV4.service.http.CloudflareDetector
import com.lagradost.api.Log
import kotlinx.coroutines.*
import java.net.URI

class WebViewEngine(
    private val activityProvider: () -> android.app.Activity?
) {
    private val TAG = "WebViewEngine"
    
    enum class Mode { HEADLESS, FULLSCREEN }
    
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun runSession(
        url: String,
        mode: Mode,
        userAgent: String,
        exitCondition: ExitCondition,
        timeout: Long = 60_000L,
        delayMs: Long = 0L
    ): WebViewResult = withContext(Dispatchers.Main) {
        
        val activity = activityProvider() ?: return@withContext WebViewResult.Error("No Activity context")
        
        val deferred = CompletableDeferred<WebViewResult>()
        var resultDelivered = false
        var dialog: Dialog? = null
        var webView: WebView? = null
        
        val timeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(timeout)
            if (!resultDelivered) {
                resultDelivered = true
                val partialHtml = try { webView?.let { getHtmlFromWebView(it) } } catch (e: Exception) { null }
                cleanup(webView, dialog)
                deferred.complete(WebViewResult.Timeout(url, partialHtml))
            }
        }
        
        try {
            webView = WebView(activity).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = userAgent
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }
            }
            
            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }
            
            when (mode) {
                Mode.FULLSCREEN -> {
                    dialog = createDialog(activity, webView)
                    dialog?.show()
                }
                else -> {}
            }
            
            webView?.webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    view?.evaluateJavascript(
                        """
                        (function() {
                            if (typeof window.object__info === 'undefined') { window.object__info = {}; }
                            if (navigator.userAgent.indexOf("Windows") !== -1) {
                                Object.defineProperty(navigator, 'platform', { get: function() { return 'Win32'; } });
                                Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 0; } });
                                Object.defineProperty(navigator, 'webdriver', { get: function() { return false; } });
                            }
                        })();
                        """.trimIndent(), null
                    )
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    val currentUrl = view?.url ?: loadedUrl ?: url
                    if (resultDelivered) return
                    
                    CoroutineScope(Dispatchers.Main).launch {
                         if (delayMs > 0) delay(delayMs)
                         val html = getHtmlFromWebView(view!!)
                         
                         val shouldExit = when (exitCondition) {
                             is ExitCondition.PageLoaded -> !CloudflareDetector.isCloudflareChallenge(html)
                             is ExitCondition.CookiesPresent -> {
                                 val cookies = extractCookies(currentUrl)
                                 exitCondition.keys.all { cookies.containsKey(it) }
                             }
                         }
                         
                         if (shouldExit) {
                             resultDelivered = true
                             timeoutJob.cancel()
                             val cookies = extractCookies(currentUrl)
                             cleanup(view, dialog)
                             deferred.complete(WebViewResult.Success(cookies, html, currentUrl))
                         }
                    }
                }
            }
            
            val extraHeaders = mutableMapOf("X-Requested-With" to "")
            webView?.loadUrl(url, extraHeaders)
            
        } catch (e: Exception) {
            resultDelivered = true
            timeoutJob.cancel()
            cleanup(webView, dialog)
            deferred.complete(WebViewResult.Error(e.message ?: "Unknown error"))
        }
        
        deferred.await()
    }
    
    private fun createDialog(activity: android.app.Activity, webView: WebView): Dialog {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1a1a2e"))
            addView(webView.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            })
        }
        return Dialog(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen).apply {
            setContentView(container)
            setCancelable(true)
        }
    }
    
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun getHtmlFromWebView(webView: WebView): String = suspendCancellableCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            webView.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { result ->
                val html = try {
                    if (result == null || result == "null") ""
                    else org.json.JSONTokener(result).nextValue().toString()
                } catch (e: Exception) { "" }
                cont.resume(html) {}
            }
        }
    }
    
    private fun extractCookies(url: String): Map<String, String> {
        val cookies = mutableMapOf<String, String>()
        CookieManager.getInstance().getCookie(url)?.split(";")?.forEach { cookie ->
            val parts = cookie.trim().split("=", limit = 2)
            if (parts.size == 2) cookies[parts[0].trim()] = parts[1].trim()
        }
        return cookies
    }
    
    private fun cleanup(webView: WebView?, dialog: Dialog?) {
        try {
            dialog?.dismiss()
            webView?.let { view ->
                view.stopLoading()
                (view.parent as? ViewGroup)?.removeView(view)
                view.post { try { view.destroy() } catch (e: Exception) {} }
            }
        } catch (e: Exception) {}
    }
}

sealed class ExitCondition {
    object PageLoaded : ExitCondition()
    data class CookiesPresent(val keys: List<String>) : ExitCondition()
}

sealed class WebViewResult {
    data class Success(val cookies: Map<String, String>, val html: String, val finalUrl: String) : WebViewResult()
    data class Timeout(val lastUrl: String, val partialHtml: String?) : WebViewResult()
    data class Error(val reason: String) : WebViewResult()
}
