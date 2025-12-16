
package com.cimawbas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import android.util.Base64

class CimaWbas : MainAPI() {
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

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        val title = doc.select("h1.PostTitle").text().trim()
        val description = doc.select(".StoryArea p").text().trim()
        
        var posterUrl = doc.select("#poster img").attr("src")
        if (posterUrl.isEmpty()) posterUrl = doc.select(".image img").attr("src")
        
        posterUrl = posterUrl.replace(Regex("/s\\d+-c/"), "/w600/")
                             .replace(Regex("/w\\d+/"), "/w600/")
                             .replace(Regex("/s\\d+/"), "/s1600/")

        val year = doc.select("ul.RightTaxContent li:contains(تاريخ اصدار)").text()
            .replace("تاريخ اصدار الفيلم :", "")
            .replace("تاريخ اصدار المسلسل :", "")
            .replace("date_range", "")
            .trim().toIntOrNull()
            
        val tags = doc.select("ul.RightTaxContent li a").map { it.text() }

        val isSeries = tags.any { it.contains("مسلسل") } || url.contains("episode") || url.contains("-ep-")
        
        if (isSeries) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf()) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = description
                this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, listOf()) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // الطريقة الأولى: استخراج الروابط من مصفوفة السيرفرات في السكريبت
        val scriptContent = doc.select("script").joinToString(" ") { it.data() }
        val serverRegex = Regex("""const\s+servers\s*=\s*(\[\s*\{.*?\}\s*\])""", RegexOption.DOT_MATCHES_ALL)
        val match = serverRegex.find(scriptContent)

        if (match != null) {
            val jsonString = match.groupValues[1]
            try {
                val urlRegex = Regex("""url\s*:\s*['"](.*?)['"]""")
                // الإصلاح هنا: استخدام forEach مباشرة بدلاً من map().toList() لتجنب خطأ الاستنتاج
                urlRegex.findAll(jsonString).forEach { matchResult ->
                    val serverUrl = matchResult.groupValues[1]
                    loadExtractor(serverUrl, data, subtitleCallback, callback)
                }
                return true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // الطريقة الثانية: البحث عن iframe مباشرة
        doc.select("div.WatchIframe iframe").attr("src").let { iframeUrl ->
            if (iframeUrl.isNotEmpty()) {
                loadExtractor(iframeUrl, data, subtitleCallback, callback)
            }
        }
        
        // الطريقة الثالثة: زر المشاهدة المشفر
        val secureUrl = doc.select(".BTNSDownWatch a.watch").attr("data-secure-url")
        if (secureUrl.isNotEmpty() && secureUrl != "#") {
            try {
                val clean = secureUrl.substring(1, secureUrl.length - 1).reversed()
                val decodedUrl = String(Base64.decode(clean, Base64.DEFAULT))
                loadExtractor(decodedUrl, data, subtitleCallback, callback)
            } catch (e: Exception) {
               // فشل فك التشفير
            }
        }

        return true
    }
}
