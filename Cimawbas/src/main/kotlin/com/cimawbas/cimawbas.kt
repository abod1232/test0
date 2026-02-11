package com.cimawbas

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.SubtitleFile
import org.jsoup.nodes.Element

class Cimawbas : ParsableHttpSource() {

    override val name = "TukTuk Cinema"
    override val mainUrl = "https://tuktukhd.com"
    override val lang = "ar"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/recent/page/" to "المضاف حديثاً",
        "$mainUrl/category/movies-2/page/" to "أحدث الأفلام",
        "$mainUrl/category/series-1/page/" to "أحدث الحلقات",
    )

    override fun mainPageOf(data: String, element: Element): List<SearchResponse>? {
        return element.select(".Block--Item").mapNotNull {
            val linkTag = it.selectFirst("a") ?: return@mapNotNull null
            val title = it.selectFirst(".title")?.text() ?: linkTag.attr("title")
            val href = linkTag.attr("href")
            
            val imgTag = it.selectFirst(".Poster--Block img")
            val posterUrl = imgTag?.attr("data-src") 
                ?: imgTag?.attr("src") 
                ?: "https://tuktukhd.com/wp-content/themes/TukTukCinema3/no.png"

            val isMovie = !title.contains("مسلسل") && !title.contains("حلقة")

            if (isMovie) {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            } else {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query").document.select(".Block--Item").mapNotNull {
            val linkTag = it.selectFirst("a") ?: return@mapNotNull null
            val title = it.selectFirst(".title")?.text() ?: linkTag.attr("title")
            val href = linkTag.attr("href")
            val imgTag = it.selectFirst(".Poster--Block img")
            val posterUrl = imgTag?.attr("data-src") ?: imgTag?.attr("src")

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.post-title a")?.text() 
            ?: doc.selectFirst("h1")?.text() 
            ?: "Unknown"
            
        val desc = doc.select(".story p").text()
        
        val poster = doc.selectFirst(".MainSingle .left .image img")?.attr("src")
        
        val bgPoster = doc.selectFirst(".homepage__bg")
            ?.attr("style")
            ?.substringAfter("url(")?.substringBefore(")") 
            ?: poster

        val year = doc.select(".RightTaxContent a[href*='release-year']")
            .text().filter { it.isDigit() }.toIntOrNull()
            
        // إصلاح مشكلة التقييم (Deprecated rating) واستبداله بـ manual parsing
        val ratingText = doc.select(".imdbS strong").text()
        val ratingInt = (ratingText.toDoubleOrNull()?.times(1000))?.toInt()

        val isSeries = url.contains("series") || title.contains("مسلسل") || doc.select(".allepcont").isNotEmpty()

        if (isSeries) {
            val episodes = ArrayList<Episode>()
            doc.select(".allepcont a").forEach { ep ->
                val epTitle = ep.select(".ep-info h2").text()
                val epHref = ep.attr("href")
                val epNum = ep.select(".epnum").text().filter { it.isDigit() }.toIntOrNull()
                val epThumb = ep.select("img").attr("data-src").ifEmpty { ep.select("img").attr("src") }

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epTitle
                        this.episode = epNum
                        this.posterUrl = epThumb
                    }
                )
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = poster
                this.backgroundPoster = bgPoster
                this.plot = desc
                this.year = year
                this.rating = ratingInt // استخدام المتغير المحسوب يدوياً
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPoster = bgPoster
                this.plot = desc
                this.year = year
                this.rating = ratingInt
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

        val iframeCrypt = doc.select("iframe#main-video-frame").attr("data-crypt")
        if (iframeCrypt.isNotEmpty()) {
            try {
                val decodedUrl = String(Base64.decode(iframeCrypt, Base64.DEFAULT))
                // تصحيح ترتيب المتغيرات: (url, subtitleCallback, callback)
                loadExtractor(decodedUrl, subtitleCallback, callback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        doc.select("a.smart-external-link").forEach { downloadLink ->
             val realUrlEncrypted = downloadLink.attr("data-real-url")
             if(realUrlEncrypted.contains("go.php?u=")) {
                 val hash = realUrlEncrypted.substringAfter("u=")
                 try {
                     val decoded = String(Base64.decode(hash, Base64.DEFAULT))
                     // تصحيح ترتيب المتغيرات هنا أيضاً
                     loadExtractor(decoded, subtitleCallback, callback)
                 } catch (e: Exception) {}
             }
        }

        return true
    }
}
