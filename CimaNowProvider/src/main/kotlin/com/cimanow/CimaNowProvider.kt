package com.cimanow

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element

class CimaNowProvider(private val context: Context) : MainAPI() {
    // الرابط الأساسي للموقع (يمكنك تغييره إذا تم تغيير الدومين مستقبلاً)
    override var mainUrl = "https://bw.alooytv13.xyz"
    override var name = "AlooyTv"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)

    // الأقسام التي ستظهر في الصفحة الرئيسية للتطبيق
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

    // دالة مساعدة لاستخراج بيانات المسلسل/الفيلم من كود HTML
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".movie-title h3 a")?.text() ?: return null
        val url = this.selectFirst(".movie-title h3 a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img.lazy")?.attr("data-src") ?: this.selectFirst("img.lazy")?.attr("src")
        val qualityStr = this.selectFirst(".video_quality .label")?.text()

        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(qualityStr)
        }
    }

    // جلب بيانات الصفحة الرئيسية (الأقسام)
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // الموقع يستخدم نظام صفحات يعتمد على الأرقام (24, 48..) لكن الصفحة الأولى تكفي كبداية
        if (page > 1) return throw ErrorLoadingException("لا يوجد صفحات إضافية مدعومة حالياً")

        val document = app.get(request.data).document
        val home = document.select(".movie-container > div").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    // جلب نتائج البحث
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val document = app.get(url).document
        return document.select(".movie-container > div").mapNotNull {
            it.toSearchResult()
        }
    }

    // جلب صفحة التفاصيل (الحلقات، القصة، الغلاف)
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst(".tab-content .col-md-3 img")?.attr("src")
        val description = document.selectFirst(".tab-content .col-md-9 p")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        val episodesElements = document.select(".season a.btn-ep")

        if (episodesElements.isNotEmpty()) {
            // إذا كان مسلسلاً (يحتوي على أزرار للحلقات)
            episodesElements.forEach { element ->
                val epName = element.text() // مثلاً Ep#1
                val epUrl = element.attr("href")
                // استخراج رقم الحلقة من النص (مثلاً Ep#1 -> 1)
                val epNum = epName.replace(Regex("[^0-9]"), "").toIntOrNull()
                
                episodes.add(Episode(epUrl, name = epName, episode = epNum))
            }
        } else {
            // إذا كان فيلماً أو حلقة مفردة لا تحتوي على قائمة حلقات
            episodes.add(Episode(url, name = "المشاهدة"))
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // استخراج سيرفرات المشاهدة وروابط الفيديو
    override suspend fun loadLinks(data: String, isCasting: Boolean, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        // البحث عن وسم <video> واستخراج الرابط من <source>
        val sources = document.select("video source")
        
        sources.forEach { source ->
            val videoUrl = source.attr("src")
            if (videoUrl.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "AlooyTv Server",
                        url = videoUrl,
                        ){
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value

                    }
                )
            }
        }
        return true
    }
}
