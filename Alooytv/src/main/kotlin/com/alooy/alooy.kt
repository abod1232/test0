package com.alooy
// استيراد المكتبات الضرورية المحدثة
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AlooyTvProvider : MainAPI() {
    override var mainUrl = "https://bw.alooytv13.xyz"
    override var name = "AlooyTv"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/tv-series.html" to "أحدث الحلقات",
        "$mainUrl/genre/ramadan-kleeji-2024.html" to "رمضان خليجي",
        "$mainUrl/genre/ramadan-arabi-2024.html" to "رمضان عربي",
        "$mainUrl/genre/turki.html" to "مسلسلات تركية",
        "$mainUrl/genre/arabic.html" to "مسلسلات عربية",
        "$mainUrl/genre/kleeji.html" to "مسلسلات خليجية",
        "$mainUrl/genre/Foreign-series.html" to "مسلسلات اجنبية",
        "$mainUrl/genre/foreign-movies.html" to "افلام اجنبية",
        "$mainUrl/genre/anmi.html" to "انمي"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".movie-title h3 a")?.text() ?: return null
        val url = this.selectFirst(".movie-title h3 a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img.lazy")?.attr("data-src") ?: this.selectFirst("img.lazy")?.attr("src")

        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return throw ErrorLoadingException("لا يوجد صفحات إضافية مدعومة حالياً")
        val document = app.get(request.data).document
        val home = document.select(".movie-container > div").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val document = app.get(url).document
        return document.select(".movie-container > div").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst(".tab-content .col-md-3 img")?.attr("src")
        val description = document.selectFirst(".tab-content .col-md-9 p")?.text()?.trim()

        // البحث عن أزرار الحلقات
        val episodesElements = document.select(".season a.btn-ep")

        val episodes = if (episodesElements.isNotEmpty()) {
            // حل مشكلة Deprecated Episode: استخدام newEpisode
            episodesElements.map { element ->
                val epName = element.text()
                val epUrl = element.attr("href")
                val epNum = epName.replace(Regex("[^0-9]"), "").toIntOrNull()

                newEpisode(epUrl) {
                    this.name = epName
                    this.episode = epNum
                    posterUrl = poster

                }
            }
        } else {
            // إذا كان فيلماً، استخدام newEpisode أيضاً
            listOf(
                newEpisode(url) {
                    this.name = "مشاهدة الفيلم"
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // حل مشكلة Signature mismatch: إضافة subtitleCallback
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val sources = document.select("video source")

        sources.forEach { source ->
            val videoUrl = source.attr("src")
            if (videoUrl.isNotBlank()) {
                // حل مشكلة Deprecated ExtractorLink: استخدام newExtractorLink
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "AlooyTv Server",
                        url = videoUrl,
                    ) {
                        referer = "$mainUrl/"
                        quality = Qualities.Unknown.value
                    }
                )
            }
        }
        return true
    }
}