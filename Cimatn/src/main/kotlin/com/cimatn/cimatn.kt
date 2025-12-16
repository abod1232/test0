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
    debugLog("🔵 Load Function Started: $url")
    val cleanUrl = url.substringBefore("?")

    // ----- تعامل مع الأفلام بسرعة -----
    if (cleanUrl.contains("film-")) {
        debugLog("🎬 Detected MOVIE")
        val watchUrl = cleanUrl.replace("www.cimatn.com", "cimatunisa.blogspot.com")
        val doc = app.get(cleanUrl).document
        val title = doc.select("h1.PostTitle").text().trim()
        val description = doc.select(".StoryArea p").text().trim()
        var posterUrl = doc.select("#poster img").attr("src").ifEmpty { doc.select(".image img").attr("src") }
        posterUrl = fixPoster(posterUrl)
        val year = extractYear(doc)
        val tags = doc.select("ul.RightTaxContent li a").map { it.text() }

        return newMovieLoadResponse(title, watchUrl, TvType.Movie, watchUrl) {
            this.posterUrl = posterUrl
            this.year = year
            this.plot = description
            this.tags = tags
        }
    }

    // ----- ابدأ معالجة المسلسلات -----
    debugLog("📺 Detected SERIES: $cleanUrl")
    val response = app.get(cleanUrl)
    val htmlContent = response.text
    val doc = response.document

    val title = doc.select("h1.PostTitle").text().trim()
    val description = doc.select(".StoryArea p").text().trim()
    var posterUrl = fixPoster(doc.select("#poster img").attr("src"))
    if (posterUrl.isEmpty()) posterUrl = fixPoster(doc.select(".image img").attr("src"))
    val year = extractYear(doc)
    val tags = doc.select("ul.RightTaxContent li a").map { it.text() }

    val episodes = mutableListOf<Episode>()
    val uri = try { java.net.URI(cleanUrl) } catch (e: Exception) { null }
    val domain = if (uri != null) "${uri.scheme}://${uri.host}" else mainUrl

    // ----- 1) حاول استخراج متغيرات JS (totalEpisodes + baseLink) -----
    try {
        // متغيرات ممكنة لعدد الحلقات
        val countRegex = Regex("""(?i)(?:const|var|let)?\s*(?:totalEpisodes|totalEp|episodesCount|total)\s*[:=]\s*(\d{1,4})""")
        // متغيرات ممكنة لقاعدة الرابط
        val baseRegex = Regex("""(?i)(?:const|var|let)?\s*(?:baseLink|linkBase|base_link|baseURL|baseUrl|base)\s*[:=]\s*['"]([^'"]+)['"]""")

        val countMatch = countRegex.find(htmlContent)
        val baseMatch = baseRegex.find(htmlContent)

        if (countMatch != null && baseMatch != null) {
            val count = countMatch.groupValues[1].toIntOrNull() ?: 0
            val base = baseMatch.groupValues[1]

            debugLog("JS pattern found: total=$count, base=$base")

            for (i in 1..(if (count <= 0) 0 else count)) {
                val fullLink = when {
                    base.startsWith("http", ignoreCase = true) -> {
                        // إذا كانت الـ base كاملة نلصق رقمًا (.html إن لزم)
                        if (base.contains("%d")) base.replace("%d", i.toString())
                        else if (base.endsWith(".html")) {
                            // حاول استبدال آخر رقم إن وجد، وإلا ألحق الرقم قبل .html
                            val replaced = base.replace(Regex("(\\d+)(?=\\.html\$)")) { it.value } // no-op safe
                            if (replaced == base) "${base.removeSuffix(".html")}$i.html" else replaced
                        } else "$base$i.html"
                    }
                    base.startsWith("/") -> "$domain$base$i.html"
                    else -> "$domain/p/${base.trimStart('/')}$i.html"
                }

                episodes.add(newEpisode(fullLink) {
                    this.name = "الحلقة $i"
                    this.season = 1
                    this.episode = i
                })
            }
        } else {
            debugLog("No JS episode pattern found")
        }
    } catch (ex: Exception) {
        debugLog("JS parse error: ${ex.message}")
    }

    // ----- 2) محاولة استخراج من HTML selectors (إن لم يُعطِ JS أي شيء) -----
    if (episodes.isEmpty()) {
        val selectors = listOf(
            ".allepcont .row a",
            ".EpisodesList a",
            "#EpisodesList a",
            ".episodes-container a",
            "div[class*='Episodes'] a",
            ".post-body a[href*='-ep-']",
            ".post-body a[href*='hal9a']",
            ".post-body a[href*='ep']"
        )

        for (sel in selectors) {
            val links = doc.select(sel)
            if (links.isNotEmpty()) {
                links.forEach { link ->
                    val epName = link.select("h2").text().trim().ifEmpty { link.text().trim() }.ifEmpty { "Episode" }
                    val epUrl = link.attr("href").substringBefore("?")
                    if (epUrl.isNotEmpty() && epUrl != cleanUrl && !epUrl.contains("#")) {
                        // حاول استخراج رقم الحلقة من الاسم
                        val epNum = Regex("""(\d{1,3})""").findAll(epName).lastOrNull()?.value?.toIntOrNull()
                        episodes.add(newEpisode(epUrl) {
                            this.name = epName
                            this.season = 1
                            this.episode = epNum
                        })
                    }
                }
                if (episodes.isNotEmpty()) break
            }
        }
    }

    // ----- 3) Fallback: البحث في pages feed (مثل سكربت البايثون) -----
    if (episodes.isEmpty()) {
        try {
            val slug = cleanUrl.substringAfterLast("/").substringBefore(".").replace("_9", "").trim()
            val encoded = try { java.net.URLEncoder.encode(slug, "UTF-8") } catch (_: Exception) { slug }
            val feedUrl = "$mainUrl/feeds/pages/default?alt=json&max-results=500&q=$encoded"
            debugLog("Trying feed fallback: $feedUrl")
            val feedJson = app.get(feedUrl).text
            val feedData = AppUtils.parseJson<BloggerFeed>(feedJson)
            feedData.feed?.entry?.forEach { entry ->
                val l = entry.link?.find { it.rel == "alternate" }?.href ?: return@forEach
                val t = entry.title?.t ?: ""
                val cleanLink = l.substringBefore("?")
                // شرط بسيط: الرابط أو العنوان يحوي كلمة ep/hal9a/حلقة أو رقم
                val looksLike = listOf("ep", "hal9a", "episode", "حلقة").any { k ->
                    cleanLink.contains(k, ignoreCase = true) || t.contains(k, ignoreCase = true)
                } || Regex("""\d{1,3}""").containsMatchIn(t)

                if (!looksLike) return@forEach

                val epNum = Regex("""(\d{1,3})""").findAll(t).lastOrNull()?.value?.toIntOrNull()
                episodes.add(newEpisode(cleanLink) {
                    this.name = t.ifEmpty { "Episode" }
                    this.season = 1
                    this.episode = epNum
                })
            }
        } catch (ex: Exception) {
            debugLog("Feed fallback error: ${ex.message}")
        }
    }

    // ----- تنظيف، إزالة تكرارات، وترتيب -----
    val finalEpisodes = episodes
        .distinctBy { it.data.substringBefore("?") }
        .sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
                .thenBy { it.name ?: "" }
        )

    debugLog("Load produced ${finalEpisodes.size} episodes for $cleanUrl")

    return newTvSeriesLoadResponse(title.ifEmpty { "مسلسل" }, url, TvType.TvSeries, finalEpisodes) {
        this.posterUrl = posterUrl
        this.year = year
        this.plot = description
        this.tags = tags
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
