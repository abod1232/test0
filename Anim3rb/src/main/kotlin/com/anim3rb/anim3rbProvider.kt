package com.anim3rb

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Anime3rb(val context: Context) : MainAPI() {
    override var mainUrl = "https://anime3rb.com"
    override var name = "Anime3rb"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private var savedCookies: String = ""
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val NON_DIGITS = Regex("[^0-9]")
        val TITLE_EP_REGEX = Regex("الحلقة \\d+")
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية"
    )

    private fun toAbsoluteUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    private fun cleanTitleText(text: String): String {
        return text.replace("\\n", " ")
            .replace("\n", " ")
            .replace(Regex("بترجمة.*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private suspend fun getDocumentSmart(url: String): Document? {
        return try {
            val response = app.get(url, headers = mapOf("User-Agent" to USER_AGENT))
            if (response.code in 200..399) {
                // استخراج الكوكيز بشكل آمن
                val setCookie = response.headers["set-cookie"]
                if (setCookie != null) {
                    savedCookies = setCookie
                }
                response.document
            } else null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = getDocumentSmart(request.data) ?: return null
        val homeSets = mutableListOf<HomePageList>()

        // الأنميات المثبتة
        doc.select(".glide__slide:not(.glide__slide--clone) a.video-card").let { items ->
            val list = items.mapNotNull { toSearchResult(it) }
            if (list.isNotEmpty()) homeSets.add(HomePageList("الأنميات المثبتة", list))
        }

        // أحدث الحلقات
        doc.select("#videos a.video-card").let { items ->
            val list = items.mapNotNull { toSearchResult(it) }
            if (list.isNotEmpty()) homeSets.add(HomePageList("أحدث الحلقات", list))
        }

        return newHomePageResponse(homeSets, false)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val rawTitle = element.select("h3.title-name").text()
        if (rawTitle.isEmpty()) return null
        
        val title = cleanTitleText(rawTitle)
        val href = toAbsoluteUrl(element.attr("href"))
        val posterUrl = element.select("img").attr("src")
        val episodeText = element.select("p.number").text()
        val episodeNum = episodeText.filter { it.isDigit() }.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(false, episodeNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val mainDoc = getDocumentSmart(mainUrl) ?: return emptyList()

        // استخراج بيانات Livewire للبحث
        val scriptTag = mainDoc.selectFirst("script[src*=livewire.min.js]")
        val csrfToken = mainDoc.selectFirst("meta[name=\"csrf-token\"]")?.attr("content") ?: ""

        val form = mainDoc.selectFirst("div[wire:id]")
        val snapshotRaw = form?.attr("wire:snapshot") ?: return emptyList()

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "X-CSRF-TOKEN" to csrfToken,
            "Content-Type" to "application/json",
            "X-Livewire" to "true"
        )

        val payload = mapOf(
            "_token" to csrfToken,
            "components" to listOf(
                mapOf(
                    "snapshot" to snapshotRaw,
                    "updates" to mapOf("query" to query),
                    "calls" to emptyList<Any>()
                )
            )
        )

        val postRes = app.post("$mainUrl/livewire/update", headers = headers, json = payload)
        if (postRes.code != 200) return emptyList()

        val htmlContent = postRes.text // عادة Livewire يعيد HTML داخل JSON، هذا تبسيط
        val soupResults = Jsoup.parse(htmlContent)

        return soupResults.select("a").mapNotNull { item ->
            val title = item.selectFirst("h4")?.text() ?: return@mapNotNull null
            newAnimeSearchResponse(title, toAbsoluteUrl(item.attr("href")), TvType.Anime) {
                this.posterUrl = item.selectFirst("img")?.attr("src")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = getDocumentSmart(url) ?: return null

        val title = doc.selectFirst("h1")?.text()?.let { TITLE_EP_REGEX.replace(it, "").trim() } ?: "Unknown"
        val poster = doc.selectFirst("img[alt*='بوستر']")?.attr("src")
        val desc = doc.selectFirst("p.synopsis")?.text()

        val episodes = doc.select(".video-list a").reversed().mapNotNull { element ->
            val href = toAbsoluteUrl(element.attr("href"))
            val epText = element.select(".video-data").firstOrNull()?.child(0)?.text() ?: ""
            val epNum = epText.replace(NON_DIGITS, "").toIntOrNull()

            newEpisode(href) {
                this.name = epText
                this.episode = epNum
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = desc
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val resp = app.get(data, headers = mapOf("User-Agent" to USER_AGENT))
        
        // البحث عن رابط المشغل
        val playerPattern = Regex("""https?://video\.vid3rb\.com/player/[^"']+""")
        val playerUrl = playerPattern.find(resp.text)?.value ?: return false

        val playerResponse = app.get(playerUrl, headers = mapOf("Referer" to mainUrl)).text
        
        // استخراج روابط الفيديو من JS
        val jsonPattern = Regex("""var\s+video_sources\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
        val jsonMatch = jsonPattern.find(playerResponse)?.groups?.get(1)?.value ?: return false
        
        val videoList = AppUtils.parseJson<List<VideoSource>>(jsonMatch)

        videoList.forEach { item ->
            if (item.premium != "true") {
                val quality = item.label.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value
                callback.invoke(
                    ExtractorLink(
                        source = "Anime3rb",
                        name = "Anime3rb ${item.label}",
                        url = item.src.replace("\\", ""),
                        referer = "https://video.vid3rb.com/",
                        quality = quality
                    )
                )
            }
        }
        return true
    }

    data class VideoSource(
        val src: String,
        val label: String,
        val premium: String? = null
    )
}        }
    }

    private fun cleanTitleText(text: String): String {
        return text.replace("\\n", " ")
            .replace("\n", " ")
            .replace(Regex("بترجمة.*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // 1. الدالة المحدثة للتحقق من كلاودفلير باستخدام طلب GET خفيف
    private suspend fun getDocumentSmart(url: String): Document? {
        return try {
            val response = app.get(url, headers = mapOf("User-Agent" to USER_AGENT))
            if (response.code in 200..399) {
                // حفظ الكوكيز للمستقبل لتجنب الـ 419 في طلبات POST
                val setCookie = response.headers["set-cookie"]
                if (!setCookie.isNullOrBlank()) savedCookies = setCookie
                response.document
            } else {
                // إرجاع خطأ مباشرة إذا واجه أي كود حظر مثل 403 أو 409 أو غيره
                null
            }
        } catch (e: Exception) {
            // إرجاع خطأ مباشرة عند فشل الاتصال بدون فتح WebView
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = getDocumentSmart(request.data) ?: return null
        val homeSets = mutableListOf<HomePageList>()

        val pinnedHeader = doc.selectFirst("h2:contains(الأنميات المثبتة)")
        val pinnedList = pinnedHeader?.parent()?.parent()?.parent()?.select(".glide__slide:not(.glide__slide--clone) a.video-card")?.mapNotNull {
            toSearchResult(it)
        }
        if (!pinnedList.isNullOrEmpty()) {
            homeSets.add(HomePageList("الأنميات المثبتة", pinnedList))
        }

        val latestList = doc.select("#videos a.video-card").mapNotNull {
            toSearchResult(it)
        }
        if (latestList.isNotEmpty()) {
            homeSets.add(HomePageList("أحدث الحلقات", latestList))
        }

        val addedHeader = doc.selectFirst("h3:contains(آخر الأنميات المضافة)")
        val addedList = addedHeader?.parent()?.parent()?.parent()?.select(".glide__slide:not(.glide__slide--clone) a.video-card")?.mapNotNull {
            toSearchResult(it)
        }
        if (!addedList.isNullOrEmpty()) {
            homeSets.add(HomePageList("آخر الأنميات المضافة", addedList))
        }

        return newHomePageResponse(homeSets, false)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        try {
            val rawTitle = element.select("h3.title-name").text()
            val title = cleanTitleText(rawTitle)
            val href = toAbsoluteUrl(element.attr("href"))
            val posterUrl = element.select("img").attr("src")
            val episodeText = cleanTitleText(element.select("p.number").text())
            val episodeNum = episodeText.filter { it.isDigit() }.toIntOrNull()

            return newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(false, episodeNum)
            }
        } catch (e: Exception) {
            return null
        }
    }

    // 2. البحث الجديد بنظام Livewire
    override suspend fun search(query: String): List<SearchResponse> {
        val mainDoc = getDocumentSmart(mainUrl) ?: return emptyList()

        val scriptTag = mainDoc.selectFirst("script[src*=livewire.min.js]")
        val csrfToken = scriptTag?.attr("data-csrf") ?: return emptyList()

        val form = mainDoc.selectFirst("form[wire:id]")
        val snapshotRaw = form?.attr("wire:snapshot") ?: return emptyList()
        val snapshotStr = org.jsoup.parser.Parser.unescapeEntities(snapshotRaw, true)

        val headers = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Content-Type" to "application/json",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/"
        )
        if (savedCookies.isNotBlank()) {
            headers["Cookie"] = savedCookies
        }

        val updateUrl = "$mainUrl/livewire/update"
        val payload = mapOf(
            "_token" to csrfToken,
            "components" to listOf(
                mapOf(
                    "snapshot" to snapshotStr,
                    "updates" to mapOf("query" to query),
                    "calls" to emptyList<Any>()
                )
            )
        )

        val postRes = app.post(updateUrl, headers = headers, json = payload)
        if (postRes.code != 200) return emptyList()

        val responseJson = AppUtils.parseJson<Map<String, Any>>(postRes.text)
        val components = responseJson["components"] as? List<Map<String, Any>> ?: return emptyList()
        val effects = components.firstOrNull()?.get("effects") as? Map<String, Any> ?: return emptyList()
        val htmlContent = effects["html"] as? String ?: return emptyList()

        val soupResults = Jsoup.parse(htmlContent)

        return soupResults.select("a.simple-title-card").mapNotNull { item ->
            val rawTitle = item.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
            val title = cleanTitleText(rawTitle)
            val link = item.attr("href")
            val absoluteLink = toAbsoluteUrl(link)
            val img = item.selectFirst("img")
            val image = img?.attr("src")
            val ratingTag = item.selectFirst(".badge")
            val rating = ratingTag?.text()?.trim() ?: "N/A"

            val type = if (rating.contains("Movie") || rating.contains("Film") || title.contains("فيلم")) {
                TvType.AnimeMovie
            } else {
                TvType.Anime
            }

            newAnimeSearchResponse(title, absoluteLink, type) {
                this.posterUrl = image
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = getDocumentSmart(url) ?: return null

        val title = TITLE_EP_REGEX.replace(doc.selectFirst("h1")?.text() ?: "", "").trim()
        val poster = doc.selectFirst("img[alt*='بوستر']")?.attr("src") ?: ""
        val desc = doc.selectFirst("p.synopsis")?.text() ?: doc.selectFirst("meta[name='description']")?.attr("content") ?: ""

        val episodes = doc.select(".video-list a").reversed().mapNotNull { element ->
            val href = toAbsoluteUrl(element.attr("href"))
            val videoData = element.selectFirst(".video-data")
            val epText = videoData?.child(0)?.text() ?: ""
            val epName = videoData?.child(1)?.text() ?: ""
            val epNum = epText.replace(NON_DIGITS, "").toIntOrNull()
            val imgAttr = element.selectFirst("img")?.attr("src") ?: ""

            newEpisode(href) {
                this.name = epName.ifBlank { epText }
                this.episode = epNum
                this.posterUrl = imgAttr
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster
            this.plot = desc
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headersMap = mapOf("User-Agent" to USER_AGENT)
        val resp = app.get(data, headers = headersMap)
        val htmlText = resp.text
        val playerPattern = Regex("https:(?:\\\\/|/){2}video\\.vid3rb\\.com(?:\\\\/|/)player(?:\\\\/|/)[^\"']+")
        val match = playerPattern.find(htmlText) ?: return false

        val playerUrl = match.value.replace("\\", "").replace("&amp;", "&").replace("\\u0026", "&")
        val videoHeaders = mapOf(
            "Host" to "video.vid3rb.com",
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Site" to "cross-site"
        )

        val playerResponse = app.get(playerUrl, headers = videoHeaders).text
        val jsonPattern = Regex("var\\s+video_sources\\s*=\\s*(\\[.*?\\]);", RegexOption.DOT_MATCHES_ALL)

        var success = false
        jsonPattern.findAll(playerResponse).forEach { m ->
            val jsonStr = m.groupValues[1]
            val videoList = AppUtils.parseJson<List<Map<String, Any>>>(jsonStr)

            videoList.forEach { item ->
                val src = item["src"]?.toString()?.replace("\\", "")?.replace("&amp;", "&")?.replace("\\u0026", "&") ?: return@forEach
                val label = item["label"]?.toString() ?: "Unknown"
                val premium = item["premium"]?.toString() == "true"

                if (!premium) {
                    val quality = label.replace(NON_DIGITS, "").toIntOrNull() ?: Qualities.Unknown.value
                    callback.invoke(
                        newExtractorLink(
                            "Anime3rb",
                            "Anime3rb $label",
                            src,
                        ) {
                            "https://video.vid3rb.com/"
                            quality
                            false
                            mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://video.vid3rb.com/"
                            )
                        }
                    )
                    success = true
                }
            }
        }
        return success
    }
}
