package com.lagradost.cloudstream3.providers.arabseed.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.api.Log
import com.lagradost.cloudstream3.providers.arabseed.service.ProviderSessionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AlertDialog-based Cloudflare challenge solver.
 * Uses AlertDialog.Builder.setView() which is proven to display WebView correctly.
 */
object CloudflareChallengeDialog {
    private const val TAG = "CFDialog"
    private const val TIMEOUT_MS = 120_000L // 2 minutes

    /**
     * Show the CF challenge dialog and wait for result.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun solve(
        url: String,
        userAgent: String = ProviderSessionManager.UNIFIED_USER_AGENT
    ): ChallengeResult = withContext(Dispatchers.Main) {
        val activity = ActivityProvider.currentActivity
        
        if (activity == null) {
            Log.e(TAG, "No Activity context available")
            return@withContext ChallengeResult.failure("No Activity context")
        }
        
        val deferred = CompletableDeferred<ChallengeResult>()
        val handler = Handler(Looper.getMainLooper())
        var resultDelivered = false
        
        // Create WebView
        val webView = WebView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                800 // Fixed height in pixels for visibility
            )
            setBackgroundColor(Color.WHITE)
            
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = userAgent
                loadWithOverviewMode = true
                useWideViewPort = true
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            
            // Configure cookies for this WebView
            val wv = this
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            
            
            webChromeClient = android.webkit.WebChromeClient()
        }
        
        // Create container layout
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1a1a2e"))
            setPadding(0, 32, 0, 0)
            
            // Header
            addView(TextView(activity).apply {
                text = "🔒 Security Check"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(16, 16, 16, 8)
            })
            
            // Status
            addView(TextView(activity).apply {
                text = "Complete the security check to continue..."
                textSize = 14f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                setPadding(16, 0, 16, 16)
            })
            
            // WebView
            addView(webView)
        }
        
        // Build AlertDialog
        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
            .setView(container)
            .setCancelable(true)
            .setOnCancelListener {
                if (!resultDelivered) {
                    resultDelivered = true
                    try { webView.stopLoading(); webView.destroy() } catch (_: Exception) {}
                    deferred.complete(ChallengeResult.cancelled())
                }
            }
            .create()
        
        // CF detection script
        val cfDetectionScript = """
            (function() {
                try {
                    var title = (document.title || "").toLowerCase();
                    var isChallenge = 
                        title.includes("just a moment") || 
                        title.includes("cloudflare") ||
                        title.includes("checking your browser") ||
                        title.includes("تحقق") ||
                        document.getElementById('cf-wrapper') != null ||
                        document.getElementById('challenge-form') != null ||
                        document.querySelector('[data-ray]') != null;
                    
                    if (isChallenge) return "CHALLENGE";
                    
                    var cookies = document.cookie;
                    if (cookies && cookies.length > 0) return "SUCCESS:" + cookies;
                    return "CONTENT_NO_COOKIES";
                } catch(e) {
                    return "ERROR:" + e.message;
                }
            })();
        """.trimIndent()
        
        // Timeout handler
        val timeoutRunnable = Runnable {
            if (!resultDelivered) {
                resultDelivered = true
                Log.w(TAG, "Challenge timeout")
                try { webView.stopLoading(); webView.destroy() } catch (_: Exception) {}
                dialog.dismiss()
                deferred.complete(ChallengeResult.timeout())
            }
        }
        
        // Check function
        fun checkForChallengeComplete() {
            if (resultDelivered) return
            
            webView.evaluateJavascript(cfDetectionScript) { result ->
                val cleanResult = result?.removeSurrounding("\"") ?: return@evaluateJavascript
                Log.d(TAG, "Detection: ${cleanResult.take(50)}")
                
                when {
                    cleanResult == "CHALLENGE" -> {
                        handler.postDelayed({ checkForChallengeComplete() }, 1000)
                    }
                    cleanResult.startsWith("SUCCESS:") -> {
                        val cookieString = cleanResult.removePrefix("SUCCESS:")
                        val cookies = parseCookies(cookieString)
                        val cmCookies = CookieManager.getInstance().getCookie(url)
                        val allCookies = if (!cmCookies.isNullOrBlank()) {
                            parseCookies(cmCookies) + cookies
                        } else cookies
                        
                        Log.i(TAG, "Challenge solved! Cookies: ${allCookies.keys}")
                        resultDelivered = true
                        handler.removeCallbacks(timeoutRunnable)
                        handler.postDelayed({
                            try { webView.stopLoading(); webView.destroy() } catch (_: Exception) {}
                            dialog.dismiss()
                            deferred.complete(ChallengeResult.success(allCookies, webView.url ?: url))
                        }, 500)
                    }
                    cleanResult == "CONTENT_NO_COOKIES" -> {
                        val cmCookies = CookieManager.getInstance().getCookie(url)
                        if (!cmCookies.isNullOrBlank()) {
                            val cookies = parseCookies(cmCookies)
                            if (cookies.isNotEmpty()) {
                                resultDelivered = true
                                handler.removeCallbacks(timeoutRunnable)
                                try { webView.stopLoading(); webView.destroy() } catch (_: Exception) {}
                                dialog.dismiss()
                                deferred.complete(ChallengeResult.success(cookies, webView.url ?: url))
                                return@evaluateJavascript
                            }
                        }
                        handler.postDelayed({ checkForChallengeComplete() }, 1000)
                    }
                    else -> {
                        handler.postDelayed({ checkForChallengeComplete() }, 1000)
                    }
                }
            }
        }
        
        // Set WebViewClient
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                super.onPageFinished(view, loadedUrl)
                Log.d(TAG, "Page finished: ${loadedUrl?.take(80)}")
                checkForChallengeComplete()
            }
            
            override fun onPageStarted(view: WebView?, loadedUrl: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, loadedUrl, favicon)
                Log.d(TAG, "Page started: ${loadedUrl?.take(80)}")
            }
            
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val requestUrl = request?.url?.toString() ?: return false
                if (!requestUrl.startsWith("http://") && !requestUrl.startsWith("https://")) {
                    return true
                }
                return false
            }
        }
        
        // Show dialog and load URL
        Log.i(TAG, "Showing CF challenge for: $url")
        dialog.show()
        
        // Make dialog full width
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        
        handler.postDelayed(timeoutRunnable, TIMEOUT_MS)
        webView.loadUrl(url)
        
        deferred.await()
    }
    
    private fun parseCookies(cookieString: String): Map<String, String> {
        return cookieString.split(";").associate {
            val parts = it.split("=", limit = 2)
            (parts.getOrNull(0)?.trim() ?: "") to (parts.getOrNull(1)?.trim() ?: "")
        }.filter { it.key.isNotBlank() && it.value.isNotBlank() }
    }

    data class ChallengeResult(
        val success: Boolean,
        val cookies: Map<String, String>,
        val finalUrl: String,
        val reason: String
    ) {
        companion object {
            fun success(cookies: Map<String, String>, finalUrl: String) = ChallengeResult(true, cookies, finalUrl, "solved")
            fun timeout() = ChallengeResult(false, emptyMap(), "", "timeout")
            fun cancelled() = ChallengeResult(false, emptyMap(), "", "cancelled")
            fun failure(reason: String) = ChallengeResult(false, emptyMap(), "", reason)
        }
    }
}
