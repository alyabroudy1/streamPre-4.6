package com.lagradost.cloudstream3.providers.arabseedV4

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.providers.arabseedV4.config.ProviderConfig
import com.lagradost.cloudstream3.providers.arabseedV4.parser.ArabseedParser
import com.lagradost.cloudstream3.providers.arabseedV4.service.ContextProvider
import com.lagradost.cloudstream3.providers.arabseedV4.service.ProviderHttpService
import com.lagradost.cloudstream3.providers.arabseedV4.utils.ActivityProvider
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import kotlinx.coroutines.runBlocking
import com.lagradost.cloudstream3.extractors.ArabseedLazyExtractor

/**
 * Arabseed V4 - Clean Architecture Implementation.
 * 
 * Uses independent service stack for better isolation and maintainability.
 * 100% Logic Parity with Legacy/Plugin Implementation.
 */
class ArabseedV4 : MainAPI() {
    
    override var mainUrl = "https://arabseed.show"
    override var name = "ArabseedV4" // Distinct name
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    
    override val mainPage = mainPageOf(
        "/movies-1/" to "أفلام",
        "/series-1/" to "مسلسلات"
    )
    
    companion object {
        private const val GITHUB_CONFIG = "https://raw.githubusercontent.com/alyabroudy1/omarC/main/configs/arabseed.json"
        // EXACT UA from FaselHD / Legacy
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.230 Mobile Safari/537.36"
    }

    // Initialize ContextProvider immediately with global context
    init {
        // Essential step for built-in provider
        com.lagradost.cloudstream3.AcraApplication.context?.let { 
            ContextProvider.init(it) 
            ActivityProvider.initCompat(it)
        }
    }

    private val parser = ArabseedParser()

