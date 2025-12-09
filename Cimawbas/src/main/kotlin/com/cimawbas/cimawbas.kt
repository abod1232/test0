package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class CimaWbas : MainAPI() {
    override var mainUrl = "https://cimawbas.org"
    override var name = "CimaWbas"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime)

    // تعريف الكوكيز هنا لاستخدامها في كل الطلبات
    private val protectionCookies = mapOf(
        "cf_clearance" to "GiTICM7SfHnNeeQxFszUi6XGBJzKoYvgkT2h2DXES5M-1765287006-1.2.1.1-ctTEI.mzBsUuaEtOPYncQ4g5uz7A8cRI7qRC8cqtgMGxT4jVIbP_HhezALFn7AlvA6yItStB.wCPDBHz_ru1iQLXBn_vpqrxBCgehb64e9kWRp.eijz93Rd7529f4fjNPxlqYf1ap3TRx3ZdrPHlTpSur5Cq1iMr46YK66kHynR1q0Mth.uHz.ljEGVoLbgMDM0kq3gjI8ZX6FIMNzyiU0l1evRncj4uAdegEZ588yg"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "أفلام",
        "$mainUrl/series/page/" to "مسلسلات",
        "$mainUrl/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d9%86%d9%85%d9%8a/page/" to "أفلام أنمي",
        "$mainUrl/category/%d9%85%d8%b3%d9%84%d8%b3%d9%84%d8%a7%d8%aa-%d8%a7%d9%86%d9%85%d9%8a/page/" to "مسلسلات أنمي",
        "$mainUrl/last/page/" to "أضيف حديثاً"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        // تم تمرير الكوكيز هنا
        val document = app.get(url, cookies = protectionCookies).document
        val home = document.select("li.Small--Box").mapNotNull {
            toSearchResult(it)
        }
        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val title = element.select("h3.title").text().trim()
        val url = element.select("a").attr("href")
        val posterUrl = element.select(".Poster img").let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
        val quality = element.select(".ribbon span").text().trim()

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        // تم تمرير الكوكيز هنا
        val document = app.get(url, cookies = protectionCookies).document
        return document.select("li.Small--Box").mapNotNull {
            toSearchResult(it)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // تم تمرير الكوكيز هنا
        val doc = app.get(url, cookies = protectionCookies).document

        val title = doc.select("h1.PostTitle").text().trim()
        val poster = doc.select(".left .image img").let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
        val description = doc.select(".StoryArea p").text().trim()
        val year = doc.select(".TaxContent a[href*='release-year']").text().trim().toIntOrNull()
        val rating = doc.select(".imdbR span").text().trim().toRatingInt()
        val tags = doc.select(".TaxContent .genre a").map { it.text() }

        // Check if it is a Series or Movie
        val episodes = doc.select(".allepcont .row a")

        if (episodes.isNotEmpty()) {
            // It's a Series
            val episodeList = episodes.map { ep ->
                val epTitle = ep.select(".ep-info h2").text()
                val epUrl = ep.attr("href")
                val epThumb = ep.select("img").let { it.attr("data-src").ifEmpty { it.attr("src") } }

                // Try to extract episode number
                val epNum = ep.select(".epnum").text().replace(Regex("[^0-9]"), "").toIntOrNull()

                newEpisode(epUrl) {
                    this.name = epTitle
                    this.posterUrl = epThumb
                    this.episode = epNum
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
            }
        } else {
            // It's a Movie
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // تم تمرير الكوكيز هنا
        val doc = app.get(data, cookies = protectionCookies).document

        // Extract watch servers from data-watch attribute
        doc.select("ul#watch li").forEach { server ->
            val embedUrl = server.attr("data-watch")
            if (embedUrl.isNotEmpty()) {
                loadExtractor(embedUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}