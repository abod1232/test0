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
    // دالة Load المطابقة لكود البايثون 100%
    // =========================================================================
    override suspend fun load(url: String): LoadResponse {
        debugLog("Load Function Started: $url")
        val cleanUrl = url.substringBefore("?")

        // ========================================================
        // 1. منطق الأفلام (Python: if "film-" in url)
        // ========================================================
        if (cleanUrl.contains("film-")) {
            debugLog("🎬 Category: Movie")
            // استبدال الدومين للوصول للمصدر المباشر
            val newUrl = cleanUrl.replace("www.cimatn.com", "cimatunisa.blogspot.com")
            debugLog("✅ Direct Source Link: $newUrl")

            // نجلب البيانات للعرض فقط، لكن الرابط المهم هو newUrl
            val doc = app.get(cleanUrl).document
            val title = doc.select("h1.PostTitle").text().trim()
            val desc = doc.select(".StoryArea p").text().trim()
            val poster = fixPoster(doc.select("#poster img").attr("src"))
            val year = extractYear(doc)
            val tags = doc.select("ul.RightTaxContent li a").map { it.text() }

            return newMovieLoadResponse(title, newUrl, TvType.Movie, newUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.tags = tags
            }
        }

        // ========================================================
        // 2. منطق المسلسلات (Python: Analysis logic)
        // ========================================================
        debugLog("[*] Analyzing Series: $cleanUrl")
        
        val response = app.get(cleanUrl)
        val htmlContent = response.text
        val doc = response.document

        val title = doc.select("h1.PostTitle").text().trim()
        val desc = doc.select(".StoryArea p").text().trim()
        val poster = fixPoster(doc.select("#poster img").attr("src"))
        val year = extractYear(doc)
        val tags = doc.select("ul.RightTaxContent li a").map { it.text() }

        val seasonsList = mutableListOf<Pair<String, String>>()

        // 1. البحث عن المواسم (Seasons Feed) - محاكاة Regex البايثون
        // re.search(r'const\s+feedURL\s*=\s*"([^"]+)";', html_content)
        val feedMatch = Regex("""const\s+feedURL\s*=\s*['"]([^"']+)['"]""").find(htmlContent)
        
        if (feedMatch != null) {
            val feedUrlSuffix = feedMatch.groupValues[1]
            // معالجة الرابط كما في البايثون
            val feedUrl = if (feedUrlSuffix.startsWith("http")) feedUrlSuffix else "$mainUrl$feedUrlSuffix"
            // تنظيف الرابط لجلب JSON صافي
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
            } catch (e: Exception) { debugLog("Error fetching seasons feed") }
        }

        // 2. إذا لم نجد Feed، نبحث في HTML
        if (seasonsList.isEmpty()) {
            doc.select(".allseasonss .Small--Box.Season a").forEach {
                val sTitle = it.attr("title").ifEmpty { "Season" }
                val sLink = it.attr("href")
                if (sLink.isNotEmpty()) seasonsList.add(sTitle to sLink)
            }
        }

        // إذا القائمة فارغة، نعتبر الصفحة الحالية هي الموسم الوحيد
        if (seasonsList.isEmpty()) {
            debugLog("📂 Main List (Single Season)")
            seasonsList.add("الموسم 1" to cleanUrl)
        } else {
            debugLog("   Seasons count: ${seasonsList.size}")
        }

        val allEpisodes = mutableListOf<Episode>()

        // الدوران على المواسم
        seasonsList.forEachIndexed { index, (sTitle, sUrl) ->
            val seasonNum = index + 1
            debugLog("📂 $sTitle")

            // جلب حلقات الموسم
            var eps: List<Episode>
            
            if (sUrl == cleanUrl) {
                // إذا كان نفس الرابط، نستخدم المحتوى المحمل مسبقاً
                eps = getEpisodesDirect(htmlContent, sUrl, seasonNum)
            } else {
                // إذا رابط مختلف، نجلب محتواه (كما يفعل البايثون: s_resp = requests.get...)
                try {
                    val sHtml = app.get(sUrl).text
                    eps = getEpisodesDirect(sHtml, sUrl, seasonNum)
                } catch (e: Exception) {
                    eps = emptyList()
                }
            }

            if (eps.isNotEmpty()) {
                eps.forEach { debugLog("    🔗 ${it.url}") }
                allEpisodes.addAll(eps)
            } else {
                debugLog("    (No episodes available directly)")
            }
        }

        // محاولة أخيرة للمسلسلات بدون هيكل واضح (مثل El Fetna في كود البايثون)
        if (allEpisodes.isEmpty()) {
            debugLog("Attempting Fallback (Feed Search)...")
            val slug = cleanUrl.substringAfterLast("/").replace(".html", "").replace("_9", "")
            val eps = getEpisodesFromSearchFeed(slug, 1)
            allEpisodes.addAll(eps)
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = desc
            this.tags = tags
        }
    }

    // ========================================================
    // دالة استخراج الحلقات (مطابقة لدالة python: get_episodes_direct)
    // ========================================================
    private fun getEpisodesDirect(htmlContent: String, pageUrl: String, seasonNum: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // محاولة 1: البحث عن متغيرات JS (مثل Ragouj)
        // count = int(re.search(r'const\s+totalEpisodes\s*=\s*(\d+);', html_content).group(1))
        val countMatch = Regex("""const\s+totalEpisodes\s*=\s*(\d+);""").find(htmlContent)
        // base_link = re.search(r'const\s+baseLink\s*=\s*"([^"]+)";', html_content).group(1)
        val baseLinkMatch = Regex("""const\s+baseLink\s*=\s*['"]([^"']+)['"]""").find(htmlContent)

        if (countMatch != null && baseLinkMatch != null) {
            val count = countMatch.groupValues[1].toInt()
            val baseLink = baseLinkMatch.groupValues[1]
            
            // استخراج الدومين من الرابط الحالي
            // parsed_uri = urllib.parse.urlparse(page_url) -> domain
            val domain = "https://${java.net.URI(pageUrl).host}"

            for (i in 1..count) {
                val fullLink = when {
                    baseLink.startsWith("http") -> "$baseLink$i.html"
                    baseLink.startsWith("/") -> "$domain$baseLink$i.html"
                    else -> {
                        // إزالة / الزائدة إذا وجدت
                        val cleanBase = baseLink.removePrefix("/")
                        "$domain/p/$cleanBase$i.html"
                    }
                }
                
                episodes.add(newEpisode(fullLink) {
                    this.name = "الحلقة $i"
                    this.season = seasonNum
                    this.episode = i
                })
            }
            return episodes // في البايثون، إذا نجح هذا، يعيد القائمة فوراً
        }

        // محاولة 2: البحث عن روابط HTML (للمسلسلات القديمة)
        // links = soup.select('.allepcont .row a')
        val doc = org.jsoup.Jsoup.parse(htmlContent)
        val links = doc.select(".allepcont .row a")
        
        links.forEach { link ->
            val title = link.select("h2").text().trim().ifEmpty { "Episode" }
            val href = link.attr("href")
            
            // استخراج رقم الحلقة للمساعدة في الترتيب
            val epNum = Regex("""(\d+)""").findAll(title).lastOrNull()?.value?.toIntOrNull()

            if (href.isNotEmpty()) {
                episodes.add(newEpisode(href) {
                    this.name = title
                    this.season = seasonNum
                    this.episode = epNum
                })
            }
        }
        
        return episodes
    }

    // دالة البحث الاحتياطي (Fallback) كما في البايثون
    private suspend fun getEpisodesFromSearchFeed(slug: String, seasonNum: Int): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val pageFeedUrl = "$mainUrl/feeds/pages/default?alt=json&max-results=100&q=$slug"
        
        try {
            val feedJson = app.get(pageFeedUrl).text
            val feedData = AppUtils.parseJson<BloggerFeed>(feedJson)
            feedData.feed?.entry?.forEach { e ->
                val l = e.link?.find { it.rel == "alternate" }?.href ?: ""
                // if slug in l and ('ep' in l or 'hal9a' in l):
                if (l.contains(slug) && (l.contains("ep") || l.contains("hal9a"))) {
                     val t = e.title?.t ?: "Episode"
                     val epNum = Regex("""(\d+)\.html""").find(l)?.groupValues?.get(1)?.toIntOrNull()
                     
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