    private val httpService by lazy {
        ProviderHttpService.create(
            context = ContextProvider.require(),
            config = ProviderConfig(
                name = name,
                fallbackDomain = "arabseed.show",
                githubConfigUrl = GITHUB_CONFIG,
                userAgent = USER_AGENT,
                syncWorkerUrl = "https://omarstreamcloud.alyabroudy1.workers.dev",
                skipHeadless = true,
                trustedDomains = listOf("arabseed", "asd"),
                validateWithContent = listOf("ArabSeed", "عرب سيد")
            ),
            parser = parser,
            activityProvider = { ActivityProvider.currentActivity }
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        httpService.ensureInitialized()
        
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val items = httpService.getMainPage(url)
        
        if (items.isEmpty()) return null
        
        val responses = items.map { item ->
            newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                this.posterUrl = item.posterUrl
                this.posterHeaders = httpService.getImageHeaders()
            }
        }
        return newHomePageResponse(request.name, responses)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        httpService.ensureInitialized()
        return httpService.search(query).map { item ->
             newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                 this.posterUrl = item.posterUrl
                 this.posterHeaders = httpService.getImageHeaders()
             }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        httpService.ensureInitialized()
        
        val doc = httpService.getDocument(url) ?: return null
        val data = parser.parseLoadPage(doc, url) ?: return null
        
        return if (data.type == TvType.Movie) {
             newMovieLoadResponse(data.title, url, TvType.Movie, data.url) {
                 this.posterUrl = data.posterUrl
                 this.year = data.year
                 this.plot = data.plot
                 this.tags = data.tags
                 this.posterHeaders = httpService.getImageHeaders()
             }
        } else {
             val episodes = parser.parseEpisodes(doc, null)
             newTvSeriesLoadResponse(data.title, url, TvType.TvSeries, episodes.map {
                 newEpisode(it.url) {
                     this.name = it.name
                     this.season = it.season
                     this.episode = it.episode
                 }
             }) {
                 this.posterUrl = data.posterUrl
                 this.year = data.year
                 this.plot = data.plot
                 this.tags = data.tags
                 this.posterHeaders = httpService.getImageHeaders()
             }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Get Main Doc or Watch Doc
        var doc = httpService.getDocument(data) ?: return false
        
        // Check if we have server list or iframe
        var isWatchPage = doc.select("ul > li[data-link], ul > h3").isNotEmpty() ||
                doc.select("iframe[name=player_iframe]").isNotEmpty()
        
        var watchDoc = if (isWatchPage) doc else null
        
        if (watchDoc == null) {
            val watchUrl = doc.select("a.watch__btn").attr("href")
            if (watchUrl.isNotBlank()) {
                watchDoc = httpService.getDocument(watchUrl)
            }
        }
        
        if (watchDoc == null) return false
        
        // 3. Dynamic Quality Extraction
        // Extract available qualities from tabs
        val availableQualities = parser.extractQualities(watchDoc)
        val postId = parser.extractPostId(watchDoc) ?: ""
        val csrfToken = parser.extractCsrfToken(doc) ?: "" // Need token for AJAX
        
        var found = false
        
        // ==================== LEGACY LINKS (PRIORITY) ====================
        // Logic from Reference: Parse data-link elements grouped by H3 headers
        // This handles cases where servers are listed directly as links
        val elements = watchDoc.select("ul > li[data-link], ul > h3")
        val indexOperators = mutableListOf<Int>()
        elements.forEachIndexed { index, element ->
            if (element.`is`("h3")) {
                indexOperators.add(index)
            }
        }
        
        val watchLinks = if (indexOperators.isNotEmpty()) {
            indexOperators.map { index ->
                val endIndex = elements.drop(index + 1).indexOfFirst { it.`is`("h3") }.let { if (it == -1) elements.size else it + index + 1 }
                val qualityText = elements[index].text()
                val quality = Regex("""\d+""").find(qualityText)?.value?.toIntOrNull() ?: 0
                val links = elements.subList(index + 1, endIndex).filter { !it.`is`("h3") }
                quality to links
            }
        } else {
             // Fallback: use active quality from tabs if available
             val activeQuality = availableQualities.find { watchDoc.select("ul.qualities__list li[data-quality='${it.quality}']").hasClass("active") }?.quality ?: 0
             listOf(activeQuality to elements.filter { !it.`is`("h3") })
        }
        
        watchLinks.forEach { (quality, linkElements) ->
            linkElements.forEach { element ->
                val rawUrl = element.attr("data-link").ifBlank { element.attr("data-url") }
                if (rawUrl.isNotBlank()) {
                    loadExtractor(rawUrl, watchDoc.location(), subtitleCallback) { link ->
                        // Enrich with quality if missing
                        if (link.quality == Qualities.Unknown.value && quality > 0) {
                             link.quality = quality
                        }
                        callback(link)
                        found = true
                    }
                }
            }
        }

        // ==================== DIRECT EMBEDS (PRIORITY) ====================
        val directEmbeds = parser.extractDirectEmbeds(watchDoc)
        directEmbeds.forEach { embedUrl ->
            // Manual handling for ReviewRate
            if (embedUrl.contains("reviewrate")) {
                // Emit directly (Interceptor handles resolution)
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name Direct",
                        url = embedUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = watchDoc.location()
                    }
                )
            } else {
                 loadExtractor(embedUrl, watchDoc.location(), subtitleCallback, callback)
            }
        }
        
        // ==================== VISIBLE SERVERS (LAZY) ====================
        var servers = parser.extractVisibleServers(watchDoc).toMutableList()
        
        // Cross-product: Use all available qualities for ALL unique servers found
        // If no servers found, assume Server 0 (Auto) exists if we have qualities
        val uniqueServers = servers.distinctBy { it.serverId }.toMutableList()
        if (uniqueServers.isEmpty() && availableQualities.isNotEmpty()) {
             // Fallback to Server 0 (Auto) if no explicit servers are listed but qualities exist
             uniqueServers.add(ArabseedParser.ServerData(postId, 0, "0", "Server Auto"))
        }

        // Determine qualities to process (Prioritize extracted qualities, fallback to defaults)
        val qualitiesToProcess = if (availableQualities.isNotEmpty()) {
             availableQualities.map { it.quality }.distinct().sortedDescending()
        } else {
             // Fallback to qualities seen in server list, or default 720p
             val sQualities = servers.map { it.quality }.filter { it > 0 }.distinct().sortedDescending()
             if (sQualities.isNotEmpty()) sQualities else listOf(720) 
        }
        
        val pageReferer = watchDoc.location()
        val encodedReferer = java.net.URLEncoder.encode(pageReferer, "UTF-8")
        
