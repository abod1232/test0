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

    // =========================================================================
    // دالة Load (المحسنة للمسلسلات والأفلام)
    // =========================================================================
    override suspend fun load(url: String): LoadResponse {
        debugLog("🔵 Load Function Started: $url")
        val cleanUrl = url.substringBefore("?")

        // -----------------------------------------------------------
        // 1. منطق الأفلام
        // -----------------------------------------------------------
        if (cleanUrl.contains("film-")) {
            debugLog("🎬 Type: MOVIE detected")
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
            } catch (e: Exception) { debugLog("Season parsing error: ${e.message}") }
        }

        // ب. البحث عن المواسم (HTML)
        if (seasonsList.isEmpty()) {
            doc.select(".allseasonss .Small--Box.Season a").forEach {
                val sTitle = it.attr("title").ifEmpty { "Season" }
                val sLink = it.attr("href")
                if (sLink.isNotEmpty()) seasonsList.add(sTitle to sLink)
            }
        }

        // ج. حالة موسم واحد
        if (seasonsList.isEmpty()) {
            seasonsList.add("الموسم 1" to cleanUrl)
        }

        val allEpisodes = mutableListOf<Episode>()

        // د. معالجة كل موسم
        seasonsList.forEachIndexed { index, (sTitle, sLink) ->
            val seasonNum = index + 1
            debugLog("🔄 Processing Season $seasonNum: $sTitle")
            
            val seasonHtml = if (sLink == cleanUrl) htmlContent else app.get(sLink).text
            
            // 1. محاولة مباشرة (JS & HTML)
            var eps = getEpisodesDirect(seasonHtml, sLink, seasonNum)
            
            // 2. إذا فشل الاستخراج المباشر، نستخدم البحث الاحتياطي (Fallback)
            // هذا الجزء هو الذي سيحل مشكلة Flash Back
            if (eps.isEmpty()) {
                debugLog("   -> No episodes found directly. Trying Feed Search...")
                // استخراج slug من الرابط (اسم المسلسل)
                val slug = sLink.substringAfterLast("/").substringBefore(".").replace("_9", "").replace("-s2", "").replace("-s1", "")
                eps = getEpisodesFromSearchFeed(slug, seasonNum)
            }

            if (eps.isNotEmpty()) {
                debugLog("✅ Found ${eps.size} episodes in Season $seasonNum")
                allEpisodes.addAll(eps)
            } else {
                debugLog("❌ FAILED to find episodes in Season $seasonNum")
                if (seasonsList.size == 1) printLargeLog(seasonHtml) // طباعة HTML للتحليل إذا فشل كل شيء
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
    // دالة استخراج الحلقات (المباشرة)
    // ========================================================
    private fun getEpisodesDirect(htmlContent: String, pageUrl: String, seasonNum: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // 1. JS Variables (Ragouj Style)
        val countMatch = Regex("""const\s+totalEpisodes\s*=\s*(\d+);""").find(htmlContent)
        val baseLinkMatch = Regex("""const\s+baseLink\s*=\s*['"]([^"']+)['"]""").find(htmlContent)

        if (countMatch != null && baseLinkMatch != null) {
            val count = countMatch.groupValues[1].toInt()
            val baseLink = baseLinkMatch.groupValues[1]
            val domain = "https://${java.net.URI(pageUrl).host}"

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
        }

        // 2. HTML Selectors
        val doc = org.jsoup.Jsoup.parse(htmlContent)
        val selectors = listOf(
            ".allepcont .row a",          
            ".EpisodesList a",            
            "#EpisodesList a",            
            ".episodes-container a",
            "div[class*='Episodes'] a",
            ".post-body a[href*='-ep-']",
            ".post-body a[href*='hal9a']"
        )

        for (selector in selectors) {
            val links = doc.select(selector)
            if (links.isNotEmpty()) {
                links.forEach { link ->
                    val epName = link.select("h2").text().trim().ifEmpty { link.text().trim() }.ifEmpty { "Episode" }
                    val epUrl = link.attr("href")
                    
                    val epNum = Regex("""(\d+)""").findAll(epName).lastOrNull()?.value?.toIntOrNull()

                    if (epUrl.isNotEmpty() && !epUrl.contains("#") && epUrl != pageUrl) {
                        episodes.add(newEpisode(epUrl) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                        })
                    }
                }
                if (episodes.isNotEmpty()) break
            }
        }
        
        return episodes
    }

    // ========================================================
    // دالة البحث الاحتياطي (Feed Search)
    // ========================================================
    private suspend fun getEpisodesFromSearchFeed(slugInput: String, defaultSeasonNum: Int): List<Episode> {
    suspend fun fetchFeedJson(url: String): String? {
        return try {
            var text = app.get(url).text
            // البعض يرد JSONP أو script-wrapped -> نحاول تنظيفه
            val jsonOnly = Regex("""^[^\{]*(""" + "\\{[\\s\\S]*\\}" + """)[^\}]*$""").find(text)?.groupValues?.get(1)
            if (jsonOnly != null) jsonOnly else text
        } catch (e: Exception) {
            debugLog("Feed fetch error for $url : ${e.message}")
            null
        }
    }

    // تحويل أرقام عربية-هندية الى لاتينية
    fun normalizeDigits(s: String): String {
        val map = mapOf(
            '٠' to '0','١' to '1','٢' to '2','٣' to '3','٤' to '4',
            '٥' to '5','٦' to '6','٧' to '7','٨' to '8','٩' to '9'
        )
        return s.map { map[it] ?: it }.joinToString("")
    }

    // يستخرج رقم الموسم و الحلقة من العنوان إن وجد
    fun parseSeasonEpisode(titleRaw: String): Pair<Int?, Int?> {
        val title = normalizeDigits(titleRaw)
        // أشهر الباترنات بالإنجليزية و العربية و SxxExx و Sxx Eyy و "الموسم X" و "الحلقة Y" و ep/E
        val patterns = listOf(
            // SxxExx or Sxx Eyy
            Regex("""[Ss](\d{1,2})\s*[^\dA-Za-z]{0,3}[Ee](\d{1,3})"""),
            Regex("""[Ss]eason[\s:\-]*?(\d{1,2}).*[Ee]p[\s:\-]*?(\d{1,3})""", RegexOption.IGNORE_CASE),
            Regex("""([Ee]p|[Ee])\s*\.?\s*(\d{1,3})""", RegexOption.IGNORE_CASE), // ep 12 or E12
            Regex("""الحلقة[\s:\-]*?(\d{1,3})"""),
            Regex("""الموسم[\s:\-]*?(\d{1,2})"""),
            Regex("""season[\s:\-]*?(\d{1,2})""", RegexOption.IGNORE_CASE),
            // generic last number fallback (useful when only episode number present)
            Regex("""(\d{1,3})""")
        )

        // Try combined patterns first (season+episode)
        val combined = listOf(
            Regex("""[Ss](\d{1,2})[^\dA-Za-z]{0,3}[Ee](\d{1,3})"""),
            Regex("""season[\s:\-]*?(\d{1,2}).*?[Ee]p[\s:\-]*?(\d{1,3})""", RegexOption.IGNORE_CASE),
            Regex("""الموسم[\s:\-]*?(\d{1,2}).*?الحلقة[\s:\-]*?(\d{1,3})""")
        )
        for (rg in combined) {
            val m = rg.find(title)
            if (m != null && m.groupValues.size >= 3) {
                val s = m.groupValues[1].toIntOrNull()
                val e = m.groupValues[2].toIntOrNull()
                if (s != null || e != null) return Pair(s, e)
            }
        }

        // فصل: ابحث عن الموسم ثم الحلقة، أو الحلقة ثم الموسم
        val seasonOnly = Regex("""(?:الموسم|season|[Ss])[\s:\-]*?(\d{1,2})""", RegexOption.IGNORE_CASE).find(title)
        val episodeOnly = Regex("""(?:الحلقة|ep|[Ee])[\s:\-]*?(\d{1,3})""", RegexOption.IGNORE_CASE).find(title)

        val seasonNum = seasonOnly?.groupValues?.get(1)?.toIntOrNull()
        val episodeNum = episodeOnly?.groupValues?.get(1)?.toIntOrNull()

        if (seasonNum != null || episodeNum != null) {
            return Pair(seasonNum, episodeNum)
        }

        // آخر محاولة: استخدم آخر رقم في العنوان كحلقة
        val lastNumber = Regex("""(\d{1,3})""").findAll(title).lastOrNull()?.value?.toIntOrNull()
        if (lastNumber != null) {
            // احتمالية أن يكون الرقم موسم أم حلقة: نفترض حلقة إن لم يذكر الموسم
            return Pair(null, lastNumber)
        }

        return Pair(null, null)
    }

    // إعداد candidate feed URLs ذكية
    val slug = slugInput.replace(".html", "").trim()
    val encoded = try { java.net.URLEncoder.encode(slug, "UTF-8") } catch (_: Exception) { slug }
    val candidates = listOf(
        "$mainUrl/feeds/pages/default?alt=json&max-results=500&q=$encoded",
        "$mainUrl/feeds/pages/default?alt=json-in-script&max-results=500&q=$encoded",
        "$mainUrl/feeds/posts/default?alt=json&max-results=500&q=$encoded",
        "$mainUrl/feeds/posts/default?alt=json-in-script&max-results=500&q=$encoded",
        // generic site-wide feeds (قد تكون مفيدة)
        "$mainUrl/feeds/pages/default?alt=json&max-results=500",
        "$mainUrl/feeds/posts/default?alt=json&max-results=500"
    )

    val episodes = mutableListOf<Episode>()
    val seenUrls = mutableSetOf<String>()

    for (feedUrl in candidates) {
        val feedJson = fetchFeedJson(feedUrl) ?: continue

        try {
            val feedData = AppUtils.parseJson<BloggerFeed>(feedJson)
            val entries = feedData?.feed?.entry ?: emptyList()
            debugLog("Feed ${feedUrl} returned ${entries.size} entries")

            entries.forEach { e ->
                val link = e.link?.find { it.rel == "alternate" }?.href ?: return@forEach
                var title = e.title?.t ?: ""
                if (title.isEmpty()) {
                    // أحيانًا العنوان داخل media thumbnail أو content -> نحاول استدعاء mediaThumbnail
                    title = e.mediaThumbnail?.url ?: ""
                }

                // تنظيف عنوان و رابط
                val cleanLink = link.split("?")[0]
                if (cleanLink.isEmpty() || seenUrls.contains(cleanLink)) return@forEach

                val (parsedSeason, parsedEpisode) = parseSeasonEpisode(title)
                val season = parsedSeason ?: defaultSeasonNum
                val episodeNumber = parsedEpisode // could be null

                // شرط إضافي: تأكد من أن الرابط يبدو كحلقة (ep, hal9a, رقم في العنوان أو slug يحتوي ep)
                val looksLikeEpisode = listOf("ep", "hal9a", "episode", "حلقة").any { keyword ->
                    cleanLink.contains(keyword, ignoreCase = true) || title.contains(keyword, ignoreCase = true)
                } || episodeNumber != null

                if (!looksLikeEpisode) {
                    // لا نرفضه كليًا — أحيانًا العنوان يحوي رقم فقط؛ لكن لا نضيف إلا لو فيه دليل
                    // لو لم نكن نملك حلقة أخرى، يمكننا إضافته لاحقًا (هنا نتخطاه)
                    return@forEach
                }

                // إنشاء Episode
                val ep = newEpisode(cleanLink) {
                    this.name = title
                    this.season = season
                    this.episode = episodeNumber
                }

                episodes.add(ep)
                seenUrls.add(cleanLink)
            }

            // إن وجدنا عدد جيد من الحلقات - نوقف البحث (تحسين الأداء)
            if (episodes.size >= 5) {
                debugLog("Enough episodes found (${episodes.size}), stopping feed search.")
                break
            }
        } catch (ex: Exception) {
            debugLog("Feed parse error for $feedUrl : ${ex.message}")
        }
    }

    // في حال لم نجد شيء من الـ feeds: حاول استخدام آخر محرك بحث بسيط بالـ slug في pages feed مرة واحدة أكثر تساهلًا
    if (episodes.isEmpty()) {
        try {
            val fallback = "$mainUrl/feeds/pages/default?alt=json&max-results=500&q=${encoded.replace('-', ' ')}"
            val feedJson = fetchFeedJson(fallback)
            val feedData = feedJson?.let { AppUtils.parseJson<BloggerFeed>(it) }
            feedData?.feed?.entry?.forEach { e ->
                val link = e.link?.find { it.rel == "alternate" }?.href ?: return@forEach
                val title = e.title?.t ?: ""
                val cleanLink = link.split("?")[0]
                if (seenUrls.contains(cleanLink)) return@forEach
                val (s, epn) = parseSeasonEpisode(title)
                val season = s ?: defaultSeasonNum
                val episodeNumber = epn
                if (epn == null && !title.matches(Regex(""".*\d.*"""))) return@forEach
                val ep = newEpisode(cleanLink) {
                    this.name = title
                    this.season = season
                    this.episode = episodeNumber
                }
                episodes.add(ep)
                seenUrls.add(cleanLink)
            }
        } catch (_: Exception) { /* ignore */ }
    }

    // ترتيب و تنظيف النتائج:
    val sorted = episodes
        .distinctBy { it.url } // حذر مزدوج
        .sortedWith(compareBy<Episode> { it.season ?: Int.MAX_VALUE }
            .thenBy { it.episode ?: Int.MAX_VALUE }
            .thenBy { it.name ?: "" })

    debugLog("getEpisodesFromSearchFeed: returning ${sorted.size} episodes")
    return sorted
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
