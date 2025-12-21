
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

    return newHomePageResponse(homePageList)
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

}