        if (postId.isNotBlank() && csrfToken.isNotBlank()) {
            qualitiesToProcess.forEach { quality ->
                uniqueServers.forEach { server ->
                    val linkName = server.title.ifBlank { "Server ${server.serverId}" }
                    
                    // Construct virtual URL overriding the quality parameter
                    val currentBaseUrl = try {
                        val uri = java.net.URI(pageReferer)
                        "${uri.scheme}://${uri.host}"
                    } catch (e: Exception) { "https://arabseed.show" }
                    
                    val virtualUrl = "$currentBaseUrl/get__watch__server/?post_id=$postId&quality=$quality&server=${server.serverId}&csrf_token=$csrfToken&referer=$encodedReferer"
                    
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$linkName (${quality}p)",
                            url = virtualUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = quality
                        }
                    )
                    found = true
                }
            }
        }
        
        return found || directEmbeds.isNotEmpty()
    }

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        // Handle both lazy servers and reviewrate direct embeds
        if (extractorLink.url.contains("/get__watch__server/") || extractorLink.url.contains("reviewrate")) {
            return Interceptor { chain ->
                val request = chain.request()
                val url = request.url
                val urlString = url.toString()
                
                // Handle JIT Direct Embeds (ReviewRate)
                if (urlString.contains("reviewrate")) {
                    try {
                        val referer = request.header("Referer")
                        val directUrl = runBlocking {
                            // Robust Manual Extraction
                            var response = try {
                                val headers = if (!referer.isNullOrBlank()) mapOf("Referer" to referer) else emptyMap()
                                httpService.getDocument(urlString, headers = headers)?.html()
                            } catch (e: Exception) { null }
                            
                            // Fallback without referer
                            if (response == null) {
                                 response = try { httpService.getDocument(urlString)?.html() } catch(e: Exception) { null }
                            }
                            
                            val html = response ?: ""
                            var foundUrl: String? = null
                            
                            // Regex patterns
                            if (foundUrl == null) foundUrl = Regex("""file:\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                            if (foundUrl == null) foundUrl = Regex("""<source[^>]+src=["']([^"']+\.mp4)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                            if (foundUrl == null) foundUrl = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                            if (foundUrl == null) foundUrl = Regex("""source:\s*["']([^"']+\.mp4)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                            if (foundUrl == null) foundUrl = Regex("""var\s+url\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
                            
                            return@runBlocking foundUrl
                        }
                        
                        if (!directUrl.isNullOrBlank()) {
                            return@Interceptor chain.proceed(
                                request.newBuilder()
                                    .url(directUrl)
                                    .header("Referer", urlString)
                                    .build()
                            )
                        } else {
                            return@Interceptor chain.proceed(request)
                        }
                    } catch (e: Exception) {
                        return@Interceptor chain.proceed(request)
                    }
                }

                // Handle Lazy Servers
                if (urlString.contains("/get__watch__server/")) {
                    var resolvedLink: ExtractorLink? = null
                    try {
                        runBlocking {
                             val extractor = ArabseedLazyExtractor { endpoint, data, referer ->
                                 httpService.postText(
                                     endpoint,
                                     data,
                                     referer = referer,
                                     headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                                 )
                             }
                             extractor.getUrl(urlString, null, {}) { link ->
                                 resolvedLink = link
                             }
                        }
                    } catch(e: Exception) { }
                    
                    resolvedLink?.let { link ->
                         val builder = request.newBuilder().url(link.url)
                         if (link.referer.isNotBlank()) {
                             builder.header("Referer", link.referer)
                         }
                         return@Interceptor chain.proceed(builder.build())
                    }
                }
                
                chain.proceed(request)
            }
        }
        return null
    }

    private fun getQualityFromName(qualityName: String): Int {
        return when {
            qualityName.contains("360") -> Qualities.P360.value
            qualityName.contains("480") -> Qualities.P480.value
            qualityName.contains("720") -> Qualities.P720.value
            qualityName.contains("1080") -> Qualities.P1080.value
            qualityName.contains("4k") || qualityName.contains("2160") -> Qualities.P2160.value
            else -> Qualities.Unknown.value
        }
    }
}
