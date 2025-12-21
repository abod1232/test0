
package com.cimawbas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element

class CimaTn : MainAPI() {
override var mainUrl = "https://www.syria-live.tv"
override var name = "Syria Live"
override val hasMainPage = true
override var lang = "ar"


// دعم البث المباشر والأخبار
override val supportedTypes = setOf(TvType.Live, TvType.Movie)

// دالة مساعدة لإصلاح الروابط
private fun fixUrl(url: String): String {
    if (url.startsWith("//")) return "https:$url"
    if (!url.startsWith("http")) return "$mainUrl$url"
    return url
}

override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
    val document = app.get(mainUrl).document
    val homePageList = mutableListOf<HomePageList>()

    // --- القسم 1: مباريات اليوم (Live) ---
    // استنادا لملف "سوريا لايف.html" الكلاس هو match-container
    val matches = document.select(".match-container").mapNotNull { element ->
        val rightTeam = element.selectFirst(".right-team .team-name")?.text() ?: return@mapNotNull null
        val leftTeam = element.selectFirst(".left-team .team-name")?.text() ?: return@mapNotNull null

        val status = element.selectFirst(".date")?.text() ?: ""
        val time = element.selectFirst(".match-time")?.text() ?: ""
        val info = "$status | $time"

        val title = "$rightTeam vs $leftTeam\n$info"

        val href = element.selectFirst("a.ahmed")?.attr("href") ?: return@mapNotNull null
        val poster = element.selectFirst(".right-team img")?.attr("data-src")
            ?: element.selectFirst(".right-team img")?.attr("src")

        newLiveSearchResponse(title, fixUrl(href), TvType.Live) {
            this.posterUrl = fixUrl(poster ?: "")
        }
    }


    if (matches.isNotEmpty()) {
        homePageList.add(HomePageList("مباريات اليوم", matches, isHorizontalImages = true))
    }

    // --- القسم 2: آخر الأخبار (News) ---
    // استنادا للملف، الكلاس هو AY-PItem
    val news = document.select(".AY-PItem").mapNotNull { element ->
        val titleElement = element.selectFirst(".AY-PostTitle a") ?: return@mapNotNull null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val poster = element.selectFirst("img")?.attr("data-src")
            ?: element.selectFirst("img")?.attr("src")

        newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = fixUrl(poster ?: "")
        }
    }

    if (news.isNotEmpty()) {
        homePageList.add(HomePageList("آخر الأخبار", news))
    }

    return HomePageResponse(homePageList)
}

override suspend fun search(query: String): List<SearchResponse> {
    val searchUrl = "$mainUrl/?s=$query"
    val document = app.get(searchUrl).document

    return document.select(".AY-PItem").mapNotNull { element ->
        val titleElement = element.selectFirst(".AY-PostTitle a") ?: return@mapNotNull null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val poster = element.selectFirst("img")?.attr("data-src")

        newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = fixUrl(poster ?: "")
        }
    }
}

override suspend fun load(url: String): LoadResponse? {
    val document = app.get(url).document

    // العنوان
    val title = document.selectFirst(".EntryTitle")?.text()
        ?: document.selectFirst("h1")?.text()
        ?: "No Title"

    // البوستر
    val poster = document.selectFirst(".teamlogo")?.attr("data-src") // في صفحة المباراة
        ?: document.selectFirst(".EntryHeader img")?.attr("src") // في صفحة الخبر
        ?: document.selectFirst(".post-thumb img")?.attr("src")

    // الوصف وتفاصيل المباراة
    val descriptionBuilder = StringBuilder()

    // استخراج جدول المعلومات (المعلق، القناة، البطولة) من table-bordered
    val tableRows = document.select(".table-bordered tr")
    if (tableRows.isNotEmpty()) {
        tableRows.forEach { row ->
            val key = row.select("th, td").firstOrNull()?.text()
            val value = row.select("td").lastOrNull()?.text()
            if (key != null && value != null && key != value) {
                descriptionBuilder.append("$key: $value\n")
            }
        }
    } else {
        // إذا كان خبر عادي
        descriptionBuilder.append(document.select(".entry-content p").text())
    }

    val type = if (url.contains("/matches/") || tableRows.isNotEmpty()) TvType.Live else TvType.Movie

    return if (type == TvType.Live) {
        newMovieLoadResponse(title, url, type, url) {
            this.posterUrl = fixUrl(poster ?: "")
            this.plot = descriptionBuilder.toString()
        }
    } else {
        newMovieLoadResponse(title, url, type, url) {
            this.posterUrl = fixUrl(poster ?: "")
            this.plot = descriptionBuilder.toString()
        }
    }
}

override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data).document
    var foundLinks = false

    // 1. البحث عن Iframe المباشر في الصفحة الحالية
    document.select("iframe").forEach { iframe ->
        val src = iframe.attr("src")
        if (src.isNotBlank()) {
            val fixedSrc = fixUrl(src)
            // محاولة استخراج الرابط من الـ Iframe
            if (isValidStreamUrl(fixedSrc)) {
                loadExtractor(fixedSrc, data, subtitleCallback, callback)
                foundLinks = true
            }
        }
    }

    // 2. البحث في أزرار السيرفرات (video-serv a)
    // هذه الأزرار غالباً تنقلك لصفحة أخرى تحتوي على الـ Iframe
    val serverButtons = document.select(".video-serv a")
    serverButtons.forEach { btn ->
        val serverName = btn.text()
        val serverLink = btn.attr("href")

        if (serverLink.isNotBlank()) {
            val fixedServerLink = fixUrl(serverLink)

            // نقوم بطلب الصفحة الخاصة بالسيرفر لاستخراج الفيديو منها
            try {
                val serverDoc = app.get(fixedServerLink, referer = data).document
                serverDoc.select("iframe").forEach { iframe ->
                    val src = iframe.attr("src")
                    if (src.isNotBlank()) {
                        val fixedSrc = fixUrl(src)
                        // إضافة الرابط كمصدر
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "$name - $serverName",
                                url = fixedSrc,
                            ) {
                                referer = fixedServerLink
                                quality = Qualities.Unknown.value
                            }
                        )
                        // وأيضاً نحاول فحصه عبر المستخرجات التلقائية
                        loadExtractor(fixedSrc, fixedServerLink, subtitleCallback, callback)
                        foundLinks = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    return foundLinks
}

// دالة للتحقق مما إذا كان الرابط يستحق الفحص (فلترة الإعلانات وروابط التواصل)
private fun isValidStreamUrl(url: String): Boolean {
    return !url.contains("facebook") &&
            !url.contains("twitter") &&
            !url.contains("instagram") &&
            (url.contains("embed") || url.contains("player") || url.contains("live") || url.contains("cairo-news"))
}

}        val url = element.attr("href")
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
