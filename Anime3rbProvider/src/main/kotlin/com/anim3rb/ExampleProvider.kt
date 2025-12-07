package com.anime3rb

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Anime3rb : MainAPI() {
    override var mainUrl = "https://anime3rb.com"
    override var name = "Anime3rb"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    // 1. الكوكيز المحدثة
    private val cookiesMap = mapOf(
        "cf_clearance" to "Mxd9VtmgipdY5o_6zvcKVRLwqyfnJcJRyPiTmW3S8cQ-1765128736-1.2.1.1-C0uBuiaENA22z4pbv2MCtXY.S6ibm_ANAHkUGP_XB0wdFbGUZDrgl1oXg7LIsGkcsGD5QHQ0bQvFZSByJiMlACesfyq.10VVd6cRZvytTvQZeh8ErCkTFwP9SBE5fNCP8z9CmUnS2lSmCNWa_c1kri6VFxGIG4LF5LLiaIuw7ZvTUyOgsglb_2KbqONlgBZZIVuCIZuCUp2dciLQ6iUMiznjBV9P.Z5FrdxIh.QmOpTwb2Gh7ZG81ni3a2Y9a.Nv"
    )

    private val cookieHeaderValue = cookiesMap.entries.joinToString("; ") { "${it.key}=${it.value}" }

    // ترويسات عامة للموقع
    private val myHeaders = mapOf(
        "Host" to "anime3rb.com",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "ar-EG,ar;q=0.9",
        "sec-fetch-site" to "none",
        "sec-fetch-mode" to "navigate",
        "sec-fetch-dest" to "document",
        "upgrade-insecure-requests" to "1",
        "Cookie" to cookieHeaderValue
    )

    // ---------------------------------------------------------------
    // 1. الصفحة الرئيسية
    // ---------------------------------------------------------------
    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val doc = app.get(request.data, headers = myHeaders).document
        val homeSets = mutableListOf<HomePageList>()

        val pinnedHeader = doc.select("h2:contains(الأنميات المثبتة)").firstOrNull()
        if (pinnedHeader != null) {
            val pinnedContainer = pinnedHeader.parent()?.parent()?.parent()
            val pinnedList = pinnedContainer?.select(".glide__slide:not(.glide__slide--clone) a.video-card")?.mapNotNull { 
                toSearchResult(it) 
            }
            if (!pinnedList.isNullOrEmpty()) {
                homeSets.add(HomePageList("الأنميات المثبتة", pinnedList))
            }
        }

        val latestEpisodesList = doc.select("#videos a.video-card").mapNotNull { 
            toSearchResult(it) 
        }
        if (latestEpisodesList.isNotEmpty()) {
            homeSets.add(HomePageList("أحدث الحلقات", latestEpisodesList))
        }

        val addedHeader = doc.select("h3:contains(آخر الأنميات المضافة)").firstOrNull()
        if (addedHeader != null) {
            val addedContainer = addedHeader.parent()?.parent()?.parent()
            val addedList = addedContainer?.select(".glide__slide:not(.glide__slide--clone) a.video-card")?.mapNotNull { 
                toSearchResult(it) 
            }
            if (!addedList.isNullOrEmpty()) {
                homeSets.add(HomePageList("آخر الأنميات المضافة", addedList))
            }
        }

        return newHomePageResponse(homeSets)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val title = element.select("h3.title-name").text().trim()
        val href = fixUrl(element.attr("href"))
        val posterUrl = element.select("img").attr("src")
        
        // تصحيح: استخراج الرقم فقط وتمريره كـ Int
        val episodeText = element.select("p.number").text().trim()
        val episodeNum = episodeText.filter { it.isDigit() }.toIntOrNull()
        
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(false, episodeNum)
        }
    }

    // ---------------------------------------------------------------
    // 2. البحث
    // ---------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val doc = app.get(url, headers = myHeaders).document

        return doc.select("a.simple-title-card").mapNotNull {
            val title = it.select("h4.text-lg").text().trim()
            val href = fixUrl(it.attr("href"))
            val posterUrl = it.select("img").attr("src")
            val typeText = it.select("div.details span.badge").text()
            val type = if (typeText.contains("Movie") || title.contains("Film")) TvType.AnimeMovie else TvType.Anime

            newAnimeSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }
    }

    // ---------------------------------------------------------------
    // 3. تحميل التفاصيل
    // ---------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = myHeaders).document
        
        val title = doc.select("h1").text().replace("الحلقة \\d+".toRegex(), "").trim()
        val poster = doc.select("img[alt*='بوستر']").attr("src")
        val desc = doc.select("p.synopsis, meta[name='description']").attr("content")

        val episodes = doc.select(".videos-list a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val epText = element.select(".video-data span").text() 
            val epNum = epText.replace(Regex("[^0-9]"), "").toIntOrNull()
            val epName = element.select(".video-data p").text()

            if (href.isEmpty()) return@mapNotNull null

            newEpisode(href) {
                this.name = if (epName.isNotBlank()) epName else epText
                this.episode = epNum
                this.posterUrl = element.select("img").attr("src")
            }
        }.reversed()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = desc
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // ---------------------------------------------------------------
    // 4. استخراج روابط المشاهدة (نفس منطق بايثون)
    // ---------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // الخطوة 1: طلب صفحة الأنمي
        val doc = app.get(data, headers = myHeaders).document
        val htmlText = doc.html()

        // Regex لاستخراج رابط المشغل من داخل JSON
        // النمط يطابق: https:\/\/video.vid3rb.com\/player\/...
        val playerPattern = """https:\\?/\\?/video\.vid3rb\.com\\?/player\\?/[^"&\\\']+(?:\\u0026|&amp;|\|&)[^"&\\\']+""".toRegex()
        val match = playerPattern.find(htmlText)
        
        if (match != null) {
            // تنظيف الرابط (إزالة الرموز الزائدة)
            val playerUrl = match.value
                .replace("\\", "")
                .replace("&amp;", "&")
                .replace("\\u0026", "&")
            
            // ترويسات خاصة للمشغل (Referer ضروري جداً)
            val videoHeaders = mapOf(
                "Host" to "video.vid3rb.com",
                "User-Agent" to myHeaders["User-Agent"]!!,
                "Referer" to "https://anime3rb.com/",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Site" to "cross-site"
            )
            
            try {
                // الخطوة 2: طلب رابط المشغل
                val playerResponse = app.get(playerUrl, headers = videoHeaders).text
                
                // البحث عن رابط الفيديو المباشر (/video/) داخل كود المشغل
                val videoPattern = """https:\\?/\\?/video\.vid3rb\.com\\?/video\\?/[^"\'\s<>]*""".toRegex()
                val videoMatch = videoPattern.find(playerResponse)
                
                if (videoMatch != null) {
                    val directVideoUrl = videoMatch.value.replace("\\", "")
                    
                    // إضافة الرابط للمشغل
                    callback.invoke(
                        newExtractorLink(
                            source = "Vid3rb",
                            name = "Vid3rb Auto",
                            url = directVideoUrl,
                        ) {
                            referer = "https://video.vid3rb.com/" // مهم لتشغيل الفيديو
                            quality = Qualities.Unknown.value
                            headers = mapOf(
                                "User-Agent" to videoHeaders["User-Agent"]!!,
                                "Referer" to "https://video.vid3rb.com/"
                           }
                        )
                    )
                }
            } catch (e: Exception) {
                // في حال فشل الطلب الثاني
                // e.printStackTrace()
            }
        }
        
        return true
    }
}
