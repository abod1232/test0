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
    debugLog("🔵 Load started: $url")
    val cleanUrl = url.substringBefore("?").trim()
    val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

    // سريع للفيلم
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

    debugLog("📺 Detected SERIES: $cleanUrl")

    // تهيئة headers
    val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // دالة مساعدة داخلية (محاولة GET مرّتين: الأصلية ثم variations)
    suspend fun fetchWithRedirectHandling(original: String): Pair<com.lagradost.cloudstream3.AppResponse?, String?> {
        try {
            // حاول جلب الرابط الأصلي مع هيدرز (إن كان app.get يقبل headers)
            val resp = try {
                app.get(original, headers)
            } catch (_: Exception) {
                // إن لم يقبل overload، جرب بدون headers
                app.get(original)
            }

            // حاوِل تحديد final URL إن أمكن
            val finalUrl = try {
                // قد يكون resp.request.url أو resp.url حسب implementation
                resp.request?.url?.toString() ?: resp.url?.toString()
            } catch (_: Exception) {
                null
            }

            debugLog("Initial fetch done. Status/URL maybe: ${finalUrl ?: "unknown"}")

            // إذا الرد كان redirect أو finalUrl مختلف عن الأصلي، حاول جلب finalUrl صراحةً
            if (finalUrl != null && !finalUrl.equals(original, ignoreCase = true)) {
                debugLog("Redirect detected -> fetching final URL: $finalUrl")
                val resp2 = try {
                    app.get(finalUrl, headers)
                } catch (_: Exception) {
                    app.get(finalUrl)
                }
                return Pair(resp2, finalUrl)
            }

            // لا redirect واضح — نعيد الاستجابة الأولى
            return Pair(resp, finalUrl ?: original)
        } catch (e: Exception) {
            debugLog("fetchWithRedirectHandling error: ${e.message}")
            return Pair(null, null)
        }
    }

    // 1) محاولة رئيسية
    var (response, finalUrl) = fetchWithRedirectHandling(cleanUrl)
    var htmlContent = response?.text ?: ""
    var doc = response?.document

    // 2) إن كانت الصفحة فاضية أو لا تحتوي شيء مفيد، جرب بعض variations شائعة
    if ((htmlContent.isEmpty() || htmlContent.length < 50) && finalUrl != null) {
        val tryUrls = listOf(
            // trailing slash
            if (!cleanUrl.endsWith("/")) cleanUrl + "/" else cleanUrl,
            // بدون www
            cleanUrl.replace("://www.", "://"),
            // مع www (لو كانت بدونها)
            if (!cleanUrl.contains("://www.")) cleanUrl.replace("://", "://www.") else cleanUrl
        ).distinct()

        for (u in tryUrls) {
            if (u.equals(finalUrl, ignoreCase = true)) continue
            debugLog("Attempting alternative fetch: $u")
            val (r2, f2) = fetchWithRedirectHandling(u)
            if (r2 != null) {
                response = r2
                finalUrl = f2
                htmlContent = response.text
                doc = response.document
                if (!htmlContent.isNullOrEmpty() && htmlContent.length > 50) break
            }
        }
    }

    debugLog("Final fetch URL: ${finalUrl ?: "unknown"}, content length=${htmlContent.length}")

    // استخراج بيانات المسلسل العامة
    val title = doc?.select("h1.PostTitle")?.text()?.trim() ?: "مسلسل"
    val description = doc?.select(".StoryArea p")?.text()?.trim() ?: ""
    var posterUrl = doc?.select("#poster img")?.attr("src") ?: ""
    if (posterUrl.isEmpty()) posterUrl = doc?.select(".image img")?.attr("src") ?: ""
    posterUrl = fixPoster(posterUrl)
    val year = doc?.let { extractYear(it) }
    val tags = doc?.select("ul.RightTaxContent li a")?.map { it.text() } ?: emptyList()

    val uri = try { java.net.URI(finalUrl ?: cleanUrl) } catch (_: Exception) { null }
    val domain = if (uri != null) "${uri.scheme}://${uri.host}" else mainUrl

    val episodes = mutableListOf<Episode>()

    // ---------------------------
    // A: استخراج من JS (totalEpisodes + baseLink)
    // ---------------------------
    try {
        val countRegex = Regex("""(?i)(?:const|var|let)?\s*(?:totalEpisodes|totalEp|episodesCount|total)\s*[:=]\s*(\d{1,4})""")
        val baseRegex = Regex("""(?i)(?:const|var|let)?\s*(?:baseLink|linkBase|base_link|baseURL|baseUrl|base)\s*[:=]\s*['"]([^'"]+)['"]""")

        val countMatch = countRegex.find(htmlContent)
        val baseMatch = baseRegex.find(htmlContent)

        if (countMatch != null && baseMatch != null) {
            val count = countMatch.groupValues[1].toIntOrNull() ?: 0
            val base = baseMatch.groupValues[1]
            debugLog("JS blueprint found -> total=$count, base=$base")

            for (i in 1..(if (count <= 0) 0 else count)) {
                val fullLink = when {
                    base.startsWith("http", ignoreCase = true) -> {
                        if (base.contains("%d")) base.replace("%d", i.toString())
                        else if (base.endsWith(".html")) "${base.removeSuffix(".html")}$i.html"
                        else "$base$i.html"
                    }
                    base.startsWith("/") -> "$domain${base.trimEnd('/')}/$i.html"
                    else -> "$domain/p/${base.trimStart('/')}$i.html"
                }
                episodes.add(newEpisode(fullLink) {
                    this.name = "الحلقة $i"
                    this.season = 1
                    this.episode = i
                })
            }
        } else {
            debugLog("No JS pattern found or incomplete")
        }
    } catch (ex: Exception) {
        debugLog("JS parse error: ${ex.message}")
    }

    // ---------------------------
    // B: استخراج من HTML selectors
    // ---------------------------
    if (episodes.isEmpty() && doc != null) {
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

        loop@ for (sel in selectors) {
            val links = doc.select(sel)
            if (links.isNotEmpty()) {
                links.forEach { link ->
                    val epName = link.select("h2").text().trim().ifEmpty { link.text().trim() }.ifEmpty { "Episode" }
                    val epUrl = link.attr("href").substringBefore("?")
                    if (epUrl.isNotEmpty() && epUrl != finalUrl && !epUrl.contains("#")) {
                        val epNum = Regex("""(\d{1,3})""").findAll(epName).lastOrNull()?.value?.toIntOrNull()
                        episodes.add(newEpisode(epUrl) {
                            this.name = epName
                            this.season = 1
                            this.episode = epNum
                        })
                    }
                }
                break@loop
            }
        }
    }

    // ---------------------------
    // C: Fallback - pages feed (مثل بايثون)
    // ---------------------------
    if (episodes.isEmpty()) {
        try {
            val slug = (finalUrl ?: cleanUrl).substringAfterLast("/").substringBefore(".").replace("_9", "").trim()
            val encoded = try { java.net.URLEncoder.encode(slug, "UTF-8") } catch (_: Exception) { slug }
            val feedUrl = "$mainUrl/feeds/pages/default?alt=json&max-results=500&q=$encoded"
            debugLog("Feed fallback -> $feedUrl")

            val feedResp = try { app.get(feedUrl, headers) } catch (_: Exception) { app.get(feedUrl) }
            val feedJson = feedResp.text
            val feedData = AppUtils.parseJson<BloggerFeed>(feedJson)
            feedData.feed?.entry?.forEach { entry ->
                val l = entry.link?.find { it.rel == "alternate" }?.href ?: return@forEach
                val t = entry.title?.t ?: ""
                val cleanLink = l.substringBefore("?")
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
            debugLog("Feed error: ${ex.message}")
        }
    }

    // تنظيف و فرز
    val finalEpisodes = episodes
        .distinctBy { it.data.substringBefore("?") }
        .sortedWith(
            compareBy<Episode> { it.season ?: Int.MAX_VALUE }
                .thenBy { it.episode ?: Int.MAX_VALUE }
                .thenBy { it.name ?: "" }
        )

    debugLog("Load finished. Found ${finalEpisodes.size} episodes for $cleanUrl (finalUrl=${finalUrl ?: "unknown"})")

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
