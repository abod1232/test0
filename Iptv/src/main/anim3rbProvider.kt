package com.anime3rb

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.AcraApplication
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey

// إزالة sharedPref من الـ constructor لأنه غير مدعوم في CloudStream Plugin system
class Anime3rb : MainAPI() {
    override var mainUrl = "https://anime3rb.com"
    override var name = "Anime3rb"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private const val TAG = "Anime3rb_Log"
        private val NON_DIGITS = Regex("[^0-9]")
        private val TITLE_EP_REGEX = Regex("الحلقة \\d+")
        // مفتاح حفظ الكوكيز في dataStore الخاص بالإضافة
        private const val COOKIE_KEY = "anime3rb_cookie_v2"
    }

    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private fun toAbsoluteUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    // دالة لفتح WebView مخفي وجلب الكوكيز الجديد بعد حل الكابتشا
    private suspend fun openWebViewFor(url: String): String? {
        Log.d(TAG, "⚡ Launching Background WebView for: $url")
        return suspendCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                var isResumed = false
                var webView: WebView? = null

                try {
                    // الحصول على Context التطبيق بأمان
                    val context = AcraApplication.context
                    if (context == null) {
                        if (continuation.context.isActive) continuation.resume(null)
                        return@post
                    }

                    webView = WebView(context)
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = USER_AGENT
                        cacheMode = WebSettings.LOAD_DEFAULT
                        databaseEnabled = true
                        blockNetworkImage = false // أحياناً الصور ضرورية للكابتشا
                        loadsImagesAutomatically = true
                    }

                    val cookieManager = CookieManager.getInstance()
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(webView, true)

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            val cookies = cookieManager.getCookie(url) ?: ""

                            // نتحقق من وجود كوكيز مهمة تدل على النجاح
                            // cf_clearance = تجاوز كلاودفلير
                            // laravel_session / XSRF-TOKEN = كوكيز الموقع نفسه
                            if (cookies.contains("cf_clearance") || cookies.contains("laravel_session") || cookies.contains("XSRF-TOKEN")) {
                                if (!isResumed) {
                                    isResumed = true
                                    Log.d(TAG, "✅ WebView Success! Returning Cookies.")

                                    // حفظ في النظام
                                    cookieManager.flush()
                                    // حفظ في بيانات الإضافة
                                    setKey(COOKIE_KEY, cookies)

                                    // تنظيف الويب فيو بعد قليل
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        try { webView?.destroy() } catch (e: Exception) {}
                                    }, 2000)

                                    if (continuation.context.isActive) continuation.resume(cookies)
                                }
                            }
                        }
                    }

                    Log.d(TAG, "WebView Loading: $url")
                    webView.loadUrl(url)

                    // Timeout بعد 25 ثانية
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isResumed) {
                            Log.w(TAG, "❌ WebView Timeout")
                            isResumed = true
                            try { webView?.destroy() } catch (e: Exception) {}
                            if (continuation.context.isActive) continuation.resume(null)
                        }
                    }, 25000)

                } catch (e: Exception) {
                    Log.e(TAG, "WebView Crash: ${e.message}")
                    if (!isResumed && continuation.context.isActive) {
                        try { continuation.resume(null) } catch(e: Exception){}
                    }
                }
            }
        }
    }

    private fun getHeaders(cookies: String): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Cookie" to cookies,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Referer" to "$mainUrl/",
            "Upgrade-Insecure-Requests" to "1"
        )
    }

    private fun isChallenge(text: String): Boolean {
        // كلمات تدل على وجود حماية
        return text.contains("just a moment", ignoreCase = true) ||
                text.contains("cloudflare", ignoreCase = true) ||
                text.contains("verify you are human", ignoreCase = true) ||
                text.contains("attention required", ignoreCase = true)
    }

    // --- المحرك الرئيسي للطلبات ---
    private suspend fun request(urlInput: String): Document {
        val url = toAbsoluteUrl(urlInput)
        Log.d(TAG, "🌐 Requesting: $url")

        // 1. محاولة استخدام الكوكيز المحفوظة
        var currentCookies = getKey<String>(COOKIE_KEY) ?: ""

        if (currentCookies.isBlank()) {
            currentCookies = CookieManager.getInstance().getCookie(url) ?: ""
        }

        if (currentCookies.isNotBlank()) {
            try {
                // نجرب الطلب بالكوكيز القديمة
                val response = app.get(url, headers = getHeaders(currentCookies))
                if (response.code == 200 && !isChallenge(response.text)) {
                    return response.document
                } else {
                    Log.w(TAG, "⚠️ Saved cookies expired or challenged.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Request failed: ${e.message}")
            }
        }

        // 2. إذا فشلنا، نفتح WebView للحصول على كوكيز جديدة
        val newCookies = openWebViewFor(url)

        if (newCookies != null) {
            Log.d(TAG, "🔄 Retrying with NEW WebView cookies...")
            val response = app.get(url, headers = getHeaders(newCookies))
            if (!isChallenge(response.text)) return response.document
        }

        throw ErrorLoadingException("فشل فتح الرابط. يرجى الانتظار قليلاً أو المحاولة لاحقاً.")
    }

    // =========================================================================
    // 1. الصفحة الرئيسية
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        // نستخدم دالة request الخاصة بنا بدلاً من app.get
        val doc = try {
            request(request.data)
        } catch (e: Exception) {
            return null
        }

        val homeSets = mutableListOf<HomePageList>()

        try {
            doc.select("h2:contains(الأنميات المثبتة)").firstOrNull()?.let { header ->
                val pinnedList = header.parent()?.parent()?.parent()
                    ?.select(".glide__slide:not(.glide__slide--clone) a.video-card")
                    ?.mapNotNull { toSearchResult(it) }
                if (!pinnedList.isNullOrEmpty()) homeSets.add(HomePageList("الأنميات المثبتة", pinnedList))
            }

            val latestEpisodesList = doc.select("#videos a.video-card").mapNotNull { toSearchResult(it) }
            if (latestEpisodesList.isNotEmpty()) homeSets.add(HomePageList("أحدث الحلقات", latestEpisodesList))

            doc.select("h3:contains(آخر الأنميات المضافة)").firstOrNull()?.let { header ->
                val addedList = header.parent()?.parent()?.parent()
                    ?.select(".glide__slide:not(.glide__slide--clone) a.video-card")
                    ?.mapNotNull { toSearchResult(it) }
                if (!addedList.isNullOrEmpty()) homeSets.add(HomePageList("آخر الأنميات المضافة", addedList))
            }

        } catch (e: Exception) {
            Log.e(TAG, "MainPage Error: ${e.message}")
        }
        return newHomePageResponse(homeSets)
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية"
    )

    private fun toSearchResult(element: Element): SearchResponse? {
        return try {
            val title = element.select("h3.title-name").text().trim()
            val rawHref = element.attr("href")
            val href = toAbsoluteUrl(rawHref)
            val posterUrl = element.select("img").attr("src")
            val episodeText = element.select("p.number").text().trim()
            val episodeNum = episodeText.filter { it.isDigit() }.toIntOrNull()

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(false, episodeNum)
            }
        } catch (e: Exception) {
            null
        }
    }

    // =========================================================================
    // 2. البحث
    // =========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8").replace("%20", "+")
        val url = "$mainUrl/search?q=$encodedQuery"
        val doc = request(url)

        return doc.select("a.simple-title-card").mapNotNull {
            val title = it.select("h4.text-lg").text().trim()
            val rawHref = it.attr("href")
            val href = toAbsoluteUrl(rawHref)
            val posterUrl = it.select("img").attr("src")

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
            }
        }
    }

    // =========================================================================
    // 3. تحميل التفاصيل
    // =========================================================================
    override suspend fun load(url: String): LoadResponse? {
        val fullUrl = toAbsoluteUrl(url)
        val doc = request(fullUrl)

        return withContext(Dispatchers.Default) {
            try {
                val rawTitle = doc.selectFirst("h1")?.text() ?: ""
                val title = TITLE_EP_REGEX.replace(rawTitle, "").trim()
                val poster = doc.selectFirst("img[alt*='بوستر']")?.attr("src") ?: ""
                val desc = doc.selectFirst("p.synopsis")?.text() ?: ""

                val elements = doc.select(".videos-list a")
                val episodes = ArrayList<Episode>(elements.size)

                for (i in elements.size - 1 downTo 0) {
                    val element = elements[i]
                    val rawHref = element.attr("href")
                    if (rawHref.isNullOrEmpty()) continue

                    val href = toAbsoluteUrl(rawHref)
                    val videoData = element.selectFirst(".video-data")
                    val epText = videoData?.child(0)?.text() ?: ""
                    val epNum = NON_DIGITS.replace(epText, "").toIntOrNull()
                    val epName = videoData?.child(1)?.text().orEmpty()
                    val imgAttr = element.selectFirst("img")?.attr("src").orEmpty()

                    episodes.add(
                        newEpisode(href) {
                            name = if (epName.isNotBlank()) epName else epText
                            episode = epNum
                            posterUrl = imgAttr
                        }
                    )
                }
                newTvSeriesLoadResponse(title, fullUrl, TvType.Anime, episodes) {
                    this.posterUrl = poster
                    this.plot = desc
                }
            } catch (e: Exception) {
                Log.e(TAG, "Load Error: ${e.message}")
                null
            }
        }
    }

    // =========================================================================
    // 4. الروابط
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val fullUrl = toAbsoluteUrl(data)
            val doc = request(fullUrl)
            val htmlText = doc.html()

            // نمط يلتقط رابط المشغل
            val playerPattern = """https?:(?:\\/|/){2}video\.vid3rb\.com(?:\\/|/)player(?:\\/|/)[^"']+""".toRegex()
            val match = playerPattern.find(htmlText) ?: return false

            var playerUrl = match.value
                .replace("\\", "")
                .replace("&amp;", "&")
                .replace("\\u0026", "&")

            playerUrl = toAbsoluteUrl(playerUrl)

            // طلب صفحة المشغل (أيضاً عبر request لضمان المرور)
            val playerDoc = request(playerUrl)
            val playerRespText = playerDoc.html()

            // استخراج JSON المصادر من داخل كود المشغل
            val jsonPattern = """var\s+video_sources\s*=\s*(\[.*?\]);""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val matches = jsonPattern.findAll(playerRespText)

            var success = false
            val foundLinks = mutableSetOf<String>()

            for (m in matches) {
                val jsonStr = m.groupValues[1]
                try {
                    val videoList = parseJson<List<Map<String, Any?>>>(jsonStr)
                    for (item in videoList) {
                        val src = item["src"]?.toString() ?: continue
                        val label = item["label"]?.toString() ?: "Unknown"
                        val premium = item["premium"]?.toString() == "true"
                        if (premium) continue

                        val cleanLink = src.replace("\\", "").replace("&amp;", "&").replace("\\u0026", "&")
                        if (!foundLinks.add(cleanLink)) continue

                        callback.invoke(
                            newExtractorLink(
                                source = "Anime3rb",
                                name = "Anime3rb $label",
                                url = cleanLink,
                            ) {
                                referer = "https://video.vid3rb.com/"
                                quality = getQualityFromName(label)
                            }
                        )
                        success = true
                    }
                } catch (_: Exception) {}
            }
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}