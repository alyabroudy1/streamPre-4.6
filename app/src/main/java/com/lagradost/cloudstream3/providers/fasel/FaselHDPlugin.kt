package com.lagradost.cloudstream3.providers.fasel

import com.lagradost.cloudstream3.APIHolder

/**
 * Registration entry point for FaselHD provider.
 * 
 * Call FaselHDPlugin.registerAll() once during app initialization.
 * 
 * Migration to standalone repo:
 * 1. Delete this package (providers/fasel/)
 * 2. Remove the registerAll() call from CommonActivity
 */
object FaselHDPlugin {
    fun registerAll() {
        // Register standard extractor globally for all known domains
        synchronized(com.lagradost.cloudstream3.utils.extractorApis) {
            val domains = listOf("faselhd.biz", "faselhds.biz", "faselhd.club", "faselhd.it", "faselhd.io")
            domains.forEach { domain ->
                if (com.lagradost.cloudstream3.utils.extractorApis.none { it.mainUrl.contains(domain) }) {
                    com.lagradost.cloudstream3.utils.extractorApis.add(object : FaselSniffer() {
                        override val mainUrl = "https://$domain"
                    })
                }
            }
        }

        val provider = FaselHD()
        synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.add(provider)
        }
        APIHolder.addPluginMapping(provider)
    }
}
