package com.lagradost.cloudstream3.providers.fasel

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element

class FaselHD : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://www.faselhds.biz"
    private val alternativeUrl = "https://www.faselhd.club"
    override var name = "FaselHD"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)
    private val cfKiller = CloudflareKiller()

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = select("div.postDiv a").attr("href") ?: return null
        val posterUrl = select("div.postDiv a div img").attr("data-src") ?:
        select("div.postDiv a div img").attr("src")
        val title = select("div.postDiv a div img").attr("alt")
        val quality = select(".quality").first()?.text()?.replace("1080p |-".toRegex(), "")
        val type = if(title.contains("فيلم")) TvType.Movie else TvType.TvSeries
        
        return newMovieSearchResponse(
            title.replace("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي".toRegex(),""),
            url,
            type
        ) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
            this.posterHeaders = cfKiller.getCookieHeaders(mainUrl).toMap() + mapOf("Referer" to mainUrl)
        }
    }
    override val mainPage = mainPageOf(
            "$mainUrl/all-movies/page/0" to "جميع الافلام",
        )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        var doc = app.get(request.data + page, interceptor = cfKiller).document
        if(doc.select("title").text() == "Just a moment...") {
            doc = app.get(request.data.replace(mainUrl, alternativeUrl) + page, interceptor = cfKiller, timeout = 120).document
        }
        val list = doc.select("div[id=\"postList\"] div[class=\"col-xl-2 col-lg-2 col-md-3 col-sm-3\"]")
            .mapNotNull { element ->
                element.toSearchResponse()
            }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ","+")
        var d = app.get("$mainUrl/?s=$q", interceptor = cfKiller).document
        if(d.select("title").text() == "Just a moment...") {
            d = app.get("$alternativeUrl/?s=$q", interceptor = cfKiller, timeout = 120).document
        }
        return d.select("div[id=\"postList\"] div[class=\"col-xl-2 col-lg-2 col-md-3 col-sm-3\"]")
            .mapNotNull {
                it.toSearchResponse()
            }
    }


    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url, interceptor = cfKiller).document
        if(doc.select("title").text() == "Just a moment...") {
            doc = app.get(url.replace(mainUrl, alternativeUrl), interceptor = cfKiller, timeout = 120).document
        }
        val isMovie = doc.select("div.epAll").isEmpty()
        val posterUrl = doc.select("div.posterImg img").attr("src")
            .ifEmpty { doc.select("div.seasonDiv.active img").attr("data-src") }

        val year = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]").firstOrNull {
            it.text().contains("سنة|موعد".toRegex())
        }?.text()?.getIntFromText()

        val title =
            doc.select("title").text().replace(" - فاصل إعلاني", "")
                .replace("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|$year".toRegex(),"")
        val duration = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]").firstOrNull {
            it.text().contains("مدة|توقيت".toRegex())
        }?.text()?.getIntFromText()

        val tags = doc.select("div[id=\"singleList\"] div[class=\"col-xl-6 col-lg-6 col-md-6 col-sm-6\"]:contains(تصنيف الفيلم) a").map {
            it.text()
        }
        val recommendations = doc.select("div#postList div.postDiv").mapNotNull {
            it.toSearchResponse()
        }
        val synopsis = doc.select("div.singleDesc p").text()
        return if (isMovie) {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.duration = duration
                this.tags = tags
                this.recommendations = recommendations
                this.posterHeaders = cfKiller.getCookieHeaders(mainUrl).toMap() + mapOf("Referer" to mainUrl)
            }
        } else {
            val episodes = ArrayList<Episode>()
            doc.select("div.epAll a").map {
                episodes.add(
                    newEpisode(it.attr("href")) {
                       this.name = it.text()
                       this.season = doc.select("div.seasonDiv.active div.title").text().getIntFromText() ?: 1
                       this.episode = it.text().getIntFromText()
                    }
                )
            }
            doc.select("div[id=\"seasonList\"] div[class=\"col-xl-2 col-lg-3 col-md-6\"] div.seasonDiv")
                .not(".active").amap { it ->
					val id = it.attr("onclick").replace(".*\\/\\?p=|'".toRegex(), "")
                    var s = app.get("$mainUrl/?p="+id).document
                    if(s.select("title").text() == "Just a moment...") {
                        s = app.get("$alternativeUrl/?p="+id, interceptor = cfKiller).document
                    }
                    s.select("div.epAll a").map {
                        episodes.add(
                            newEpisode(it.attr("href")) {
                                this.name = it.text()
                                this.season = s.select("div.seasonDiv.active div.title").text().getIntFromText()
                                this.episode = it.text().getIntFromText()
                            }
                        )
                    }
                }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinct().sortedBy { it.episode }) {
                this.duration = duration
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = recommendations
                this.posterHeaders = cfKiller.getCookieHeaders(mainUrl).toMap() + mapOf("Referer" to mainUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var doc = app.get(data, interceptor = cfKiller).document
        if(doc.select("title").text() == "Just a moment...") {
            doc = app.get(data.replace(mainUrl, alternativeUrl), interceptor = cfKiller).document
        }
        
        val urlRegex = "'.*?'".toRegex()
        val elements = doc.select(".signleWatch ul.tabs-ul li[onclick]")
        com.lagradost.api.Log.d("FaselHD", "Found ${elements.size} potential player tabs")
        
        var foundLink = false
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
             foundLink = true
             callback(link)
        }

        for (li in elements) {
            // Stop if we found a link
            if (foundLink) break
            
            val onclickAttr = li.attr("onclick")
            val match = urlRegex.find(onclickAttr)
            val url = match?.value?.replace("'", "")
            
            com.lagradost.api.Log.d("FaselHD", "Parsed URL from tab: $url")
            
            if (!url.isNullOrEmpty() && url.contains("faselhd")) {
                com.lagradost.api.Log.i("FaselHD", "Calling loadExtractor for: $url")
                loadExtractor(url, subtitleCallback, wrappedCallback)
            } else {
                com.lagradost.api.Log.w("FaselHD", "URL skipped: $url")
            }
        }
        return true
    }
}
