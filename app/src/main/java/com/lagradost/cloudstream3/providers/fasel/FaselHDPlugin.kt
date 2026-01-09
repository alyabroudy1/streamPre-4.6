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
        val provider = FaselHD()
        synchronized(APIHolder.allProviders) {
            APIHolder.allProviders.add(provider)
        }
        APIHolder.addPluginMapping(provider)
    }
}
