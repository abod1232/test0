package com.lagradost.cloudstream3.arabic

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class TukTukHd : ParsableHttpSource() {

    override val name = "TukTuk Cinema"
    override val mainUrl = "https://tuktukhd.com"
    override val lang = "ar"
    override val hasMainPage = true

    // الصفحة الرئيسية تحتوي على عدة أقسام، سنقوم بجلب "الأحدث" والأقسام المميزة
    override val mainPage = mainPageOf(
        "$mainUrl/recent/page/" to "المضاف حديثاً",
        "$mainUrl/category/movies-2/page/" to "أحدث الأفلام",
        "$mainUrl/category/series-1/page/" to "أحدث الحلقات",
    )

    // تحليل عناصر القائمة (في الصفحة الرئيسية والبحث)
    override fun mainPageOf(data: String, element: Element): List<SearchResponse>? {
        return element.select(".Block--Item").mapNotNull {
            val linkTag = it.selectFirst("a") ?: return@mapNotNull null
            val title = it.selectFirst(".title")?.text() ?: linkTag.attr("title")
            val href = linkTag.attr("href")
            
            // التعامل مع الصور (Lazy Load)
            val imgTag = it.selectFirst(".Poster--Block img")
            val posterUrl = imgTag?.attr("data-src") 
                ?: imgTag?.attr("src") 
                ?: "https://tuktukhd.com/wp-content/themes/TukTukCinema3/no.png"

            // التحقق من نوع المحتوى
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

    // وظيفة البحث
    override suspend fun search(query: String): List<SearchResponse> {
        // الموقع يستخدم ?s=QUERY
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

    // جلب تفاصيل الفيلم أو المسلسل
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.post-title a")?.text() ?: doc.selectFirst("h1")?.text() ?: "Unknown"
        val desc = doc.select(".story p").text()
        val poster = doc.selectFirst(".MainSingle .left .image img")?.attr("src")
        val bgPoster = doc.selectFirst(".homepage__bg")?.attr("style")?.substringAfter("url(")?.substringBefore(")") 
            ?: poster

        val year = doc.select(".RightTaxContent a[href*='release-year']").text().filter { it.isDigit() }.toIntOrNull()
        val rating = doc.select(".imdbS strong").text().toDoubleOrNull()

        // تحديد النوع بناءً على الأقسام أو العنوان
        val isSeries = url.contains("series") || title.contains("مسلسل") || doc.select(".allepcont").isNotEmpty()

        if (isSeries) {
            // جلب الحلقات
            val episodes = ArrayList<Episode>()
            
            // في ملف HTML الخاص بالمسلسلات، الحلقات موجودة داخل .allepcont a
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
            // ترتيب الحلقات
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = poster
                this.backgroundPoster = bgPoster
                this.plot = desc
                this.year = year
                this.rating = rating.toRatingInt()
            }
        } else {
            // فيلم
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPoster = bgPoster
                this.plot = desc
                this.year = year
                this.rating = rating.toRatingInt()
            }
        }
    }

    // استخراج روابط المشاهدة
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // الطريقة 1: البحث عن Iframe الذي يحتوي على data-crypt (مشفر Base64)
        // موجود في tuktukloadmovie.html
        val iframeCrypt = doc.select("iframe#main-video-frame").attr("data-crypt")
        if (iframeCrypt.isNotEmpty()) {
            try {
                // فك التشفير Base64
                val decodedUrl = String(Base64.decode(iframeCrypt, Base64.DEFAULT))
                loadExtractor(decodedUrl, callback, subtitleCallback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // الطريقة 2: قائمة السيرفرات (watch--servers--list)
        // قد تحتوي على روابط مباشرة أو مشفرة أخرى
        doc.select(".watch--servers--list ul li").forEach { server ->
            val dataLink = server.attr("data-link")
            if (dataLink.isNotEmpty()) {
                // ملاحظة: أحياناً data-link يكون مشفراً بطريقة معقدة، لكن إذا كان Base64 بسيط نجرب فكه
                // أو إذا كان هناك طلب AJAX يتم إرساله (Go.php في الملف المرفق)
                // بناءً على الملف، هناك رابط "go.php" يستخدم data-real-url للتحميل، يمكن استخدامه أيضاً
            }
        }
        
        // الطريقة 3: روابط التحميل المباشرة (Megamax في المثال)
        doc.select("a.smart-external-link").forEach { downloadLink ->
             val realUrlEncrypted = downloadLink.attr("data-real-url")
             if(realUrlEncrypted.contains("go.php?u=")) {
                 val hash = realUrlEncrypted.substringAfter("u=")
                 try {
                     val decoded = String(Base64.decode(hash, Base64.DEFAULT))
                     loadExtractor(decoded, callback, subtitleCallback)
                 } catch (e: Exception) {}
             }
        }

        return true
    }
}
