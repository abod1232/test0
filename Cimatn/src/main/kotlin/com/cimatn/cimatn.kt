package com.cimawbas

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import android.util.Base64

class CimaTn : MainAPI() {
    override var mainUrl = "https://www.cimatn.com"
    override var name = "Cima Tn"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/search/label/أحدث الإضافات" to "أحدث الإضافات",
        "$mainUrl/search/label/أفلام تونسية" to "أفلام تونسية",
        "$mainUrl/search/label/مسلسلات تونسية" to "مسلسلات تونسية",
        "$mainUrl/search/label/رمضان2025" to "رمضان 2025",
        "$mainUrl/search/label/دراما" to "دراما",
        "$mainUrl/search/label/كوميديا" to "كوميديا",
        "$mainUrl/search/label/أكشن" to "أكشن"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}?max-results=20"
        val doc = app.get(url).document
        val home = doc.select("#holder a.itempost").mapNotNull { toSearchResult(it) }
        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val title = element.select("#item-name").text().trim()
        val url = element.attr("href")
        var posterUrl = element.select("img").attr("src")
        posterUrl = fixPoster(posterUrl)
        val year = element.select(".entry-label").text().trim().toIntOrNull()

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val doc = app.get(url).document
        return doc.select("#holder a.itempost").mapNotNull { toSearchResult(it) }
    }


override suspend fun load(url: String): LoadResponse {
    val cleanUrl = url.substringBefore("?")

    // ===============================
    // جلب الصفحة (CloudStream يتبع 302 تلقائياً)
    // ===============================
    val html = app.get(cleanUrl).text
    val doc = org.jsoup.Jsoup.parse(html)

    val title = doc.select("h1.PostTitle").text().trim().ifEmpty { "Series" }
    val description = doc.select(".StoryArea p").text().trim()
    val posterUrl = fixPoster(
        doc.select("#poster img").attr("src")
            .ifEmpty { doc.select(".image img").attr("src") }
    )

    val episodes = mutableListOf<Episode>()

    // =====================================================
    // 1️⃣ محاولة استخراج الحلقات من JavaScript (مثل بايثون)
    // =====================================================
    val countMatch = Regex("""const\s+totalEpisodes\s*=\s*(\d+)""").find(html)
    val baseMatch  = Regex("""const\s+baseLink\s*=\s*["']([^"']+)["']""").find(html)

    if (countMatch != null && baseMatch != null) {
        val count = countMatch.groupValues[1].toInt()
        val baseLink = baseMatch.groupValues[1]
        val domain = mainUrl

        for (i in 1..count) {
            val epUrl = when {
                baseLink.startsWith("http") -> "$baseLink$i.html"
                baseLink.startsWith("/") -> "$domain$baseLink$i.html"
                else -> "$domain/p/${baseLink.removePrefix("/")}$i.html"
            }

            episodes.add(
                newEpisode(epUrl) {
                    name = "الحلقة $i"
                    season = 1
                    episode = i
                }
            )
        }
    }

    // =====================================================
    // 2️⃣ fallback مثل بايثون → Feed Search
    // =====================================================
    if (episodes.isEmpty()) {
        val slug = cleanUrl
            .substringAfterLast("/")
            .substringBefore(".")
            .replace("_9", "")
            .trim()

        val feedUrl =
            "$mainUrl/feeds/pages/default?alt=json&max-results=500&q=$slug"

        try {
            val feedJson = app.get(feedUrl).text
            val feed = AppUtils.parseJson<BloggerFeed>(feedJson)

            feed.feed?.entry?.forEach { e ->
                val link = e.link?.find { it.rel == "alternate" }?.href ?: return@forEach
                val titleEp = e.title?.t ?: "Episode"

                if (link.contains(slug) &&
                    (link.contains("ep") || link.contains("hal9a"))
                ) {
                    val epNum = Regex("""(\d+)""")
                        .findAll(titleEp)
                        .lastOrNull()
                        ?.value
                        ?.toIntOrNull()

                    episodes.add(
                        newEpisode(link) {
                            name = titleEp
                            season = 1
                            episode = epNum
                        }
                    )
                }
            }
        } catch (_: Exception) { }
    }

    episodes.sortBy { it.episode ?: 0 }

    return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
        this.posterUrl = posterUrl
        this.plot = description
    }
}
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugLog("loadLinks started: $data")
        val doc = app.get(data).document
        val scriptContent = doc.select("script").joinToString(" ") { it.data() }
        var foundServer = false

        // 1. مصفوفة const servers
        val serverRegex = Regex("""const\s+servers\s*=\s*(\[\s*\{.*?\}\s*\])""", RegexOption.DOT_MATCHES_ALL)
        val match = serverRegex.find(scriptContent)

        if (match != null) {
            val jsonString = match.groupValues[1]
            val urlRegex = Regex("""url\s*:\s*['"](.*?)['"]""")
            urlRegex.findAll(jsonString).forEach { matchResult ->
                val serverUrl = matchResult.groupValues[1]
                debugLog("Found Server: $serverUrl")
                loadExtractor(serverUrl, data, subtitleCallback, callback)
                foundServer = true
            }
        }

        // 2. Iframe مباشر
        doc.select("div.WatchIframe iframe, iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("facebook") && !src.contains("instagram")) {
                loadExtractor(src, data, subtitleCallback, callback)
                foundServer = true
            }
        }
        
        // 3. زر المشاهدة المشفر
        val secureUrl = doc.select(".BTNSDownWatch a.watch").attr("data-secure-url")
        if (secureUrl.isNotEmpty() && secureUrl != "#") {
            try {
                val clean = secureUrl.substring(1, secureUrl.length - 1).reversed()
                val decodedUrl = String(Base64.decode(clean, Base64.DEFAULT))
                loadExtractor(decodedUrl, data, subtitleCallback, callback)
                foundServer = true
            } catch (e: Exception) { }
        }

        return foundServer
    }

    private fun printLargeLog(content: String) {
        if (content.length > 4000) {
            println("CimaTnDebug: HTML DUMP PART 1:")
            println(content.substring(0, 4000))
            printLargeLog(content.substring(4000))
        } else {
            println(content)
        }
    }

    private fun debugLog(msg: String) {
        println("CimaTnDebug: $msg")
    }

    private fun fixPoster(url: String): String {
        return url.replace(Regex("/s\\d+-c/"), "/w600/")
                  .replace(Regex("/w\\d+/"), "/w600/")
                  .replace(Regex("/s\\d+/"), "/s1600/")
    }

    private fun extractYear(doc: Element): Int? {
        return doc.select("ul.RightTaxContent li:contains(تاريخ اصدار)").text()
            .replace(Regex("[^0-9]"), "")
            .toIntOrNull()
    }

    data class BloggerFeed(@JsonProperty("feed") val feed: FeedData? = null)
    data class FeedData(@JsonProperty("entry") val entry: List<FeedEntry>? = null)
    data class FeedEntry(
        @JsonProperty("title") val title: FeedTitle? = null,
        @JsonProperty("link") val link: List<FeedLink>? = null,
        @JsonProperty("media\$thumbnail") val mediaThumbnail: FeedMedia? = null
    )
    data class FeedTitle(@JsonProperty("\$t") val t: String? = null)
    data class FeedLink(
        @JsonProperty("rel") val rel: String? = null,
        @JsonProperty("href") val href: String? = null
    )
    data class FeedMedia(@JsonProperty("url") val url: String? = null)
}
