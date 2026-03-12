package com.lagradost.cloudstream3.plugins

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Request as OkRequest
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.net.Uri
import android.widget.LinearLayout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.HttpURLConnection
import java.net.URL
import android.webkit.JavascriptInterface
import android.widget.ScrollView
import android.webkit.WebChromeClient
import android.view.View
import android.webkit.ConsoleMessage
import android.graphics.Bitmap
import java.io.InputStream
class CimaWbas(private val context: Context) : MainAPI() {
    override var name = "FASELHD"
    override var mainUrl = "https://www.faselhds.biz"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        mainUrl to "الرئيسية",
        "$mainUrl/movies" to "أفلام أجنبية",
        "$mainUrl/series" to "مسلسلات أجنبية",
        "$mainUrl/hindi" to "أفلام هندي",
        "$mainUrl/asian-movies" to "أفلام آسيوية",
        "$mainUrl/anime" to "أنمي",
        "$mainUrl/anime-movies" to "أفلام أنمي"
    )

    private val cfLock = Mutex()
    private var lastValidUserAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun getProtectedHeaders(): Map<String, String> {
        val cm = CookieManager.getInstance()
        val cookies = cm.getCookie(mainUrl) ?: ""
        return mapOf(
            "Cookie" to cookies,
            "User-Agent" to lastValidUserAgent,
            "Referer" to mainUrl
        )
    }

    @SuppressLint("SetTextI18n")
    private suspend fun fetchCookiesWithTrustedWebView(
        url: String,
        timeoutMs: Long = 60000L
    ): String? = suspendCoroutine { cont ->
        Handler(Looper.getMainLooper()).post {
            val activity = context as? Activity
            if (activity == null || activity.isFinishing) {
                cont.resume(null)
                return@post
            }

            // إعداد Dialog مخفي تماماً
            val dialog = Dialog(activity)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCancelable(false)

            dialog.window?.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )


            // جعل الخلفية شفافة وإزالة التعتيم
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setDimAmount(0f)

            // تعيين الحجم إلى 1x1 بكسل ووضعه في الزاوية (خارج الرؤية عملياً)
            val params = WindowManager.LayoutParams()
            params.copyFrom(dialog.window?.attributes)
            params.width = 1
            params.height = 1
            params.gravity = Gravity.TOP or Gravity.START
            params.x = -100 // خارج الشاشة
            params.y = -100 // خارج الشاشة
            dialog.window?.attributes = params

            // WebView صغير جداً
            val webView = WebView(activity)
            dialog.setContentView(
                webView,
                ViewGroup.LayoutParams(1, 1)
            )

            try {
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = lastValidUserAgent
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    blockNetworkImage = false // الصور مهمة أحياناً للتحقق
                    loadsImagesAutomatically = true
                    javaScriptCanOpenWindowsAutomatically = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
                lastValidUserAgent = webView.settings.userAgentString
            } catch (_: Exception) {
            }

            val cookieManager = CookieManager.getInstance()
            try {
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)
            } catch (_: Exception) {
            }

            var finished = false

            fun finish(result: String?) {
                if (finished) return
                finished = true
                try {
                    cookieManager.flush()
                } catch (_: Exception) {
                }
                try {
                    if (dialog.isShowing) dialog.dismiss()
                } catch (_: Exception) {
                }
                try {
                    webView.stopLoading()
                } catch (_: Exception) {
                }
                try {
                    webView.destroy()
                } catch (_: Exception) {
                }
                try {
                    cont.resume(result)
                } catch (_: Exception) {
                }
            }

            // لا يوجد زر إغلاق لأن النافذة مخفية، نعتمد على التايمر والتحقق
            // dialog.setOnDismissListener { if (!finished) finish(cookieManager.getCookie(url)) }

            val startTime = System.currentTimeMillis()
            val handler = Handler(Looper.getMainLooper())

            val cookieChecker = object : Runnable {
                override fun run() {
                    if (finished) return
                    val currentCookies = try {
                        cookieManager.getCookie(url)
                    } catch (e: Exception) {
                        ""
                    } ?: ""

                    if (currentCookies.contains("cf_clearance")) {
                        Log.d("FASELHD", "Silent Cloudflare Bypass Successful!")
                        handler.postDelayed({ finish(currentCookies) }, 2500)
                        return
                    }

                    if (System.currentTimeMillis() - startTime > timeoutMs) {
                        finish(null)
                        return
                    }
                    handler.postDelayed(this, 1000)
                }
            }

            handler.postDelayed(cookieChecker, 1000)
            webView.webViewClient = object : WebViewClient() {}

            try {
                dialog.show()
                webView.loadUrl(url)
            } catch (e: Exception) {
                finish(null)
                return@post
            }
        }
    }

    private suspend fun smartGet(
        url: String,
        referer: String? = null,
        timeoutSeconds: Long? = null
    ): Document {
        try {
            val normalDoc = if (referer != null) {
                app.get(url, referer = referer, timeout = timeoutSeconds ?: 0L).document
            } else {
                app.get(url, timeout = timeoutSeconds ?: 0L).document
            }
            val title = normalDoc.select("title").text()
            val bodyText = normalDoc.body()?.text() ?: ""
            val html = normalDoc.html() ?: ""
            val looksLikeCF = title.contains("Just a moment", ignoreCase = true) ||
                    bodyText.contains("Just a moment", ignoreCase = true) ||
                    bodyText.contains("checking your browser", ignoreCase = true) ||
                    html.contains("cf-turnstile") ||
                    html.contains("challenge-platform")
            if (!looksLikeCF) return normalDoc
        } catch (e: Exception) {
        }

        val cookies = cfLock.withLock {
            val cm = CookieManager.getInstance()
            val existingCookies = cm.getCookie(mainUrl) ?: ""
            if (existingCookies.contains("cf_clearance")) {
                existingCookies
            } else {
                fetchCookiesWithTrustedWebView(url, timeoutMs = 60_000L)
            }
        }

        if (!cookies.isNullOrBlank()) {
            val headers = mutableMapOf(
                "Cookie" to cookies,
                "User-Agent" to lastValidUserAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
                "Upgrade-Insecure-Requests" to "1",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "none",
                "Sec-Fetch-User" to "?1"
            )
            if (!referer.isNullOrBlank()) headers["Referer"] = referer

            return try {
                val reqBuilder = OkRequest.Builder().url(url)
                headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

                val client = app.baseClient.newBuilder()
                    .cookieJar(CookieJar.NO_COOKIES)
                    .build()

                val okResp = client.newCall(reqBuilder.build()).execute()

                val bodyStr = okResp.body?.string() ?: ""
                Jsoup.parse(bodyStr, url)
            } catch (ee: Exception) {
                Jsoup.parse("", url)
            }
        }
        return Jsoup.parse("", url)
    }

    private suspend fun smartPost(
        url: String,
        referer: String? = null,
        timeoutSeconds: Long? = null
    ): Document {
        try {
            val normalDoc = if (referer != null) {
                app.post(url, referer = referer, timeout = timeoutSeconds ?: 0L).document
            } else {
                app.post(url, timeout = timeoutSeconds ?: 0L).document
            }
            if (!normalDoc.text().contains("Just a moment", ignoreCase = true)) return normalDoc
        } catch (_: Exception) {
        }

        val cookies = cfLock.withLock {
            val cm = CookieManager.getInstance()
            val existingCookies = cm.getCookie(mainUrl) ?: ""
            if (existingCookies.contains("cf_clearance")) {
                existingCookies
            } else {
                fetchCookiesWithTrustedWebView(url, timeoutMs = 60_000L)
            }
        }

        if (!cookies.isNullOrBlank()) {
            val headers = mutableMapOf(
                "Cookie" to cookies,
                "User-Agent" to lastValidUserAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9,ar;q=0.8",
                "Upgrade-Insecure-Requests" to "1"
            )
            if (!referer.isNullOrBlank()) headers["Referer"] = referer

            return try {
                val reqBuilder = OkRequest.Builder()
                    .url(url)
                    .post(FormBody.Builder().build())

                headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

                val client = app.baseClient.newBuilder()
                    .cookieJar(CookieJar.NO_COOKIES)
                    .build()

                val okResp = client.newCall(reqBuilder.build()).execute()
                val bodyStr = okResp.body?.string() ?: ""
                Jsoup.parse(bodyStr, url)
            } catch (e: Exception) {
                Jsoup.parse("", url)
            }
        }
        return Jsoup.parse("", url)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href")?.trim() ?: return null
        val title = this.selectFirst(".h1, .h4, .h5")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("img")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.trim()
        if (href.isBlank() || title.isBlank()) return null

        val headers = getProtectedHeaders()

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
            this.posterHeaders = headers
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1 && request.data != mainUrl) {
            if (request.data.contains("all_movies"))
                "${request.data.removeSuffix("/")}/page/$page"
            else
                "${request.data}/page/$page"
        } else {
            request.data
        }

        val document = smartGet(url)
        val headers = getProtectedHeaders()

        if (request.data == mainUrl) {
            val lists = mutableListOf<HomePageList>()
            val sliderItems = document.select("#homeSlide .swiper-slide").mapNotNull {
                val slideHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val slideTitle = it.selectFirst(".h1 a")?.text()?.trim() ?: return@mapNotNull null
                val slidePoster = it.selectFirst(".poster img")?.attr("src")
                newMovieSearchResponse(slideTitle, slideHref, TvType.Movie) {
                    this.posterUrl = slidePoster
                    this.posterHeaders = headers
                }
            }
            if (sliderItems.isNotEmpty()) {
                lists.add(HomePageList("أحدث الإضافات", sliderItems, isHorizontalImages = true))
            }

            document.select("div.slider")
                .firstOrNull { it.selectFirst(".h4")?.text()?.contains("مشاهدة") == true }
                ?.let { mostWatchedBlock ->
                    val title =
                        mostWatchedBlock.selectFirst(".h4")?.text() ?: "الأفلام الأكثر مشاهدة"
                    val items = mostWatchedBlock.select(".itemviews .postDiv")
                        .mapNotNull { it.toSearchResult() }
                    if (items.isNotEmpty()) {
                        lists.add(HomePageList(title, items, isHorizontalImages = true))
                    }
                }

            document.select("section#blockList").forEach { block ->
                val title = block.selectFirst(".blockHead .h3")?.text() ?: return@forEach
                if (!title.contains("آخر الأفلام المضافة")) {
                    val items = block.select(".blockMovie, .postDiv, .epDivHome")
                        .mapNotNull { it.toSearchResult() }
                    if (items.isNotEmpty()) {
                        lists.add(HomePageList(title, items))
                    }
                }
            }
            return HomePageResponse(lists.filter { it.list.isNotEmpty() }, hasNext = false)
        } else {
            val items = document.select(".postDiv, .blockMovie").mapNotNull { it.toSearchResult() }
            val hasNext = document.select("ul.pagination a[href*='/page/${page + 1}']").isNotEmpty()
            return newHomePageResponse(request.name, items, hasNext)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = if (page == 1) {
            "$mainUrl/?s=$encoded"
        } else {
            "$mainUrl/page/$page/?s=$encoded"
        }
        val document = smartGet(searchUrl)
        val items = document.select("div#postList div.postDiv").mapNotNull { it.toSearchResult() }
        val hasNext = document.select("ul.pagination a[href*='/page/${page + 1}']").isNotEmpty()
        return newSearchResponseList(items, hasNext)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = smartGet(url)
        val title = doc.selectFirst(".singleInfo .title.h1")?.ownText()?.trim() ?: return null
        val poster = fixUrlNull(
            doc.selectFirst("meta[itemprop=image]")?.attr("content")
                ?: doc.selectFirst(".posterImg img.poster")?.attr("src")
        )
        val plot = doc.selectFirst(".singleDesc p, .story p")?.text()?.trim()
        val backgroundPoster = doc.selectFirst("div.singlePage")?.attr("style")
            ?.let { Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1) }
            ?.let { fixUrlNull(it) }

        var year: Int? = null
        val tagsList = mutableListOf<String>()
        doc.select("#singleList > div").forEach {
            val text = it.text()
            when {
                text.contains("سنة الإنتاج") -> year = it.selectFirst("a")?.text()?.toIntOrNull()
                text.contains("تصنيف") -> tagsList.addAll(
                    it.select("a").map { tagEl -> tagEl.text() })
            }
        }

        val headers = getProtectedHeaders()

        val seasonCards = doc.select(".seasonDiv")
        val seasonUrlRegex = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""")
        val recommendations = seasonCards.mapNotNull { seasonEl ->
            val onclickAttr = seasonEl.attr("onclick")
            val seasonUrlRel =
                seasonUrlRegex.find(onclickAttr)?.groupValues?.get(1) ?: return@mapNotNull null
            val seasonTitle = seasonEl.selectFirst(".title")?.text() ?: "موسم"
            val seasonPoster =
                seasonEl.selectFirst("img")?.attr("data-src") ?: seasonEl.selectFirst("img")
                    ?.attr("src")
            newTvSeriesSearchResponse(seasonTitle, fixUrl(seasonUrlRel), TvType.TvSeries) {
                this.posterUrl = seasonPoster
                this.posterHeaders = headers
            }
        }

        data class SeasonTask(val name: String, val url: String, val poster: String?)

        val seasonTasks = mutableListOf<SeasonTask>()
        seasonCards.forEachIndexed { idx, seasonEl ->
            val onclickAttr = seasonEl.attr("onclick")
            val seasonUrlRel = seasonUrlRegex.find(onclickAttr)?.groupValues?.get(1)
            if (!seasonUrlRel.isNullOrBlank()) {
                val seasonUrl = fixUrl(seasonUrlRel)
                val seasonName =
                    seasonEl.selectFirst(".title")?.text()?.trim() ?: "الموسم ${idx + 1}"
                val seasonPoster =
                    seasonEl.selectFirst("img")?.attr("data-src") ?: seasonEl.selectFirst("img")
                        ?.attr("src")
                seasonTasks.add(SeasonTask(seasonName, seasonUrl, seasonPoster))
            }
        }

        val allEpisodes = mutableListOf<Episode>()

        if (seasonTasks.isNotEmpty()) {
            val semaphore = Semaphore(5)
            try {
                val results: List<Pair<Int, List<Episode>>> = coroutineScope {
                    seasonTasks.mapIndexed { idx, task ->
                        async(Dispatchers.IO) {
                            semaphore.acquire()
                            try {
                                val seasonDoc = smartGet(task.url)
                                val episodeElements = seasonDoc.select("div#epAll a")
                                val eps = mutableListOf<Episode>()
                                val seasonPosterUrl = task.poster?.let { fixUrlNull(it) } ?: poster

                                if (episodeElements.isNotEmpty()) {
                                    episodeElements.forEach { el ->
                                        val epUrlRaw = el.attr("href").trim()
                                        if (epUrlRaw.isNotBlank()) {
                                            val epTitle = el.ownText().ifBlank { el.text() }.trim()
                                            if (!epTitle.contains("باقي الحلقات") && !epTitle.contains(
                                                    "المزيد"
                                                )
                                            ) {
                                                val epNum =
                                                    Regex("""\d+""").find(epTitle)?.value?.toIntOrNull()
                                                eps.add(newEpisode(fixUrl(epUrlRaw)) {
                                                    name = epTitle
                                                    episode = epNum
                                                    season = idx + 1
                                                    posterUrl = seasonPosterUrl
                                                })
                                            }
                                        }
                                    }
                                } else {
                                    val fallback = seasonDoc.select("a[href]").mapNotNull { a ->
                                        val h = a.attr("href").trim()
                                        val text = a.ownText().ifBlank { a.text() }.trim()
                                        if (h.isBlank()) return@mapNotNull null
                                        if (text.contains("حلقة") || h.contains("/?p=") || h.contains(
                                                "/?ep="
                                            ) || h.contains("/episode-")
                                        ) {
                                            val epNum =
                                                Regex("""\d+""").find(text)?.value?.toIntOrNull()
                                            newEpisode(fixUrl(h)) {
                                                name = text
                                                episode = epNum
                                                season = idx + 1
                                                posterUrl = seasonPosterUrl
                                            }
                                        } else null
                                    }
                                    eps.addAll(fallback)
                                }
                                Pair(idx, eps.toList())
                            } catch (e: Exception) {
                                Pair(idx, emptyList())
                            } finally {
                                semaphore.release()
                            }
                        }
                    }.awaitAll()
                }
                results.sortedBy { it.first }.forEach { (_, eps) -> allEpisodes.addAll(eps) }
            } catch (e: Exception) {
            }
        } else {
            val seasonNumFromName = doc.selectFirst(".singleInfo .title.h1")?.text()
                ?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() } ?: 1
            val eps = doc.select("div#epAll a").mapNotNull { el ->
                val epUrl = el.attr("href").trim()
                if (epUrl.isBlank()) return@mapNotNull null
                val epName = el.ownText().ifBlank { el.text() }.trim()
                if (epName.contains("باقي الحلقات") || epName.contains("المزيد")) return@mapNotNull null
                val epNum = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
                newEpisode(fixUrl(epUrl)) {
                    name = epName
                    episode = epNum
                    season = seasonNumFromName
                    posterUrl = poster
                }
            }
            allEpisodes.addAll(eps)
        }

        return if (allEpisodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
                this.posterUrl = poster
                this.posterHeaders = headers
                this.backgroundPosterUrl = backgroundPoster
                this.year = year
                this.plot = plot
                this.tags = tagsList
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = headers
                this.backgroundPosterUrl = backgroundPoster
                this.year = year
                this.plot = plot
                this.tags = tagsList
                this.recommendations = recommendations
            }
        }
    }



    private fun extractIframeSources(doc: Document): List<String> {
        val results = mutableSetOf<String>()

        Log.d("FASELHD_IFRAME", "========== START iframe extraction ==========")

        // 🧪 معلومات عامة
        Log.d("FASELHD_IFRAME", "Document URL: ${doc.baseUri()}")
        Log.d("FASELHD_IFRAME", "HTML length: ${doc.html().length}")

        // ===============================
        // 1️⃣ iframe مباشر
        // ===============================
        val iframeEls = doc.select("iframe[src]")
        Log.d("FASELHD_IFRAME", "Direct iframe count: ${iframeEls.size}")

        iframeEls.forEachIndexed { i, el ->
            val src = el.attr("src")
            Log.d("FASELHD_IFRAME", "iframe[$i] src = $src")
            if (src.isNotBlank()) {
                results.add(fixUrl(src))
            }
        }

        // ===============================
        // 2️⃣ onclick
        // ===============================
        val onClickRegex =
            Regex("""player_iframe\.location\.href\s*=\s*['"]([^'"]+)['"]""")

        val onclickEls = doc.select("[onclick]")
        Log.d("FASELHD_IFRAME", "Elements with onclick: ${onclickEls.size}")

        onclickEls.forEachIndexed { i, el ->
            val onclick = el.attr("onclick")
            Log.d("FASELHD_IFRAME", "onclick[$i] = $onclick")

            val match = onClickRegex.find(onclick)
            if (match != null) {
                val url = match.groupValues[1]
                Log.d("FASELHD_IFRAME", "✔ onclick MATCH → $url")
                results.add(fixUrl(url))
            }
        }

        // ===============================
        // 3️⃣ script
        // ===============================
        val scriptRegex = Regex("""https?://[^\s"'<>]+""")
        val scripts = doc.select("script")

        Log.d("FASELHD_IFRAME", "Script tags count: ${scripts.size}")

        scripts.forEachIndexed { i, s ->
            val data = s.data()
            if (data.isBlank()) return@forEachIndexed

            Log.d("FASELHD_IFRAME", "script[$i] length = ${data.length}")

            scriptRegex.findAll(data).forEach { m ->
                val url = m.value
                Log.d("FASELHD_IFRAME", "script[$i] found url = $url")

                if (url.contains("player") || url.contains("embed")) {
                    Log.d("FASELHD_IFRAME", "✔ script MATCH → $url")
                    results.add(fixUrl(url))
                }
            }
        }

        // ===============================
        // 4️⃣ shortLink / liskSh
        // ===============================
        val shortEls = doc.select("div.shortLink, span#liskSh, a[data-src]")
        Log.d("FASELHD_IFRAME", "ShortLink elements count: ${shortEls.size}")

        shortEls.forEachIndexed { i, el ->
            val text = el.text().trim()
            Log.d("FASELHD_IFRAME", "short[$i] text = $text")

            if (text.startsWith("http")) {
                Log.d("FASELHD_IFRAME", "✔ shortLink MATCH → $text")
                results.add(fixUrl(text))
            }
        }

        // ===============================
        // النتيجة النهائية
        // ===============================
        Log.d("FASELHD_IFRAME", "========== RESULT ==========")
        results.forEachIndexed { i, url ->
            Log.d("FASELHD_IFRAME", "FINAL[$i] = $url")
        }

        Log.d(
            "FASELHD_IFRAME",
            "Total iframe/player URLs found: ${results.size}"
        )

        Log.d("FASELHD_IFRAME", "========== END iframe extraction ==========")

        return results.toList()
    }






    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveWithWebView(
        iframeUrl: String,
        referer: String
    ): String? = suspendCancellableCoroutine { cont ->

        val activity = context as? Activity
        if (activity == null || activity.isFinishing) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        activity.runOnUiThread {
            // === Headless / invisible Dialog + WebView setup ===
            val dialog = Dialog(activity)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCancelable(false)
            // make dialog fully non-interactive and transparent
            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
            }

            // Create a single-pixel WebView (invisible)
            val webView = WebView(activity).apply {
                layoutParams = ViewGroup.LayoutParams(1, 1)
                visibility = View.INVISIBLE
                isHorizontalScrollBarEnabled = false
                isVerticalScrollBarEnabled = false
            }

            // Put the WebView into the dialog as its content (so it's attached to window)
            try {
                dialog.setContentView(webView, ViewGroup.LayoutParams(1, 1))
                // move the window far off-screen to be extra-safe (some OEMs may still show)
                dialog.window?.attributes = dialog.window?.attributes?.apply {
                    width = 1
                    height = 1
                    x = -10000
                    y = -10000
                    gravity = Gravity.START or Gravity.TOP
                }
                dialog.show()
            } catch (e: Exception) {
                // fallback: attach to activity content view
                try {
                    val decor = activity.window?.decorView as? ViewGroup
                    decor?.addView(webView, FrameLayout.LayoutParams(1, 1, Gravity.START or Gravity.TOP))
                } catch (_: Exception) { }
            }

            // WebView settings
            val settings = webView.settings
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowContentAccess = true
                allowFileAccess = true
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportMultipleWindows(true)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = lastValidUserAgent
            }

            val cookieManager = CookieManager.getInstance()
            try {
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)
                cookieManager.flush()
            } catch (_: Exception) { }

            val client = app.baseClient.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                .build()

            // storage & synchronization
            val foundM3u8 = linkedSetOf<String>()
            var finished = false
            val finishLock = Any()
            val handler = Handler(Looper.getMainLooper())
            var finishRunnable: Runnable? = null
            val overallTimeoutMs = 20_000L

            fun cleanup() {
                try {
                    if (webView.parent is ViewGroup) {
                        (webView.parent as ViewGroup).removeView(webView)
                    }
                } catch (_: Exception) {}
                try { webView.stopLoading() } catch (_: Exception) {}
                try { webView.destroy() } catch (_: Exception) {}
                try { cookieManager.flush() } catch (_: Exception) {}
                try { if (dialog.isShowing) dialog.dismiss() } catch (_: Exception) {}
            }

            fun safeFinish(result: String?) {
                synchronized(finishLock) {
                    if (finished) return
                    finished = true
                }
                try {
                    if (cont.isActive) cont.resume(result)
                } catch (_: Exception) {}
                cleanup()
            }

            fun chooseAndFinish() {
                if (foundM3u8.isEmpty()) {
                    safeFinish(null)
                    return
                }
                // prefer strict .m3u8 path (avoid analytics ping with m3u8 in query)
                val strict = foundM3u8.firstOrNull {
                    val clean = it.substringBefore("?")
                    clean.endsWith(".m3u8") && (clean.contains("master") || clean.contains("playlist") || clean.contains("index"))
                } ?: foundM3u8.firstOrNull { it.substringBefore("?").endsWith(".m3u8") }
                val final = strict ?: foundM3u8.first()
                safeFinish(final)
            }

            handler.postDelayed({
                synchronized(finishLock) {
                    if (!finished) chooseAndFinish()
                }
            }, overallTimeoutMs)

            // Shared WebViewClient (store then assign to avoid getWebViewClient on older APIs)
            lateinit var sharedWebViewClient: WebViewClient
            sharedWebViewClient = object : WebViewClient() {

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    Log.d("FASEL_DEBUG", "Headless Page Started: $url")
                    super.onPageStarted(view, url, favicon)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    try {
                        // inject sniffer + force play + jw read
                        val js = """
                        (function() {
                            try {
                                if (!window.__NET_HOOKED__) {
                                    window.__NET_HOOKED__ = true;
                                    // hook fetch
                                    const _fetch = window.fetch;
                                    if (_fetch) {
                                        window.fetch = function() {
                                            return _fetch.apply(this, arguments).then(function(resp) {
                                                try {
                                                    const u = resp && resp.url ? resp.url : '';
                                                    if (u && u.indexOf('.m3u8') !== -1) {
                                                        console.log('NET_M3U8::' + u);
                                                    }
                                                    try {
                                                        resp.clone().text().then(function(t){
                                                            var m = t && t.match(/https?:\/\/[^"'\\s]+\\.m3u8/);
                                                            if (m) console.log('NET_M3U8::' + m[0]);
                                                        }).catch(function(){});
                                                    } catch(e){}
                                                } catch(e){}
                                                return resp;
                                            });
                                        };
                                    }
                                    // hook XHR
                                    const _open = XMLHttpRequest.prototype.open;
                                    XMLHttpRequest.prototype.open = function(method, u) {
                                        this.addEventListener('load', function() {
                                            try {
                                                if (typeof u === 'string' && u.indexOf('.m3u8') !== -1) {
                                                    console.log('NET_M3U8::' + u);
                                                }
                                                try {
                                                    var txt = this.responseText || '';
                                                    var m = txt && txt.match(/https?:\/\/[^"'\\s]+\\.m3u8/);
                                                    if (m) console.log('NET_M3U8::' + m[0]);
                                                } catch(e){}
                                            } catch(e){}
                                        });
                                        return _open.apply(this, arguments);
                                    };
                                    console.log('🌐 Network sniffer installed');
                                }
                                // force play attempts (jw api + clicks)
                                try {
                                    if (typeof jwplayer === 'function') {
                                        try {
                                            var p = jwplayer();
                                            if (p && typeof p.play === 'function') {
                                                try { p.setMute(true); } catch(e) {}
                                                try { p.play(); console.log('JW_API_PLAY'); } catch(e) {}
                                            }
                                        } catch(e){}
                                    }
                                } catch(e){}
                                var sels = ['.jw-display-icon-container','.jw-icon-play','.jw-svg-icon-play','.jw-display','.jwplayer','#player','.player','video'];
                                for (var i=0;i<sels.length;i++){
                                    try {
                                        var el = document.querySelector(sels[i]);
                                        if (el) { el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true})); console.log('FORCE_CLICK:'+sels[i]); }
                                    } catch(e){}
                                }
                                // read jw playlist
                                try {
                                    if (typeof jwplayer === 'function') {
                                        try {
                                            var p2 = jwplayer();
                                            if (p2 && typeof p2.getPlaylist === 'function') {
                                                var pl = p2.getPlaylist();
                                                if (pl && pl.length>0 && pl[0].sources) {
                                                    pl[0].sources.forEach(function(s){
                                                        try {
                                                            if (s && s.file && s.file.indexOf('.m3u8') !== -1) {
                                                                console.log('JW_M3U8::' + s.file);
                                                            }
                                                        } catch(e){}
                                                    });
                                                }
                                            }
                                        } catch(e){}
                                    }
                                } catch(e){}
                            } catch(err){}
                        })();
                    """.trimIndent()
                        try { view?.evaluateJavascript(js, null) } catch (_: Exception) {}
                    } catch (_: Exception) {}
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val url = request.url.toString()
                    val method = request.method
                    val lower = url.lowercase()

                    // ignore images/fonts/styles
                    if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".woff2") || lower.endsWith(".css")) {
                        return super.shouldInterceptRequest(view, request)
                    }

                    // only intercept real .m3u8 files (path ends with .m3u8)
                    if (method.equals("GET", ignoreCase = true) &&
                        lower.contains(".m3u8") &&
                        lower.substringBefore("?").endsWith(".m3u8")
                    ) {
                        try {
                            Log.d("FASEL_DEBUG", "Headless M3U8 detected (collecting): $url")
                            synchronized(foundM3u8) {
                                if (!foundM3u8.contains(url)) {
                                    foundM3u8.add(url)
                                    if (lower.contains("master") || lower.contains("playlist") || lower.contains("index.m3u8")) {
                                        finishRunnable?.let { handler.removeCallbacks(it) }
                                        finishRunnable = Runnable { chooseAndFinish() }
                                        handler.postDelayed(finishRunnable!!, 1200)
                                    } else {
                                        if (finishRunnable == null) {
                                            finishRunnable = Runnable { chooseAndFinish() }
                                            handler.postDelayed(finishRunnable!!, 6000)
                                        }
                                    }
                                }
                            }

                            // proxy via OkHttp to pass headers/cookies
                            val reqBuilder = OkRequest.Builder().url(url)
                                .header("User-Agent", lastValidUserAgent)
                                .header("Referer", referer)
                                .header("Origin", mainUrl)
                            try { cookieManager.getCookie(url)?.let { ck -> reqBuilder.header("Cookie", ck) } } catch (_: Exception) {}
                            val response = client.newCall(reqBuilder.build()).execute()
                            if (!response.isSuccessful) {
                                Log.d("FASEL_DEBUG", "Proxy error ${response.code} for $url")
                                return null
                            }
                            response.headers("Set-Cookie").forEach { try { cookieManager.setCookie(url, it) } catch (_: Exception) {} }
                            val contentType = response.header("content-type")?.split(";")?.first() ?: "application/vnd.apple.mpegurl"
                            val encoding = "utf-8"
                            val stream = response.body?.byteStream()
                            return WebResourceResponse(contentType, encoding, stream)
                        } catch (e: Exception) {
                            Log.d("FASEL_DEBUG", "Proxy fail for $url : ${e.message}")
                            return null
                        }
                    }

                    // proxy other player resources to ensure correct headers
                    if (method.equals("GET", ignoreCase = true) &&
                        (lower.contains("fasel") || lower.contains("jwplayer") || lower.contains("config") || lower.contains("player"))
                    ) {
                        try {
                            val reqBuilder = OkRequest.Builder().url(url)
                                .header("User-Agent", lastValidUserAgent)
                                .header("Referer", referer)
                                .header("Origin", mainUrl)
                            try { cookieManager.getCookie(url)?.let { ck -> reqBuilder.header("Cookie", ck) } } catch (_: Exception) {}
                            val response = client.newCall(reqBuilder.build()).execute()
                            response.headers("Set-Cookie").forEach { try { cookieManager.setCookie(url, it) } catch (_: Exception) {} }
                            val contentType = response.header("content-type")?.split(";")?.first() ?: "text/html"
                            val encoding = "utf-8"
                            val stream = response.body?.byteStream()
                            return WebResourceResponse(contentType, encoding, stream)
                        } catch (e: Exception) {
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    return super.shouldInterceptRequest(view, request)
                }
            }

            // assign shared client to main webView
            webView.webViewClient = sharedWebViewClient

            // WebChromeClient to capture console logs from injected JS
            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                    val msg = cm?.message() ?: ""
                    try {
                        if (msg.startsWith("NET_M3U8::")) {
                            val url = msg.substringAfter("NET_M3U8::").trim()
                            val clean = url.substringBefore("?")
                            if (clean.endsWith(".m3u8")) {
                                synchronized(foundM3u8) {
                                    if (!foundM3u8.contains(url)) foundM3u8.add(url)
                                }
                                // quick finish for master/playlist/index
                                if (clean.contains("master") || clean.contains("playlist") || clean.contains("index")) {
                                    finishRunnable?.let { handler.removeCallbacks(it) }
                                    finishRunnable = Runnable { chooseAndFinish() }
                                    handler.postDelayed(finishRunnable!!, 600)
                                } else {
                                    if (finishRunnable == null) {
                                        finishRunnable = Runnable { chooseAndFinish() }
                                        handler.postDelayed(finishRunnable!!, 3000)
                                    }
                                }
                            }
                        } else if (msg.startsWith("JW_M3U8::")) {
                            val url = msg.removePrefix("JW_M3U8::").trim()
                            val clean = url.substringBefore("?")
                            if (clean.endsWith(".m3u8")) {
                                synchronized(foundM3u8) {
                                    if (!foundM3u8.contains(url)) foundM3u8.add(url)
                                }
                                finishRunnable?.let { handler.removeCallbacks(it) }
                                finishRunnable = Runnable { chooseAndFinish() }
                                handler.postDelayed(finishRunnable!!, 600)
                            }
                        }
                    } catch (_: Exception) {}
                    return true
                }

                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    try {
                        val transport = resultMsg?.obj as? WebView.WebViewTransport
                        val newWebView = WebView(activity).apply {
                            layoutParams = FrameLayout.LayoutParams(1, 1, Gravity.START or Gravity.TOP)
                            visibility = View.INVISIBLE
                        }
                        newWebView.settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            mediaPlaybackRequiresUserGesture = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            userAgentString = lastValidUserAgent
                        }
                        // attach newWebView to view hierarchy quietly
                        try {
                            val decor = activity.window?.decorView as? ViewGroup
                            decor?.addView(newWebView)
                        } catch (_: Exception) {}
                        // assign same clients (use shared reference)
                        newWebView.webViewClient = sharedWebViewClient
                        newWebView.webChromeClient = this
                        transport?.webView = newWebView
                        resultMsg?.sendToTarget()
                        return true
                    } catch (e: Exception) {
                        Log.d("FASEL_DEBUG", "onCreateWindow failed: ${e.message}")
                        return false
                    }
                }
            }

            // Load iframe using referer header
            val finalUrl = iframeUrl.replace("&amp;", "&").trim()
            try {
                webView.loadUrl(finalUrl, mapOf("Referer" to referer))
            } catch (e: Exception) {
                safeFinish(null)
            }

            // cancellation handling
            cont.invokeOnCancellation {
                handler.post { safeFinish(null) }
            }
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // نجلب صفحة الحلقة للحصول على الكوكيز والبيانات
        val doc = smartGet(data)

        // نستخرج روابط الـ iframe باستخدام دالتك الممتازة
        val iframeUrls = extractIframeSources(doc)

        if (iframeUrls.isEmpty()) {
            Log.e("FASELHD", "❌ No iframe found")
            return false
        }

        var foundLink = false

        // نجرب الروابط المستخرجة (نستخدم distinct لمنع التكرار)
        iframeUrls.distinct().forEach { iframeUrl ->
            if (foundLink) return@forEach // إذا وجدنا رابط وتوقفنا

            Log.d("FASELHD", "Testing iframe: $iframeUrl")

            // نمرر الـ data (رابط صفحة الحلقة) كـ Referer
            // هذا التعديل مهم لأن دالتك السابقة كانت لا تمرر Referer للـ WebView
            val m3u8 = resolveWithWebView(iframeUrl, data)

            if (!m3u8.isNullOrBlank()) {
                foundLink = true

                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = m3u8,
                    referer = iframeUrl, // هنا السيرفر يتوقع أن الطلب قادم من الـ iframe
                    headers = mapOf(
                        "Referer" to iframeUrl,
                        "User-Agent" to lastValidUserAgent
                    )
                ).forEach(callback)
            }
        }

        return foundLink
    }
}
