package com.arabseed

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import kotlinx.serialization.Serializable
import com.lagradost.nicehttp.NiceResponse // تأكد من استيراد NiceResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


class Arabseed : MainAPI() {
    override var mainUrl = "https://asd.pics"
    override var name = "Arabseed"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    private fun getPosterHeaders(): Map<String, String> {
        val cookies = android.webkit.CookieManager.getInstance().getCookie(mainUrl) ?: ""
        return mapOf(
            "Cookie" to cookies,
            "User-Agent" to appUserAgent,
            "Referer" to mainUrl
        )
    }
    // User-Agent ثابت لضمان تطابق الجلسة بين التطبيق والـ WebView الذي سيحل كلاودفلير
    // في ملف Arabseed.kt
    // ضعه في بداية كلاس Arabseed
    private val appUserAgent = CloudflareSolver.EXACT_USER_AGENT
    private val solverMutex = Mutex()
    private fun String.toAbsolute(): String {
        if (this.isBlank()) return ""
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            else -> mainUrl.trimEnd('/') + this
        }
    }

    // ================== نظام تخطي كلاودفلير (التعديل الجديد) ==================

    // محاولة جلب الـ Activity النشط حالياً لتمريره لـ CloudflareSolver
    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun getCurrentActivity(): Activity? {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            val activities = activitiesField.get(activityThread) as? Map<*, *> ?: return null
            for (activityRecord in activities.values) {
                val activityRecordClass = activityRecord!!.javaClass
                val pausedField = activityRecordClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                if (!pausedField.getBoolean(activityRecord)) {
                    val activityField = activityRecordClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    return activityField.get(activityRecord) as Activity
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Failed to get current Activity via reflection: ${e.message}")
        }
        return null
    }

    private fun isCloudflareBlock(code: Int, text: String): Boolean {
        // كلاودفلير عادة يرجع 503 أو 403 للتحدي
        if (code in listOf(403, 503, 429)) return true
        val lowerText = text.lowercase()
        // التحقق من وجود كلمات كلاودفلير في الصفحة في حال رجع 200 OK
        return lowerText.contains("cloudflare") && lowerText.contains("checking your browser") ||
                lowerText.contains("just a moment") ||
                lowerText.contains("cf-browser-verification")
    }

    private suspend fun applyCookiesAndAgent(url: String, originalHeaders: Map<String, String>): Map<String, String> {
        val newHeaders = originalHeaders.toMutableMap()
        val cookies = CookieManager.getInstance().getCookie(url)
        if (!cookies.isNullOrBlank()) {
            newHeaders["Cookie"] = cookies
        }
        newHeaders["User-Agent"] = appUserAgent
        return newHeaders
    }

    private suspend fun safeGet(url: String, referer: String? = null, headers: Map<String, String> = emptyMap()): NiceResponse {
        var currentHeaders = applyCookiesAndAgent(url, headers)
        var response = app.get(url, referer = referer, headers = currentHeaders, allowRedirects = true)

        if (isCloudflareBlock(response.code, response.text)) {
            Log.w(name, "Cloudflare detected on GET: $url (Code: ${response.code}). Waiting for lock...")

            // استخدام القفل لمنع فتح السولفر أكثر من مرة في نفس الوقت
            solverMutex.withLock {
                // بعد الدخول للقفل، نتحقق مرة أخرى: هل قام طلب آخر بحل المشكلة أثناء انتظارنا؟
                currentHeaders = applyCookiesAndAgent(url, headers)
                response = app.get(url, referer = referer, headers = currentHeaders, allowRedirects = true)

                // إذا لا يزال محظوراً، نفتح السولفر
                if (isCloudflareBlock(response.code, response.text)) {
                    Log.w(name, "Still blocked. Triggering Solver for GET...")
                    val activity = getCurrentActivity()
                    if (activity != null) {
                        CloudflareSolver.solve(activity, url)
                        // بعد الحل، نحدث الكوكيز ونجرب الطلب النهائي
                        currentHeaders = applyCookiesAndAgent(url, headers)
                        response = app.get(url, referer = referer, headers = currentHeaders, allowRedirects = true)
                    } else {
                        Log.e(name, "Cannot solve Cloudflare: Activity is null")
                    }
                } else {
                    Log.i(name, "Cloudflare already solved by another concurrent request. Continuing GET.")
                }
            }
        }
        return response
    }

    private suspend fun safePost(url: String, data: Map<String, String>, referer: String? = null, headers: Map<String, String> = emptyMap()): NiceResponse {
        var currentHeaders = applyCookiesAndAgent(url, headers)
        var response = app.post(url, data = data, referer = referer, headers = currentHeaders, allowRedirects = true)

        if (isCloudflareBlock(response.code, response.text)) {
            Log.w(name, "Cloudflare detected on POST: $url (Code: ${response.code}). Waiting for lock...")

            // استخدام القفل لمنع فتح السولفر أكثر من مرة في نفس الوقت
            solverMutex.withLock {
                // بعد الدخول للقفل، نتحقق مرة أخرى: هل قام طلب آخر بحل المشكلة أثناء انتظارنا؟
                currentHeaders = applyCookiesAndAgent(url, headers)
                response = app.post(url, data = data, referer = referer, headers = currentHeaders, allowRedirects = true)

                // إذا لا يزال محظوراً، نفتح السولفر
                if (isCloudflareBlock(response.code, response.text)) {
                    Log.w(name, "Still blocked. Triggering Solver for POST...")
                    val activity = getCurrentActivity()
                    if (activity != null) {
                        CloudflareSolver.solve(activity, url)
                        // بعد الحل، نحدث الكوكيز ونجرب الطلب النهائي
                        currentHeaders = applyCookiesAndAgent(url, headers)
                        response = app.post(url, data = data, referer = referer, headers = currentHeaders, allowRedirects = true)
                    } else {
                        Log.e(name, "Cannot solve Cloudflare: Activity is null")
                    }
                } else {
                    Log.i(name, "Cloudflare already solved by another concurrent request. Continuing POST.")
                }
            }
        }
        return response
    }
    // ================== البحث ==================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/find/?word=${query.trim().replace(" ", "+")}"
        // استخدام safeGet بدلاً من app.get
        val document = safeGet(url).document
        return document.select("ul.blocks__ul > li").amap {
            val a = it.selectFirst("a.movie__block") ?: return@amap null
            val href = a.attr("href").toAbsolute()
            val title = a.attr("title").ifBlank { a.selectFirst("h3")?.text() } ?: return@amap null
            val posterUrl = a.selectFirst("img")?.let { img ->
                (img.attr("data-src").ifBlank { img.attr("src") }).toAbsolute()
            }
            val isMovie = href.contains("/%d9%81%d9%8a%d9%84%d9%85-") // /فيلم-
            val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
                this.posterHeaders = getPosterHeaders()
            }
        }.filterNotNull()
    }

    // ================== الصفحة الرئيسية ==================
    override val mainPage = mainPageOf(
        "$mainUrl/main0/" to "الرئيسية",
        "$mainUrl/main0/" to "الرئيسية",
        "$mainUrl/recently/" to "مضاف حديثا",
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/main0/" to "المسلسلات",
        "$mainUrl/category/افلام-انيميشن/" to "افلام انيميشن",
        "$mainUrl/category/cartoon-series/" to "مسلسلات كرتون",
        "$mainUrl/category/arabic-series-2/" to "مسلسلات عربي",
        "$mainUrl/category/arabic-movies-6/" to "افلام عربي",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        // استخدام safeGet بدلاً من app.get
        val document = safeGet(url).document
        val items = document.select(".movie__block").amap {
            val title = it.selectFirst("h3")?.text() ?: return@amap null
            val href = it.attr("href").toAbsolute()
            val posterUrl = it.selectFirst("img")?.let { img ->
                (img.attr("data-src").ifBlank { img.attr("src") }).toAbsolute()
            }
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.posterHeaders = getPosterHeaders()
            }
        }.filterNotNull()
        return newHomePageResponse(request.name, items)
    }

    @Serializable
    data class AjaxResponse(
        val html: String?,
        val hasmore: Boolean?
    )

    // ================== تفاصيل ==================
    override suspend fun load(url: String): LoadResponse {
        Log.i(name, "================ LOAD START ================ ")
        Log.d(name, "load() initiated with URL: $url")

        // استخدام safeGet بدلاً من app.get
        val doc = safeGet(url).document

        // استخلاص الرابط الرئيسي للمسلسل من breadcrumbs
        val seriesUrl = doc.select(".bread__crumbs li a[href*='/selary/']")
            .lastOrNull {
                val href = it.attr("href")
                !href.contains("/%d8%a7%d9%84%d9%85%d9%88%d8%b3%d9%85-") && !href.contains("/%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-")
            }
            ?.attr("href")?.toAbsolute()
            ?: url.substringBefore("/%d8%a7%d9%84%d9%85%d9%88%d8%b3%d9%85-").substringBefore("/%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-").toAbsolute()

        Log.d(name, "Determined base series URL: $seriesUrl")

        val seriesDoc = if (seriesUrl != url && seriesUrl.isNotBlank()) {
            Log.d(name, "Current URL is not the base series URL. Fetching base URL page...")
            // استخدام safeGet بدلاً من app.get
            safeGet(seriesUrl).document
        } else {
            doc
        }

        val title = seriesDoc.selectFirst("h1.post__name")?.text()?.trim()
            ?: doc.selectFirst("h1.post__name")?.text()?.trim()
            ?: "Title Not Found"

        val poster = seriesDoc.selectFirst(".poster__single img, .single__cover > img:not(.rating__box img), .post__poster img")?.let { img ->
            (img.attr("data-src").ifBlank { img.attr("src") }).toAbsolute()
        }

        val synopsis = seriesDoc.selectFirst(".post__story > p")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        val seasonElements = seriesDoc.select("div#seasons__list ul li")

        if (seasonElements.isNotEmpty()) {
            Log.i(name, "MODE: Multi-season series detected. Found ${seasonElements.size} seasons.")
            val csrfToken = seriesDoc.select("script").html()
                .let { Regex("""'csrf__token':\s*"([^"]+)""").find(it)?.groupValues?.get(1) }

            if (csrfToken.isNullOrBlank()) {
                Log.e(name, "CRITICAL: CSRF token not found. Cannot fetch seasons.")
            } else {
                seasonElements.forEachIndexed { index, seasonEl ->
                    val seasonId = seasonEl.attr("data-term").trim()
                    val seasonName = seasonEl.selectFirst("span")?.text()?.trim()

                    if (seasonId.isBlank()) return@forEachIndexed

                    val seasonNum = index + 1
                    val currentSeasonEpisodes = mutableListOf<Episode>()
                    var hasMore = true
                    var currentOffset = 0

                    while (hasMore) {
                        try {
                            // استخدام safePost بدلاً من app.post لتمرير التحديات في الـ AJAX أيضاً
                            val response = safePost(
                                "$mainUrl/season__episodes/",
                                data = mapOf(
                                    "season_id" to seasonId,
                                    "offset" to currentOffset.toString(),
                                    "csrf_token" to csrfToken
                                ),
                                referer = seriesUrl,
                                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                            ).parsedSafe<AjaxResponse>()

                            if (response?.html.isNullOrBlank()) {
                                hasMore = false
                            } else {
                                val newEpisodesDoc = Jsoup.parse(response.html)
                                val newEpisodeElements = newEpisodesDoc.select("li a")

                                if (newEpisodeElements.isEmpty()) {
                                    hasMore = false
                                } else {
                                    newEpisodeElements.forEach { epEl ->
                                        val epHref = epEl.attr("href").toAbsolute()
                                        val epTitle = epEl.selectFirst(".epi__num")?.text()?.trim() ?: epEl.text().trim()
                                        val epNum = epTitle.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

                                        currentSeasonEpisodes.add(newEpisode(epHref) {
                                            name = epTitle
                                            episode = epNum
                                            season = seasonNum
                                            posterUrl = poster?.takeIf { it.isNotBlank() }

                                        })
                                    }
                                    currentOffset += newEpisodeElements.size
                                    hasMore = response.hasmore == true
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(name, "  <- AJAX season fetch FAILED for seasonId: $seasonId", e)
                            hasMore = false
                        }
                    }
                    episodes.addAll(currentSeasonEpisodes.reversed())
                }
            }
        } else {
            val seasonNumFromName = doc.selectFirst(".bread__crumbs li:contains(الموسم) span")?.text()?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() } ?: 1
            doc.select("ul.episodes__list li a").forEach { epEl ->
                val epHref = epEl.attr("href").toAbsolute()
                val epTitle = epEl.selectFirst(".epi__num")?.text()?.trim() ?: epEl.text().trim()
                val epNum = epTitle.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
                episodes.add(newEpisode(epHref) {
                    this.name = epTitle
                    this.episode = epNum
                    season = seasonNumFromName
                    posterUrl = poster?.takeIf { it.isNotBlank() }
                })
            }
        }

        val isTvSeries = episodes.isNotEmpty() || seriesUrl.contains("/selary/")

        if (isTvSeries) {
            return newTvSeriesLoadResponse(
                title,
                seriesUrl,
                TvType.TvSeries,
                episodes.distinctBy { it.data }
            ) {
                this.posterHeaders = getPosterHeaders()
                this.posterUrl = poster?.takeIf { it.isNotBlank() }
                this.plot = synopsis
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster?.takeIf { it.isNotBlank() }
                this.plot = synopsis
                this.posterHeaders = getPosterHeaders()
            }
        }
    }

    @Serializable
    data class ServerResponse(val server: String?)

    // ... (لا تنسى إبقاء الاستيرادات الموجودة في ملفك) ...

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val episodePageDoc = safeGet(data).document
        val watchUrl = episodePageDoc.selectFirst("a.btton.watch__btn")?.attr("href")?.toAbsolute()
            ?: return false

        val watchPageDoc = safeGet(watchUrl, referer = data).document

        val csrfToken = watchPageDoc.select("script").html()
            .let { Regex("""'csrf__token':\s*"([^"]+)""").find(it)?.groupValues?.get(1) }
            ?: return true
        val postId = watchPageDoc.selectFirst(".servers__list li")?.attr("data-post") ?: return true

        // جلب قائمة الجودات المتاحة
        val qualityElements = watchPageDoc.select(".quality__swither ul.qualities__list li")
        if (qualityElements.isEmpty()) return true

        // ---------------------------------------------------------
        // الخطوة الجديدة: إرسال طلب تجريبي لمعرفة الرابط النهائي (rightLink)
        // ---------------------------------------------------------
        var rightLink = mainUrl // القيمة الافتراضية

        // نأخذ الجودة الأولى فقط لنستخدمها في الطلب التجريبي
        val testQuality = qualityElements.first()?.attr("data-quality") ?: ""

        if (testQuality.isNotBlank()) {
            try {
                // إرسال الطلب التجريبي
                val testResponse = safePost(
                    "$mainUrl/get__quality__servers/",
                    data = mapOf("post_id" to postId, "quality" to testQuality, "csrf_token" to csrfToken),
                    referer = watchUrl,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                )

                // testResponse.url يحتوي على الرابط النهائي بعد أي إعادة توجيه (Redirect)
                val finalResolvedUrl = testResponse.url

                // استخراج البروتوكول والنطاق فقط (مثال: https://m.asd.ink)
                val urlObj = java.net.URL(finalResolvedUrl)
                rightLink = "${urlObj.protocol}://${urlObj.host}"

                Log.d("Arabseed", "Test request resolved to rightLink: $rightLink")
            } catch (e: Exception) {
                // في حال فشل الطلب التجريبي، نبقى على القيمة الافتراضية
                Log.d("Arabseed", "Test request failed: ${e.message}")
            }
        }
        // ---------------------------------------------------------

        // الآن نقوم بالعمليات الأساسية مستخدمين rightLink الذي جلبناه
        qualityElements.amap { qualityElement ->
            val quality = qualityElement.attr("data-quality")

            safePost(
                "$rightLink/get__quality__servers/", // استخدام rightLink هنا
                data = mapOf("post_id" to postId, "quality" to quality, "csrf_token" to csrfToken),
                referer = watchUrl,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<AjaxResponse>()?.html?.let { html ->
                Jsoup.parse(html).select("li").amap { serverElement ->
                    val serverId = serverElement.attr("data-server")
                    val postData = mapOf("post_id" to postId, "quality" to quality, "server" to serverId, "csrf_token" to csrfToken)

                    try {
                        val response = safePost(
                            "$rightLink/get__watch__server/", // استخدام rightLink هنا أيضاً
                            data = postData,
                            referer = watchUrl,
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                        )

                        val serverResponse = response.parsedSafe<ServerResponse>()

                        serverResponse?.server?.let { iframeUrl ->
                            if(iframeUrl.isNotBlank()) {
                                val urlWithQuality = "$iframeUrl#quality=$quality"
                                loadExtractor(urlWithQuality, watchUrl, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        }
        return true
    }

}