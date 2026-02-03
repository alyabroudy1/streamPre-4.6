package com.lagradost.cloudstream3.providers.arabseedV4.config

import com.lagradost.cloudstream3.providers.arabseedV4.service.session.SessionState

data class ProviderConfig(
    val name: String,
    val fallbackDomain: String,
    val githubConfigUrl: String,
    val syncWorkerUrl: String? = null,
    val skipHeadless: Boolean = false,
    val userAgent: String = SessionState.DEFAULT_UA,
    
    // Domain Management
    val trustedDomains: List<String> = emptyList(),
    
    // Validation
    val validateWithContent: List<String> = emptyList(),
    
    // WebView Control (Opt-in)
    val webViewEnabled: Boolean = true,
    val webViewJsToInject: String? = null,
    val blockedUrlPatterns: List<Regex> = emptyList()
)
