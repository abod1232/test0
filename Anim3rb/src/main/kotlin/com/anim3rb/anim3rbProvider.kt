package com.anime3rb

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.lang.ref.WeakReference
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class Anime3rb : MainAPI() {
    override var mainUrl = "https://anime3rb.com"
    override var name = "Anim3rb2"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        var activityContext: WeakReference<Context>? = null

        // متغيرات ثابتة لحفظ الجلسة طوال فترة تشغيل التطبيق
        private var savedCookies: String = ""
        private const val TAG = "Anime3rb_Log"

        private val NON_DIGITS = Regex("[^0-9]")
        private val TITLE_EP_REGEX = Regex("الحلقة \\d+")

        // يجب استخدام User-Agent ثابت وموحد للطرفين
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private fun toAbsoluteUrl(url: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> "$mainUrl/$url"
        }
    }

    // --- أدوات التصميم ---
    private fun dp(value: Int, context: Context): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun createRoundedBackground(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    // --- المنطق الذكي ---

    /**
     * يحاول جلب الصفحة بصمت أولاً باستخدام الكوكيز المحفوظة.
     * إذا اكتشف حماية، يفتح النافذة، يحلها، يحفظ الكوكيز الجديدة، ثم يعيد المحاولة.
     */
    private suspend fun getDocumentSmart(url: String): Document? {
        // 1. محاولة صامتة أولى
        if (savedCookies.isNotEmpty()) {
            try {
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Cookie" to savedCookies,
                    "Referer" to "$mainUrl/"
                )
                val response = app.get(url, headers = headers)

                if (response.code == 200 && !isCloudflareChallenge(response.text)) {
                    // نجحنا بالكوكيز القديمة
                    return Jsoup.parse(response.text)
                } else {
                    Log.w(TAG, "⚠️ Saved cookies expired or challenged.")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Silent request failed: ${e.message}")
            }
        }

        // 2. إذا فشلنا (أو لا توجد كوكيز)، نفتح النافذة
        val docFromDialog = openCaptchaDialog(url)

        // إذا نجح الحل، تم تحديث savedCookies تلقائياً داخل الدالة
        return docFromDialog
    }

    private fun isCloudflareChallenge(html: String): Boolean {
        return html.contains("Just a moment") ||
                html.contains("لحظة…") ||
                html.contains("challenge-platform") ||
                html.contains("cf-turnstile")
    }

    /**
     * تفتح نافذة منبثقة (Dialog).
     */
    private suspend fun openCaptchaDialog(url: String): Document? {
        Log.d(TAG, "⚡ Asking user to solve Cloudflare for: $url")

        return suspendCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                val context = activityContext?.get()
                val activity = context as? Activity

                if (activity == null || activity.isFinishing) {
                    continuation.resume(null)
                    return@post
                }

                val dialog = Dialog(activity)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                dialog.setCancelable(false)

                // التصميم العام
                val rootLayout = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10, activity), dp(10, activity), dp(10, activity), dp(10, activity))
                    background = createRoundedBackground(Color.parseColor("#1a1a2e"), dp(16, activity).toFloat())
                }

                // العنوان
                val titleView = TextView(activity).apply {
                    text = "مطلوب التحقق (Cloudflare)"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setPadding(0, 0, 0, dp(5, activity))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                rootLayout.addView(titleView)

                // حاوية الويب
                val webContainer = FrameLayout(activity).apply {
                    background = createRoundedBackground(Color.WHITE, dp(12, activity).toFloat())
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    ).apply {
                        setMargins(0, dp(5, activity), 0, dp(10, activity))
                    }
                }

                val webView = WebView(activity)
                webView.layoutParams = FrameLayout.LayoutParams(-1, -1)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    // مهم جداً: نفس الـ UserAgent المستخدم في الطلبات الخلفية
                    userAgentString = USER_AGENT
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(webView, true)

                var isSolved = false

                fun onSuccess(html: String) {
                    if (!isSolved) {
                        isSolved = true

                        // *** أهم خطوة: حفظ الكوكيز والـ UserAgent ***
                        cookieManager.flush()
                        val newCookies = cookieManager.getCookie(url)
                        if (newCookies != null) {
                            savedCookies = newCookies
                            Log.d(TAG, "✅ Cookies Updated: $savedCookies")
                        }

                        try { dialog.dismiss() } catch (e: Exception) {}

                        // تنظيف الـ HTML
                        var cleanHtml = html
                        if (cleanHtml.startsWith("\"") && cleanHtml.endsWith("\"")) {
                            cleanHtml = cleanHtml.substring(1, cleanHtml.length - 1)
                        }
                        cleanHtml = cleanHtml.replace("\\u003C", "<")
                            .replace("\\u003E", ">")
                            .replace("\\\"", "\"")
                            .replace("\\n", "\n")
                            .replace("\\t", "\t")
                            .replace("\\\\", "\\")

                        continuation.resume(Jsoup.parse(cleanHtml))
                    }
                }

                fun checkPage() {
                    if (isSolved) return
                    val jsCheck = """
                        (function() {
                            var bodyText = document.body.innerText;
                            var html = document.documentElement.innerHTML;
                            if (!bodyText.includes('Just a moment') && 
                                !bodyText.includes('لحظة…') && 
                                !html.includes('challenge-platform')) {
                                
                                if (document.querySelector('.video-card') || 
                                    document.querySelector('.simple-title-card') || 
                                    document.querySelector('.main-content') ||
                                    document.querySelector('h1')) {
                                    return document.documentElement.outerHTML;
                                }
                            }
                            return null;
                        })();
                    """
                    webView.evaluateJavascript(jsCheck) { result ->
                        if (result != null && result != "null" && result.length > 50) {
                            titleView.text = "✅ تم الحل! حفظ الجلسة..."
                            titleView.setTextColor(Color.GREEN)
                            Handler(Looper.getMainLooper()).postDelayed({ onSuccess(result) }, 500)
                        } else {
                            if(dialog.isShowing) {
                                Handler(Looper.getMainLooper()).postDelayed({ checkPage() }, 1000)
                            }
                        }
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        checkPage()
                    }
                }

                webContainer.addView(webView)
                rootLayout.addView(webContainer)

                // زر الإغلاق
                val closeButton = TextView(activity).apply {
                    text = "إلغاء"
                    setTextColor(Color.WHITE)
                    gravity = Gravity.CENTER
                    textSize = 14f
                    setPadding(dp(20, activity), dp(8, activity), dp(20, activity), dp(8, activity))
                    background = createRoundedBackground(Color.parseColor("#ff4757"), dp(20, activity).toFloat())
                    setOnClickListener {
                        dialog.dismiss()
                    }
                }

                val buttonContainer = LinearLayout(activity).apply {
                    gravity = Gravity.CENTER
                    addView(closeButton)
                }
                rootLayout.addView(buttonContainer)

                dialog.setContentView(rootLayout)
                dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))

                // تصغير النافذة قليلاً
                val width = (activity.resources.displayMetrics.widthPixels * 0.85).toInt()
                val height = (activity.resources.displayMetrics.heightPixels * 0.70).toInt()
                dialog.window?.setLayout(width, height)

                dialog.setOnDismissListener {
                    try { webView.destroy() } catch(e: Exception){}
                    if (!isSolved) {
                        isSolved = true
                        continuation.resume(null)
                    }
                }

                webView.loadUrl(url)
                dialog.show()
            }
        }
    }

    // =========================================================================
    // 1. الصفحة الرئيسية
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val doc = getDocumentSmart(request.data) ?: return null
        val homeSets = mutableListOf<HomePageList>()

        try {
            doc.select("h2:contains(الأنميات المثبتة)").firstOrNull()?.let { header ->
                val list = header.parent()?.parent()?.parent()
                    ?.select(".glide__slide:not(.glide__slide--clone) a.video-card")
                    ?.mapNotNull { toSearchResult(it) }
                if (!list.isNullOrEmpty()) homeSets.add(HomePageList("الأنميات المثبتة", list))
            }

            val latest = doc.select("#videos a.video-card").mapNotNull { toSearchResult(it) }
            if (latest.isNotEmpty()) homeSets.add(HomePageList("أحدث الحلقات", latest))

            doc.select("h3:contains(آخر الأنميات المضافة)").firstOrNull()?.let { header ->
                val list = header.parent()?.parent()?.parent()
                    ?.select(".glide__slide:not(.glide__slide--clone) a.video-card")
                    ?.mapNotNull { toSearchResult(it) }
                if (!list.isNullOrEmpty()) homeSets.add(HomePageList("آخر الأنميات المضافة", list))
            }

        } catch (e: Exception) {
            Log.e(TAG, "MainPage Error: ${e.message}")
        }
        return newHomePageResponse(homeSets)
    }

    override val mainPage = mainPageOf("$mainUrl/" to "الرئيسية")

    private fun toSearchResult(element: Element): SearchResponse? {
        return try {
            val title = element.select("h3.title-name").text().trim()
            val href = toAbsoluteUrl(element.attr("href"))
            val posterUrl = element.select("img").attr("src")
            val episodeText = element.select("p.number").text().trim()
            val episodeNum = episodeText.filter { it.isDigit() }.toIntOrNull()

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = posterUrl
                addDubStatus(false, episodeNum)
            }
        } catch (e: Exception) { null }
    }

    // =========================================================================
    // 2. البحث
    // =========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8").replace("%20", "+")
        val url = "$mainUrl/search?q=$encodedQuery"

        val doc = getDocumentSmart(url) ?: return emptyList()

        val results = doc.select("a.simple-title-card, a.video-card").mapNotNull {
            val title = it.select("h4.text-lg, h3.title-name").text().trim()
            val href = toAbsoluteUrl(it.attr("href"))
            val posterUrl = it.select("img").attr("src")

            if (title.isBlank() || href == mainUrl) return@mapNotNull null

            val typeText = it.select("div.details span.badge").text()
            val type = if (typeText.contains("Movie") || title.contains("Film")) TvType.AnimeMovie else TvType.Anime

            newAnimeSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }

        return results
    }

    // =========================================================================
    // 3. التحميل (Load)
    // =========================================================================
    override suspend fun load(url: String): LoadResponse? {
        val fullUrl = toAbsoluteUrl(url)
        val doc = getDocumentSmart(fullUrl) ?: return null

        return try {
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

    // =========================================================================
    // 4. استخراج الروابط
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fullUrl = toAbsoluteUrl(data)
        // نحاول جلب صفحة المشغل بصمت
        val doc = getDocumentSmart(fullUrl) ?: return false
        val htmlText = doc.html()

        val playerPattern = """https?:(?:\\/|/){2}video\.vid3rb\.com(?:\\/|/)player(?:\\/|/)[^"']+""".toRegex()
        val match = playerPattern.find(htmlText) ?: return false

        var playerUrl = match.value
            .replace("\\", "")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .substringBefore("&quot;")
            .substringBefore("\"")

        playerUrl = toAbsoluteUrl(playerUrl)
        Log.d(TAG, "✅ Found Player URL: $playerUrl")

        return try {
            // نستخدم الكوكيز المحفوظة
            val headers = mapOf(
                "Host" to "video.vid3rb.com",
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/",
                "Cookie" to savedCookies, // استخدام الكوكيز المحفوظة
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Site" to "cross-site"
            )

            val playerResponseText = app.get(playerUrl, headers = headers).text

            val jsonPattern = """var\s+video_sources\s*=\s*(\[.*?\]);""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val jsonMatch = jsonPattern.find(playerResponseText)
            var linksFound = false

            if (jsonMatch != null) {
                try {
                    val jsonStr = jsonMatch.groupValues[1]
                    val videoList = parseJson<List<Map<String, Any?>>>(jsonStr)
                    val foundUrls = mutableSetOf<String>()

                    for (item in videoList) {
                        val src = item["src"]?.toString() ?: continue
                        val label = item["label"]?.toString() ?: "Unknown"
                        val premium = item["premium"]?.toString() == "true"
                        if (premium) continue

                        val cleanLink = src.replace("\\", "")
                        if (!foundUrls.add(cleanLink)) continue

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
                        linksFound = true
                    }
                } catch (e: Exception) { Log.e(TAG, "JSON parse error: $e") }
            }

            if (!linksFound) {
                val videoPattern = """https:(?:\\/|/){2}video\.vid3rb\.com(?:\\/|/)video(?:\\/|/)[^"'\s<>]+""".toRegex()
                val videoMatches = videoPattern.findAll(playerResponseText)
                val foundUrls = mutableSetOf<String>()

                videoMatches.forEach { m ->
                    val cleanLink = m.value.replace("\\", "").replace("&amp;", "&")
                    if (foundUrls.add(cleanLink)) {
                        val quality = getQualityInt(cleanLink)
                        callback.invoke(
                            newExtractorLink(
                                source = "Anime3rb",
                                name = "Anime3rb $quality",
                                url = cleanLink,
                            ) {
                                referer = "https://video.vid3rb.com/"
                            }
                        )
                        linksFound = true
                    }
                }
            }

            linksFound
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting links via HTTP: ${e.message}")
            false
        }
    }

    private fun getQualityInt(url: String): Int {
        val match = Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(url)
            ?: Regex("""(\d{3,4})""").find(url)
        return match?.value?.toIntOrNull() ?: Qualities.Unknown.value
    }
}
