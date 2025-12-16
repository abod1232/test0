package com.cimatn

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
        val url = if (page == 1) {
            request.data
        } else {
            "${request.data}?max-results=20"
        }

        val doc = app.get(url).document
        val home = doc.select("#holder a.itempost").mapNotNull { toSearchResult(it) }
        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val title = element.select("#item-name").text().trim()
        val url = element.attr("href")
        var posterUrl = element.select("img").attr("src")
        
        posterUrl = posterUrl.replace(Regex("/s\\d+-c/"), "/w600/")
                             .replace(Regex("/w\\d+/"), "/w600/")
                             .replace(Regex("/s\\d+/"), "/s1600/")

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

    
  
// =========================================================================
    // دالة Load مع سجلات تتبع (Logging) وطباعة HTML عند الفشل
    // =========================================================================
    override suspend fun load(url: String): LoadResponse {
        debugLog("🔵 Load Function Started: $url")
        val cleanUrl = url.substringBefore("?")

        // -----------------------------------------------------------
        // 1. منطق الأفلام
        // -----------------------------------------------------------
        if (cleanUrl.contains("film-")) {
            debugLog("🎬 Type: MOVIE detected")
            
            // استبدال الدومين
            val watchUrl = cleanUrl.replace("www.cimatn.com", "cimatunisa.blogspot.com")
            debugLog("✅ Redirecting to: $watchUrl")

            val doc = app.get(cleanUrl).document
            val title = doc.select("h1.PostTitle").text().trim()
            val description = doc.select(".StoryArea p").text().trim()
            var posterUrl = doc.select("#poster img").attr("src")
            if (posterUrl.isEmpty()) posterUrl = doc.select(".image img").attr("src")
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

        // -----------------------------------------------------------
        // 2. منطق المسلسلات
        // -----------------------------------------------------------
        debugLog("📺 Type: SERIES detected")
        
        // جلب الصفحة الرئيسية للمسلسل
        val response = app.get(cleanUrl)
        val htmlContent = response.text
        val doc = response.document

        val title = doc.select("h1.PostTitle").text().trim()
        val description = doc.select(".StoryArea p").text().trim()
        var posterUrl = fixPoster(doc.select("#poster img").attr("src"))
        if (posterUrl.isEmpty()) posterUrl = fixPoster(doc.select(".image img").attr("src"))
        val year = extractYear(doc)
        val tags = doc.select("ul.RightTaxContent li a").map { it.text() }

        val seasonsList = mutableListOf<Pair<String, String>>()

        // أ. البحث عن المواسم (JS Feed)
        val feedMatch = Regex("""const\s+feedURL\s*=\s*['"]([^"']+)['"]""").find(htmlContent)
        if (feedMatch != null) {
            val feedUrlSuffix = feedMatch.groupValues[1]
            val feedUrl = if (feedUrlSuffix.startsWith("http")) feedUrlSuffix else "$mainUrl$feedUrlSuffix"
            val cleanFeedUrl = feedUrl.replace("?alt=json-in-script", "?alt=json&max-results=500")
            debugLog("🔎 Found Season JS Feed: $cleanFeedUrl")

            try {
                val feedJson = app.get(cleanFeedUrl).text
                val feedData = AppUtils.parseJson<BloggerFeed>(feedJson)
                feedData.feed?.entry?.forEach { entry ->
                    val sTitle = entry.title?.t ?: "Season"
                    val sLink = entry.link?.find { it.rel == "alternate" }?.href
                    if (sLink != null) {
                        seasonsList.add(sTitle to sLink)
                    }
                }
                debugLog("✅ Parsed ${seasonsList.size} seasons from JSON")
            } catch (e: Exception) { 
                debugLog("❌ Error parsing seasons: ${e.message}")
            }
        }

        // ب. البحث عن المواسم (HTML)
        if (seasonsList.isEmpty()) {
            doc.select(".allseasonss .Small--Box.Season a").forEach {
                val sTitle = it.attr("title").ifEmpty { "Season" }
                val sLink = it.attr("href")
                if (sLink.isNotEmpty()) seasonsList.add(sTitle to sLink)
            }
            if (seasonsList.isNotEmpty()) debugLog("✅ Found ${seasonsList.size} seasons from HTML")
        }

        // ج. حالة موسم واحد
        if (seasonsList.isEmpty()) {
            debugLog("📂 No seasons found. Using current page as Season 1")
            seasonsList.add("الموسم 1" to cleanUrl)
        }

        val allEpisodes = mutableListOf<Episode>()

        // د. معالجة كل موسم
        seasonsList.forEachIndexed { index, (sTitle, sLink) ->
            val seasonNum = index + 1
            debugLog("--------------------------------------------------")
            debugLog("🔄 Processing Season $seasonNum: $sTitle")
            debugLog("🔗 Link: $sLink")
            
            // جلب محتوى صفحة الموسم
            val seasonHtml = if (sLink == cleanUrl) htmlContent else app.get(sLink).text
            
            // محاولة استخراج الحلقات
            var eps = getEpisodesDirect(seasonHtml, sLink, seasonNum)
            
            if (eps.isNotEmpty()) {
                debugLog("✅ Successfully found ${eps.size} episodes in Season $seasonNum")
                allEpisodes.addAll(eps)
            } else {
                debugLog("❌ FAILED to find episodes in Season $seasonNum")
                
                // ========================================================
                // طباعة محتوى الصفحة عند الفشل لتحليل المشكلة
                // ========================================================
                debugLog("⚠️ DUMPING HTML CONTENT FOR ANALYSIS:")
                printLargeLog(seasonHtml)
                debugLog("⚠️ END OF HTML DUMP")
                
                // محاولة أخيرة بالبحث (Fallback)
                if (seasonsList.size == 1) { // فقط للموسم الواحد لتجنب التكرار
                    debugLog("Trying Feed Search Fallback...")
                    val slug = sLink.substringAfterLast("/").substringBefore(".").replace("_9", "")
                    eps = getEpisodesFromSearchFeed(slug, seasonNum)
                    if (eps.isNotEmpty()) {
                        debugLog("✅ Fallback found ${eps.size} episodes")
                        allEpisodes.addAll(eps)
                    }
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
            this.posterUrl = posterUrl
            this.year = year
            this.plot = description
            this.tags = tags
        }
    }

    // ========================================================
    // دالة استخراج الحلقات
    // ========================================================
    private fun getEpisodesDirect(htmlContent: String, pageUrl: String, seasonNum: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // 1. فحص متغيرات JS
        val countMatch = Regex("""const\s+totalEpisodes\s*=\s*(\d+);""").find(htmlContent)
        val baseLinkMatch = Regex("""const\s+baseLink\s*=\s*['"]([^"']+)['"]""").find(htmlContent)

        if (countMatch != null && baseLinkMatch != null) {
            val count = countMatch.groupValues[1].toInt()
            val baseLink = baseLinkMatch.groupValues[1]
            val domain = "https://${java.net.URI(pageUrl).host}"
            
            debugLog("   -> Found JS Config: Count=$count, Base=$baseLink")

            for (i in 1..count) {
                val fullLink = when {
                    baseLink.startsWith("http") -> "$baseLink$i.html"
                    baseLink.startsWith("/") -> "$domain$baseLink$i.html"
                    else -> "$domain/p/${baseLink.removePrefix("/")}$i.html"
                }
                
                episodes.add(newEpisode(fullLink) {
                    this.name = "الحلقة $i"
                    this.season = seasonNum
                    this.episode = i
                })
            }
            return episodes
        } else {
            debugLog("   -> No JS config found (totalEpisodes/baseLink)")
        }

        // 2. فحص روابط HTML
        val doc = org.jsoup.Jsoup.parse(htmlContent)
        
        // قائمة بالمحددات المحتملة (CSS Selectors)
        val selectors = listOf(
            ".allepcont .row a",          // التصميم الجديد
            ".EpisodesList a",            // التصميم القديم (قائمة جانبية)
            "#EpisodesList a",            // احتمال ID
            ".episodes-container a",      // احتمال
            "div[class*='Episodes'] a",   // بحث عام عن كلاس يحتوي Episodes
            ".post-body a[href*='-ep-']", // بحث داخل المقال عن روابط حلقات
            ".post-body a[href*='hal9a']" // بحث عن "حلقة"
        )

        for (selector in selectors) {
            val links = doc.select(selector)
            if (links.isNotEmpty()) {
                debugLog("   -> Found ${links.size} potential links using selector: '$selector'")
                
                links.forEach { link ->
                    val epName = link.select("h2").text().trim()
                        .ifEmpty { link.text().trim() }
                        .ifEmpty { "Episode" }
                    val epUrl = link.attr("href")
                    
                    // استخراج رقم الحلقة
                    val epNum = Regex("""(\d+)""").findAll(epName).lastOrNull()?.value?.toIntOrNull()

                    // شروط القبول: الرابط غير فارغ، ليس الصفحة الحالية، ليس رابط هاش
                    if (epUrl.isNotEmpty() && epUrl != pageUrl && !epUrl.contains("#")) {
                         // شرط إضافي: التأكد أنه رابط تدوينة (ينتهي بـ .html)
                         if (epUrl.contains(".html")) {
                             episodes.add(newEpisode(epUrl) {
                                 this.name = epName
                                 this.season = seasonNum
                                 this.episode = epNum
                             })
                         }
                    }
                }
                
                if (episodes.isNotEmpty()) break // وجدنا حلقات، نتوقف عن تجربة المحددات الأخرى
            }
        }
        
        return episodes
    }

    private suspend fun getEpisodesFromSearchFeed(slug: String, seasonNum: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val pageFeedUrl = "$mainUrl/feeds/pages/default?alt=json&max-results=100&q=$slug"
        
        try {
            val feedJson = app.get(pageFeedUrl).text
            val feedData = AppUtils.parseJson<BloggerFeed>(feedJson)
            feedData.feed?.entry?.forEach { e ->
                val l = e.link?.find { it.rel == "alternate" }?.href ?: ""
                val t = e.title?.t ?: "Episode"
                
                if (l.contains(slug) && (l.contains("ep") || l.contains("hal9a"))) {
                     val epNum = Regex("""(\d+)""").findAll(t).lastOrNull()?.value?.toIntOrNull()
                     
                     episodes.add(newEpisode(l) {
                         this.name = t
                         this.season = seasonNum
                         this.episode = epNum
                     })
                }
            }
            episodes.sortBy { it.episode }
        } catch (e: Exception) { }
        return episodes
    }

    // ========================================================
    // دالة لطباعة النصوص الطويلة في Logcat
    // ========================================================
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
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugLog("loadLinks started for: $data")
        val doc = app.get(data).document
        val scriptContent = doc.select("script").joinToString(" ") { it.data() }

        var foundServer = false

        // 1. مصفوفة السيرفرات const servers
        val serverRegex = Regex("""const\s+servers\s*=\s*(\[\s*\{.*?\}\s*\])""", RegexOption.DOT_MATCHES_ALL)
        val match = serverRegex.find(scriptContent)

        if (match != null) {
            val jsonString = match.groupValues[1]
            val urlRegex = Regex("""url\s*:\s*['"](.*?)['"]""")
            urlRegex.findAll(jsonString).forEach { matchResult ->
                val serverUrl = matchResult.groupValues[1]
                debugLog("Found Server (JS Array): $serverUrl")
                loadExtractor(serverUrl, data, subtitleCallback, callback)
                foundServer = true
            }
        }

        // 2. Iframe مباشر
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotEmpty() && !src.contains("facebook") && !src.contains("instagram") && !src.contains("googletagmanager")) {
                debugLog("Found Iframe: $src")
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
                debugLog("Decoded Secure Link: $decodedUrl")
                loadExtractor(decodedUrl, data, subtitleCallback, callback)
                foundServer = true
            } catch (e: Exception) { 
                debugLog("Failed to decode secure link: ${e.message}")
            }
        }

        if (!foundServer) {
            debugLog("No servers found on this page!")
        }

        return foundServer
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
