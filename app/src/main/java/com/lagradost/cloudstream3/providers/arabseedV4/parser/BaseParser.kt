package com.lagradost.cloudstream3.providers.arabseedV4.parser

import com.lagradost.api.Log
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.providers.arabseedV4.parser.ParserInterface.ParsedItem
import com.lagradost.cloudstream3.providers.arabseedV4.parser.ParserInterface.ParsedLoadData
import com.lagradost.cloudstream3.providers.arabseedV4.parser.ParserInterface.ParsedEpisode

/**
 * Base abstract parser that implements common logic usually shared between providers.
 * Extends the [ParserInterface].
 */
abstract class BaseParser : ParserInterface {

    protected val TAG = "BaseParser"
    abstract val mainUrl: String
    abstract val providerName: String

    open val isMovieSelector: String = "div.movie" // Default guess

    // ================= HELPER FUNCTIONS =================

    /**
     * Tries multiple CSS selectors and returns the first matching Element.
     */
    protected fun Document.selectFirstOrNull(selectors: List<String>): Element? {
        for (selector in selectors) {
            val el = this.selectFirst(selector)
            if (el != null) return el
        }
        return null
    }

    protected fun Element.selectFirstOrNull(selectors: List<String>): Element? {
        for (selector in selectors) {
            val el = this.selectFirst(selector)
            if (el != null) return el
        }
        return null
    }

    /**
     * Fixes relative URLs to be absolute using mainUrl.
     */
    protected fun fixUrl(url: String): String {
        return when {
            url.isEmpty() -> ""
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "${mainUrl.trimEnd('/')}$url"
            else -> "${mainUrl.trimEnd('/')}/$url"
        }
    }
}
