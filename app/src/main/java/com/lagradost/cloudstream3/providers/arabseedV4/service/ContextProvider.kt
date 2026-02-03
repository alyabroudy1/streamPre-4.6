package com.lagradost.cloudstream3.providers.arabseedV4.service

import android.content.Context

/**
 * Abstraction to provide Context in both App and Plugin environments.
 * 
 * - App: Initialized with AcraApplication.context
 * - Plugin: Initialized with Plugin.load(context)
 */
object ContextProvider {
    @Volatile
    private var context: Context? = null

    fun init(ctx: Context) {
        // Use applicationContext to prevent memory leaks from Activity references
        context = ctx.applicationContext
    }

    fun require(): Context {
        return context ?: throw IllegalStateException("ContextProvider not initialized! Make sure to call init() in your Provider or Plugin entry point.")
    }
}
