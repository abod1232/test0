package com.lagradost.cloudstream3.plugins

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor

class CimaWbas : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://mycima.page"
    override var name = "MyCima"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime, TvType.AsianDrama)

    companion object {
        const val TAG = "MyCima"
    }

    // ================================
    //     Cloudflare & HTTP Helpers
    // ================================

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cfInterceptor: Interceptor get() = cloudflareKiller

    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept-Language" to "ar,en-US;q=0.9",
        "Referer" to "$mainUrl/"
    )

// دالة مساعدة لطلبات GET تمنع الكاش نهائياً
    private suspend fun httpGet(url: String, customHeaders: Map<String, String> = emptyMap(), timeout: Long = 15000L): org.jsoup.nodes.Document {
        // إضافة هيدرات تمنع السيرفر من إعطائنا 304
        val noCacheHeaders = mapOf(
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )
        val mergedHeaders = standardHeaders + customHeaders + noCacheHeaders
        
        return app.get(
            url, 
            headers = mergedHeaders, 
            interceptor = cfInterceptor, 
            timeout = timeout,
            cacheTime = 0 // <--- هذا السطر هو الأهم لمنع كاش Cloudstream الداخلي
        ).document
    }

    // دالة مساعدة لطلبات POST تمنع الكاش نهائياً
    // دالة مساعدة لطلبات POST تمنع الكاش نهائياً
    private suspend fun httpPost(url: String, data: Map<String, String>, customHeaders: Map<String, String> = emptyMap(), timeout: Long = 15000L) = 
        app.post(
            url, 
            data = data, 
            headers = standardHeaders + customHeaders + mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache"), 
            interceptor = cfInterceptor, 
            timeout = timeout,
            cacheTime = 0
        )
    private fun extractNumbers(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""\d+""").find(text)?.value?.toIntOrNull()
    }

    private fun String.safeBase64Decode(): String {
        return try {
            String(Base64.decode(this, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }

    private fun getPosterFromStyle(element: Element?): String? {
        val style = element?.attr("style")?.ifBlank { null } ?: element?.attr("data-lazy-style")
        return style?.let {
            Regex("""url\((.*?)\)""").find(it)?.groupValues?.get(1)
                ?.trim('\'', '"', ' ')
                ?.ifBlank { null }
        }
    }

    private fun extractServerName(element: Element): String {
        return (element.ownText().ifBlank { element.text() }).replace(Regex("\\s+"), " ").trim()
    }

    private fun String.encodeURL(): String {
        return try {
            URLEncoder.encode(this, "UTF-8")
        } catch (e: Exception) { this }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("div.Thumb--GridItem a") ?: return null
        val url = linkElement.attr("href")
        if (url.isBlank()) return null

        val posterUrl = getPosterFromStyle(linkElement.selectFirst("span.BG--GridItem"))
        val titleTag = linkElement.selectFirst("strong") ?: return null
        val title = titleTag.ownText().trim()
        val year = titleTag.selectFirst("span.year")?.text()?.let { extractNumbers(it) }

        val isMovie = this.selectFirst("div.Episode--number") == null && !url.contains("/series/")

        return if (isMovie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    // ================================
    //     Main Page
    // ================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = if (page > 1) {
                "${request.data.removeSuffix("/")}/page/$page/"
            } else {
                request.data
            }

            // استخدام httpGet بدلاً من app.get
            val document = httpGet(url)

            val isBannerRequest = request.name == "احدث الاضافات" && page == 1
            val selector = "div.Grid--WecimaPosts div.GridItem, div#MainFiltar div.GridItem, div.Slider--Grid div.GridItem"
            val list = document.select(selector).mapNotNull { it.toSearchResult() }

            val homePageList = HomePageList(
                name = request.name,
                list = list,
                isHorizontalImages = isBannerRequest
            )

            // التعديل هنا: استخدام الطريقة الحديثة لدعم Cloudstream
            newHomePageResponse(homePageList)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load page $page for ${request.name}", e)
            val homePageList = HomePageList(
                name = request.name,
                list = emptyList(),
                isHorizontalImages = false
            )
            
            // التعديل هنا أيضاً
            newHomePageResponse(homePageList)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/filtering/?keywords=${query.encodeURL()}"
        val document = httpGet(url)
        return document.select("div#MainFiltar div.GridItem").mapNotNull { it.toSearchResult() }
    }

    // ================================
    //     Load
    // ================================

    override suspend fun load(url: String): LoadResponse? {
        // الطلب الأول للصفحة سيقوم بفك حماية Cloudflare إن وجدت
        val document = try {
            httpGet(url)
        } catch (e: Exception) {
            return null
        }

        val title = document.selectFirst("div.Title--Content--Single-begin > h1")?.ownText()?.trim() ?: return null
        val poster = getPosterFromStyle(document.selectFirst("wecima.separated--top"))
        val year = document.selectFirst("div.Title--Content--Single-begin h1 a")?.text()?.toIntOrNull()
        val plot = document.selectFirst("div.StoryMovieContent")?.text()?.trim()
        val tags = document.select("ul.Terms--Content--Single-begin li:has(span:contains(النوع)) p a").map { it.text() }
        val recommendations = document.select("div.Grid--WecimaPosts div.GridItem").mapNotNull { it.toSearchResult() }

        val isSeriesPage = document.selectFirst("div.SeasonsList, .Seasons--Episodes") != null
        val seriesUrlFromEpisode = document.selectFirst("ul.Terms--Content--Single-begin li:contains(المسلسل) a")?.attr("href")

        fun extractPostId(doc: org.jsoup.nodes.Document): String? {
            doc.selectFirst("input[name=post_id]")?.attr("value")?.takeIf { it.isNotBlank() }?.let { return it }
            doc.selectFirst("[data-post_id]")?.attr("data-post_id")?.takeIf { it.isNotBlank() }?.let { return it }
            doc.selectFirst("[data-postid]")?.attr("data-postid")?.takeIf { it.isNotBlank() }?.let { return it }
            doc.selectFirst("meta[name=post_id]")?.attr("content")?.takeIf { it.isNotBlank() }?.let { return it }
            val scriptsText = doc.select("script").joinToString(" ") { it.data() ?: "" }
            Regex("""post_id['"]?\s*[:=]\s*['"]?(\d{3,})['"]?""").find(scriptsText)?.groups?.get(1)?.value?.let { return it }
            Regex("""postid['"]?\s*[:=]\s*['"]?(\d{3,})['"]?""").find(scriptsText)?.groups?.get(1)?.value?.let { return it }
            return null
        }

        fun extractEpisodeNumberFromText(text: String?): String? {
            if (text.isNullOrBlank()) return null
            Regex("""الحلقة\s*(\d+)""").find(text)?.groups?.get(1)?.value?.let { return it }
            Regex("""\b(\d{1,3})\b""").find(text)?.groups?.get(1)?.value?.let { return it }
            return null
        }

        fun resolveUrl(base: String, relative: String): String {
            return try {
                val u = java.net.URL(java.net.URL(base), relative)
                u.toString()
            } catch (e: Exception) {
                if (relative.startsWith("http")) relative else mainUrl.trimEnd('/') + "/" + relative.trimStart('/')
            }
        }

        if (isSeriesPage) {
            val episodes = mutableListOf<Episode>()
            val postId = extractPostId(document)
            val ajaxUrl = "$mainUrl/wp-content/themes/mycima/Ajaxt/Single/Episodes.php"
            val headers = mapOf("Referer" to url)

            var seasonAnchors = document.select("div.SeasonsList ul li a")
            if (seasonAnchors.isEmpty()) {
                seasonAnchors = document.select(".Seasons--Episodes ul li a")
            }

            if (seasonAnchors.isEmpty()) {
                val globalAnchors = document.select("div.EpisodesList a[href], a.episode[href]")
                for (a in globalAnchors) {
                    val epTitleRaw = a.selectFirst(".episodetitle")?.text() ?: a.attr("title").ifBlank { a.text() }
                    val epNumText = extractEpisodeNumberFromText(epTitleRaw)
                    val epNum = epNumText?.toIntOrNull()
                    val newTitle = if (epNum != null) "الحلقة $epNum" else (epTitleRaw ?: "حلقة")
                    var epHref = a.attr("href").ifBlank { a.attr("data-href") }
                    if (epHref.isNullOrBlank()) continue
                    epHref = resolveUrl(url, epHref)
                    episodes.add(newEpisode(epHref) {
                        this.name = newTitle
                        this.season = null
                        this.episode = epNum
                        this.posterUrl = poster
                    })
                }

                val distinctEpisodes = episodes.distinctBy { it.data }
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, distinctEpisodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    this.recommendations = recommendations
                }
            }

            for ((seasonIndex, seasonEl) in seasonAnchors.withIndex()) {
                val rawSeasonText = seasonEl.text().trim()
                val seasonIdRaw = seasonEl.attr("data-season").ifBlank { seasonEl.attr("data-season-id") }
                val seasonHrefRaw = seasonEl.attr("href").ifBlank { seasonEl.attr("data-href") }

                val seasonNumFromText = extractNumbers(rawSeasonText)
                val seasonNumFromId = extractNumbers(seasonIdRaw)?.takeIf { seasonIdRaw.length <= 3 }
                val seasonNumber = seasonNumFromText ?: seasonNumFromId ?: (seasonIndex + 1)

                val seasonLabel = when {
                    rawSeasonText.isNotBlank() && !rawSeasonText.matches(Regex("^\\d{3,}\$")) -> {
                        if (rawSeasonText.matches(Regex("^\\d{1,3}\$"))) "الموسم $rawSeasonText" else rawSeasonText
                    }
                    else -> "الموسم $seasonNumber"
                }

                var gotEpisodesForThisSeason = false
                var localEpisodeCounter = 0

                // 1) AJAX (محمي الآن بفضل دالة httpPost)
                if (seasonIdRaw.isNotBlank() && !postId.isNullOrBlank()) {
                    try {
                        val postData = mapOf("season" to seasonIdRaw, "post_id" to postId)
                        val resp = try {
                            httpPost(ajaxUrl, data = postData, customHeaders = headers, timeout = 10_000L)
                        } catch (e: Exception) {
                            try { httpPost(ajaxUrl, data = postData) } catch (ex: Exception) { null }
                        }
                        
                        resp?.let {
                            val episodesHtml = it.document.body().html().ifBlank { it.document.html() }
                            val epDoc = org.jsoup.Jsoup.parse(episodesHtml)
                            val anchors = epDoc.select("a[href]").ifEmpty { epDoc.select("div.EpisodesList a[href]") }
                            if (anchors.isNotEmpty()) {
                                for (a in anchors) {
                                    val epTitleRaw = a.selectFirst(".episodetitle")?.text() ?: a.attr("title").ifBlank { a.text() }
                                    val epNumText = extractEpisodeNumberFromText(epTitleRaw)
                                    val epNum = epNumText?.toIntOrNull() ?: run {
                                        localEpisodeCounter += 1
                                        localEpisodeCounter
                                    }
                                    var epHref = a.attr("href").ifBlank { a.attr("data-href") }
                                    if (epHref.isNullOrBlank()) continue
                                    epHref = resolveUrl(url, epHref)
                                    episodes.add(newEpisode(epHref) {
                                        this.name = "$seasonLabel الحلقة $epNum"
                                        this.season = seasonNumber
                                        this.episode = epNum
                                        this.posterUrl = poster
                                    })
                                }
                                gotEpisodesForThisSeason = true
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (gotEpisodesForThisSeason) continue

                // 2) صفحة الموسم href
                if (!seasonHrefRaw.isNullOrBlank()) {
                    try {
                        val resolvedSeasonHref = resolveUrl(url, seasonHrefRaw)
                        val seasonResp = try { httpGet(resolvedSeasonHref, customHeaders = headers, timeout = 10_000L) } catch (_: Exception) { null }
                        seasonResp?.let { seasonDoc ->
                            val anchors = seasonDoc.select("div.EpisodesList a[href], a[href]").filter {
                                it.closest(".SeasonsList") == null
                            }
                            if (anchors.isNotEmpty()) {
                                for (a in anchors) {
                                    val epTitleRaw = a.selectFirst(".episodetitle")?.text() ?: a.attr("title").ifBlank { a.text() }
                                    val epNumText = extractEpisodeNumberFromText(epTitleRaw)
                                    val epNum = epNumText?.toIntOrNull() ?: run {
                                        localEpisodeCounter += 1
                                        localEpisodeCounter
                                    }
                                    var epHref = a.attr("href").ifBlank { a.attr("data-href") }
                                    if (epHref.isNullOrBlank()) continue
                                    epHref = resolveUrl(url, epHref)
                                    episodes.add(newEpisode(epHref) {
                                        this.name = "$seasonLabel الحلقة $epNum"
                                        this.season = seasonNumber
                                        this.episode = epNum
                                        this.posterUrl = poster
                                    })
                                }
                                gotEpisodesForThisSeason = true
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (gotEpisodesForThisSeason) continue

                // 3) fallback block في نفس الصفحة
                val fallbackBlocks = document.select("div.SeasonsList, .Seasons--Episodes")
                if (fallbackBlocks.isNotEmpty()) {
                    val matchingBlock = fallbackBlocks.getOrNull(seasonIndex) ?: fallbackBlocks.firstOrNull()
                    matchingBlock?.select("div.EpisodesList a[href], a[href]")?.let { anchors ->
                        if (anchors.isNotEmpty()) {
                            for (a in anchors) {
                                val epTitleRaw = a.selectFirst(".episodetitle")?.text() ?: a.attr("title").ifBlank { a.text() }
                                val epNumText = extractEpisodeNumberFromText(epTitleRaw)
                                val epNum = epNumText?.toIntOrNull() ?: run {
                                    localEpisodeCounter += 1
                                    localEpisodeCounter
                                }
                                var epHref = a.attr("href").ifBlank { a.attr("data-href") }
                                if (epHref.isNullOrBlank()) continue
                                epHref = resolveUrl(url, epHref)
                                episodes.add(newEpisode(epHref) {
                                    this.name = "$seasonLabel الحلقة $epNum"
                                    this.season = seasonNumber
                                    this.episode = epNum
                                    this.posterUrl = poster
                                })
                            }
                            gotEpisodesForThisSeason = true
                        }
                    }
                }
            }

            val distinctEpisodes = episodes.distinctBy { it.data }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, distinctEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        } else if (seriesUrlFromEpisode != null) {
            return load(seriesUrlFromEpisode)
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // الطلب الأساسي للصفحة (محمي بـ httpGet لمنع الكاش وتخطي Cloudflare)
        val document = try {
            httpGet(data)
        } catch (e: Exception) {
            return false
        }

        val linksToProcess = mutableListOf<Pair<String, String>>() 

        // إضافة روابط المشاهدة
        document.select("ul#watch li[data-watch]").forEach {
            val url = it.attr("data-watch")
            val name = extractServerName(it)
            if (url.isNotBlank()) linksToProcess.add(url to name)
        }

        // إضافة روابط التحميل
        document.select("ul.List--Download--Wecima--Single li a[href]").forEach {
            val url = it.attr("href")
            val name = it.selectFirst("quality")?.text()?.trim() ?: "تحميل"
            if (url.isNotBlank()) linksToProcess.add(url to name)
        }

        coroutineScope {
            linksToProcess.distinctBy { it.first }.map { (link, serverName) ->
                async {
                    val finalUrl = if (link.contains("govid.site")) {
                        try {
                            // محمية بـ httpGet لتخطي الحماية
                            val govidDoc = httpGet(link)
                            govidDoc.selectFirst("iframe")?.attr("src")
                        } catch (e: Exception) {
                            null
                        }
                    } else if (link.contains("mycima.page/go/")) {
                        try {
                            val base64Part = link.substringAfterLast('/')
                            base64Part.safeBase64Decode()
                        } catch (e: Exception) {
                            null
                        }
                    } else {
                        link
                    }

                    // هنا تم إزالة الجزء الذي كان يسبب الخطأ (ExternalEarnVidsExtractor)
                    if (!finalUrl.isNullOrBlank()) {
                        loadExtractor(finalUrl, data, subtitleCallback, callback)
                    }
                }
            }.awaitAll()
        }

        return true
    }
}
