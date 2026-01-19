package com.lagradost.cloudstream3.providers.arabseed.utils

import android.content.Context
import java.lang.ref.WeakReference

object PluginContext {
    private var contextRef: WeakReference<Context>? = null

    fun init(context: Context) {
        contextRef = WeakReference(context)
    }

    val context: Context?
        get() = contextRef?.get()
}
