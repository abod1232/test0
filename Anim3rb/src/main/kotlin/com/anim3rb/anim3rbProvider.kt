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
}
