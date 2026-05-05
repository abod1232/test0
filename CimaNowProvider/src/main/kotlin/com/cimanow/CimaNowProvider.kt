package com.cimanow

import android.content.Context
import android.util.Log
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.awaitAll
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import com.lagradost.cloudstream3.utils.getQualityFromName
import android.app.Activity
import android.widget.Toast
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay
import java.util.regex.Pattern
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.net.URLEncoder

import java.io.ByteArrayOutputStream
import kotlin.ranges.contains

class CimaNowProvider(private val context: Context) : MainAPI() {
    override var name = "Cimanow"
    override var mainUrl = "https://cimanow.cc"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val usesWebView = false

    data class SvgObject(val stream: String, val hash: String)
    private val TAG = "CimaNowDebug" // <-- أضف هذا السطر

    private fun getIntFromText(text: String): Int? {
        return Regex("""\d+""").find(text)?.value?.toIntOrNull()
    }

    override val mainPage = mainPageOf(
        "$mainUrl/الاحدث/" to "الاحدث",
        "$mainUrl/category/افلام-اجنبية/page/" to "افلام اجنبية",
        "$mainUrl/category/مسلسلات-اجنبية/page/" to "مسلسلات اجنبية",
        "$mainUrl/category/افلام-نتفليكس/page/" to "افلام نتفليكس",
        "$mainUrl/category/مسلسلات-نتفليكس/page/" to "مسلسلات نتفليكس",
        "$mainUrl/category/افلام-مارفل/page/" to "افلام مارفل",
        "$mainUrl/category/مسلسلات-عربية/page/" to "مسلسلات عربية",
        "$mainUrl/category/افلام-عربية/page/" to "افلام عربية",
        "$mainUrl/category/مسلسلات-عربية/page/" to "مسلسلات عربية",
        "$mainUrl/category/افلام-هندية/page/" to "أفلام هندية",
        "$mainUrl/category/افلام-تركية/page/" to "أفلام تركية",
        "$mainUrl/category/مسلسلات-تركية/page/" to "مسلسلات تركية"
    )


    private fun decodeHtml(doc: Document): Document {
        val TAG = "CimaNowDecoder (Test)"

        val rawHtml = doc.outerHtml()

        val keyMatcher = Pattern.compile("""var\s+_r\s*=\s*(\d+)""").matcher(rawHtml)
        if (!keyMatcher.find()) {
            return doc
        }
        val dynamicKey = keyMatcher.group(1).toLong()

        val dataMatcher = Pattern.compile("""['"]([A-Za-z0-9+/=~]{20,})['"]""").matcher(rawHtml)
        val extractedData = StringBuilder(100000) // حجز مساحة مسبقة لتجنب تمدد الذاكرة
        while (dataMatcher.find()) {
            extractedData.append(dataMatcher.group(1))
        }

        if (extractedData.isEmpty()) {
            return doc
        }

        val outputStream = ByteArrayOutputStream(extractedData.length / 4)
        val decoder = java.util.Base64.getDecoder()
        var successCount = 0

        val chunk = StringBuilder(64)
        val len = extractedData.length

        for (i in 0 until len) {
            val c = extractedData[i]

            if (c == '~') {
                if (chunk.isNotEmpty()) {
                    successCount += decodeAndWriteFast(chunk, decoder, dynamicKey, outputStream)
                    chunk.setLength(0)
                }
            }
            else if ((c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '+' || c == '/' || c == '=') {
                chunk.append(c)
            }
        }

        if (chunk.isNotEmpty()) {
            successCount += decodeAndWriteFast(chunk, decoder, dynamicKey, outputStream)
        }

        // 4️⃣ تحويل البايتات إلى نص HTML
        val decodedHtmlString = outputStream.toString("UTF-8")
        Log.i(TAG, "✅ تم فك تشفير $decodedHtmlString ")

        if (decodedHtmlString.isBlank()) {
            println("$TAG: ❌ فشل فك التشفير: النتيجة فارغة تماماً.")
            return doc
        }
        return Jsoup.parse(decodedHtmlString)
    }

    // دالة مساعدة صغيرة وسريعة لفك تشفير كل جزء
    private fun decodeAndWriteFast(
        chunk: StringBuilder,
        decoder: java.util.Base64.Decoder,
        key: Long,
        out: ByteArrayOutputStream
    ): Int {
        // إصلاح سريع للـ Padding
        val r = chunk.length % 4
        if (r > 0) {
            chunk.append(if (r == 2) "==" else if (r == 3) "=" else "")
        }

        try {
            val bytes = decoder.decode(chunk.toString())
            var num = 0L
            // استخراج الأرقام مباشرة من مصفوفة البايتات
            for (i in bytes.indices) {
                val b = bytes[i].toInt()
                if (b in 48..57) { // أكواد ASCII للأرقام 0-9
                    num = num * 10 + (b - 48)
                }
            }
            if (num > 0) {
                out.write((num - key).toInt())
                return 1 // نجاح
            }
        } catch (ignored: Exception) {
        }
        return 0 // فشل هذا الجزء
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page/"
        val doc = app.get(url).document
        val decodedDoc = decodeHtml(doc)
        val home = decodedDoc.select("section article").mapNotNull { toSearchResponse(it) }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.replace(" ", "+")
        val doc = app.get("$mainUrl/?s=$q").document
        val decodedDoc = decodeHtml(doc)
        return decodedDoc.select("section article").mapNotNull { toSearchResponse(it) }
    }

    override suspend fun load(url: String): LoadResponse {

        val doc = app.get(url).document
        val decodedDoc = decodeHtml(doc)

        val isMovie = decodedDoc.title().contains("فيلم")
        val posterUrl = decodedDoc.select("figure img").attr("src")
        val year = decodedDoc.select("ul li a[href^='https://cimanow.cc/release-year/']").text().toIntOrNull()
        val title = decodedDoc.title().replace(Regex("الموسم الأول|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|\\|$year|Cima Now|-|سيما ناو|ج[0-9]|\\|"), "")

        val tags = decodedDoc.select("article ul li")
            .filterNot { it.attr("aria-label") == "story" }
            .flatMap { it.text().split("،").map { tag -> tag.trim() } }

        val recommendations = decodedDoc.select("ul.related li").mapNotNull { toSearchResponse(it) }
        val synopsis = decodedDoc.select("li[aria-label=story] p").text()
        val actors = decodedDoc.select("ul li a[href^='https://cimanow.cc/actor/']").mapNotNull {
            val actorName = it.text()
            if (actorName.isNullOrBlank()) return@mapNotNull null
            ActorData(Actor(actorName))
        }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = recommendations
                this.actors = actors
            }
        } else {

            val episodes = mutableListOf<Episode>()
            val seasonElements = decodedDoc.select("section[aria-label=seasons] ul li a")

            if (seasonElements.isNotEmpty()) {
                coroutineScope {
                    val episodeLists = seasonElements.map { seasonElement ->
                        async {
                            try {
                                val seasonUrl = seasonElement.attr("href")
                                val seasonNum = getIntFromText(seasonElement.text())

                                val seasonDoc = decodeHtml(app.get(seasonUrl).document)

                                seasonDoc.select("ul#eps li a").mapNotNull { epElement ->
                                    newEpisode(epElement.attr("href")) {
                                        this.name = epElement.selectFirst("img")?.attr("alt") // استخراج العنوان من الصورة
                                        this.season = seasonNum
                                        this.episode = epElement.selectFirst("em")?.text()?.toIntOrNull()
                                        this.posterUrl = posterUrl // *** إضافة صورة المسلسل لكل حلقة ***
                                    }
                                }
                            } catch (e: Exception) {

                                emptyList<Episode>()
                            }
                        }
                    }.awaitAll()

                    episodes.addAll(episodeLists.flatten())
                }
            } else {
                val seasonNum = decodedDoc.selectFirst("span[aria-label=season-title]")?.text()?.let { getIntFromText(it) } ?: 1
                decodedDoc.select("ul#eps li a").mapNotNullTo(episodes) { epElement ->
                    newEpisode(epElement.attr("href")) {
                        this.name = epElement.selectFirst("img")?.attr("alt")
                        this.season = seasonNum
                        this.episode = epElement.selectFirst("em")?.text()?.toIntOrNull()
                        this.posterUrl = posterUrl // *** إضافة صورة المسلسل لكل حلقة ***
                    }
                }
            }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes.sortedWith(compareBy({ it.season }, { it.episode })) // ترتيب الحلقات
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = recommendations
                this.actors = actors
            }
        }
    }

    private suspend fun resolveFreex2line(url: String, context: android.content.Context?): String? {
        val TAG = "Freex2lineResolver"

        (context as? Activity)?.runOnUiThread {
            Toast.makeText(context, "قد يستغرق 12 ثانية..", Toast.LENGTH_SHORT).show()
        }

        Log.i(TAG, "======= [STARTING RESOLVER v3 - DYNAMIC KEY] =======")

        try {
            val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            val mainReferer = "https://rm.freex2line.online/"

            val sessionCookies = mutableMapOf<String, String>()

            // 1️⃣ إنشاء الجلسة وجلب الكوكيز الأولية
            Log.i(TAG, "[1/6] 🌐 Initializing session...")
            val headResponse = app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to mainReferer))
            sessionCookies.putAll(headResponse.cookies)

            // 2️⃣ جلب صفحة المقال لاستخراج البيانات
            Log.i(TAG, "[2/6] 📄 Fetching data page...")
            val pageUrl = "https://rm.freex2line.online/2020/02/blog-post.html/"
            val res = app.get(pageUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to mainReferer), cookies = sessionCookies)
            val html = res.text
            sessionCookies.putAll(res.cookies)

            // 3️⃣ استخراج نظام الربط الديناميكي (_0x_cfg)
            Log.i(TAG, "[3/6] 🔍 Analyzing dynamic mapping (CFG)...")
            val cfgText = reMatch(html, """window\._0x_cfg\s*=\s*\{([^}]+)\}""") ?: throw Exception("CFG object not found")
            val cVarName = reMatch(cfgText, """c:\s*'([^']+)'""") ?: throw Exception("c mapping not found")
            val rVarName = reMatch(cfgText, """r:\s*'([^']+)'""") ?: throw Exception("r mapping not found")
            val kVarName = reMatch(cfgText, """k:\s*'([^']+)'""") ?: throw Exception("k (key) mapping not found")
            val sXorKey = reMatch(cfgText, """s:\s*'([^']+)'""") ?: throw Exception("s (XOR key) not found")

            // 4️⃣ استخراج القيم الفعلية
            Log.i(TAG, "[4/6] 💎 Extracting dynamic values...")
            val ch = reMatch(html, """window\.$cVarName\s*=\s*'([^']+)'""") ?: throw Exception("ch value not found")
            val requestId = reMatch(html, """window\.$rVarName\s*=\s*'([^']+)'""") ?: throw Exception("requestId value not found")
            val encryptedKeyB64 = reMatch(html, """window\.$kVarName\s*=\s*'([^']+)'""") ?: throw Exception("Encrypted key value not found")

            // 5️⃣ فك تشفير المفتاح السري (XOR Decryption)
            Log.i(TAG, "[5/6] 🔓 Decrypting secret key...")
            val encryptedBytes = Base64.decode(encryptedKeyB64, Base64.DEFAULT)
            val decryptedChars = encryptedBytes.mapIndexed { index, byte ->
                val xorCharCode = sXorKey[index % sXorKey.length].code
                (byte.toInt() xor xorCharCode).toChar()
            }
            val secretKey = decryptedChars.joinToString("")
            Log.d(TAG, "   🔑 Dynamic Secret Key: $secretKey")

            Log.i(TAG, "[6/6] 🔐 Generating HMAC signature...")
            val fpRaw = "Mozilla/5.10"
            val fpBase64 = Base64.encodeToString(fpRaw.toByteArray(), Base64.NO_WRAP)

            val messageToSign = requestId + ch + fpBase64
            val hmacToken = calculateHmacSha256(messageToSign, secretKey)
            val hmacTokenEncoded = URLEncoder.encode(hmacToken, "UTF-8")


            delay(10000)

            // 7️⃣ الطلب النهائي
            Log.i(TAG, "🚀 Sending final API request...")
            val apiUrl = "https://rm.freex2line.online/2020/02/blog-post.html/get-link.php?request_id=$requestId&hmac_token=$hmacTokenEncoded&ch=$ch&fp=$fpBase64"

            val cookieHeader = sessionCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

            val finalRes = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to pageUrl,
                    "Cookie" to cookieHeader
                )
            )

            val finalResult = finalRes.text.trim()
            if (finalResult.startsWith("http")) {
                Log.i(TAG, "🎉 [SUCCESS] Watch page URL obtained: $finalResult")
                return finalResult
            } else {
                Log.e(TAG, "❌ [FAILURE] Server did not return a valid URL. Response: $finalResult")
            }

        } catch (e: Exception) {
            Log.e(TAG, "💥 [FATAL ERROR] An exception occurred during resolution: ${e.message}")
            e.printStackTrace()
        }

        Log.i(TAG, "======= [RESOLVER FINISHED - FAILED] =======")
        return null
    }

    private fun reMatch(html: String, regex: String): String? {
        return try {
            val matcher = Pattern.compile(regex).matcher(html)
            if (matcher.find()) matcher.group(1) else null
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateHmacSha256(message: String, secret: String): String {
        val hashingAlg = "HmacSHA256"
        val keySpec = SecretKeySpec(secret.toByteArray(), hashingAlg)
        val mac = Mac.getInstance(hashingAlg)
        mac.init(keySpec)
        val bytes = mac.doFinal(message.toByteArray())
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }



//    private suspend fun resolveFreex2line(url: String, context: android.content.Context?): String? {
//        val TAG = "Freex2lineWebView"
//
//        (context as? Activity)?.runOnUiThread {
//            Toast.makeText(context, "قد يستغرق 12 ثانية..", Toast.LENGTH_SHORT).show()
//        }
//
//        val tokenUrl = suspendCoroutine<String?> { continuation ->
//            val activity = context as? Activity
//            if (activity == null || activity.isFinishing) {
//                continuation.resume(null)
//                return@suspendCoroutine
//            }
//
//            activity.runOnUiThread {
//                try {
//                    val isFinished = AtomicBoolean(false)
//                    var webView: WebView? = null
//                    val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
//
//                    fun finish(result: String?) {
//                        if (isFinished.compareAndSet(false, true)) {
//
//                            activity.runOnUiThread {
//                                try {
//                                    webView?.let { rootView.removeView(it) }
//                                    webView?.destroy()
//                                } catch (_: Exception) {}
//                            }
//                            continuation.resume(result)
//                        }
//                    }
//
//                    webView = WebView(activity).apply {
//                        layoutParams = ViewGroup.LayoutParams(1, 1)
//                        alpha = 0f
//                    }
//
//                    rootView.addView(webView)
//
//                    CookieManager.getInstance().apply {
//                        setAcceptCookie(true)
//                        setAcceptThirdPartyCookies(webView, true)
//                    }
//                    webView.removeJavascriptInterface("android")
//
//                    webView.settings.apply {
//                        javaScriptEnabled = true
//                        domStorageEnabled = true
//                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
//                        mediaPlaybackRequiresUserGesture = false
//                        setJavaScriptCanOpenWindowsAutomatically(true)
//                        setSupportMultipleWindows(true)
//                        userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
//                    }
//
//                    webView.webChromeClient = object : WebChromeClient() {
//                        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
//                            val dummyWebView = WebView(view!!.context)
//                            dummyWebView.webViewClient = object : WebViewClient() {
//                                override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
//                                    v.stopLoading(); v.destroy()
//                                }
//                            }
//                            (resultMsg?.obj as? WebView.WebViewTransport)?.webView = dummyWebView
//                            resultMsg?.sendToTarget()
//                            return true
//                        }
//                    }
//
//                    webView.webViewClient = object : WebViewClient() {
//                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
//                            val reqUrl = request?.url?.toString() ?: return false
//                            return !reqUrl.startsWith("http") // حظر أي شيء ليس http/https
//                        }
//
//                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
//                            val requestUrl = request?.url?.toString() ?: ""
//                            if (requestUrl.contains("get-link.php")) {
//
//                                finish(requestUrl)
//
//                                return WebResourceResponse("text/plain", "utf-8", "".byteInputStream())
//                            }
//                            return super.shouldInterceptRequest(view, request)
//                        }
//                    }
//
//                    webView.loadUrl(url)
//
//                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
//                        if (!isFinished.get()) {
//
//                            finish(null)
//                        }
//                    }, 30000)
//
//                } catch (e: Exception) {
//                    continuation.resume(null)
//                }
//            }
//        }
//
//        if (tokenUrl != null) {
//            try {
//                val referer = "https://rm.freex2line.online/"
//                val cookies = CookieManager.getInstance().getCookie(referer)
//                val headers = if (!cookies.isNullOrBlank()) mapOf("Cookie" to cookies) else emptyMap()
//
//                val finalLink = app.get(tokenUrl, referer = referer, headers = headers).text.trim()
//
//                if (finalLink.startsWith("http")) {
//
//
//                    return finalLink
//                }
//            } catch (e: Exception) {
//
//            }
//        }
//
//        return null
//    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val TAG = "CimaNowLoadLinks"
        Log.i(TAG, "================ [START LOADLINKS] ================")
        Log.d(TAG, "-> Data URL: $data")

        try {
            // ========== [1] جلب صفحة الفيلم الأصلية ==========
            Log.i(TAG, "[1/5] Fetching initial movie page...")
            val moviePageDoc = app.get(data).document

            // ========== [2] البحث عن رابط freex2line ==========
            Log.i(TAG, "[2/5] Searching for freex2line intermediate link...")
            var intermediateLink = moviePageDoc.selectFirst("ul.btns li a.shine[href*='freex2line']")?.attr("href")

            if (intermediateLink.isNullOrBlank()) {
                Log.w(TAG, "   - Precise selector failed, trying a general search...")
                intermediateLink = moviePageDoc.select("a[href*='freex2line']").firstOrNull()?.attr("href")
            }

            if (intermediateLink.isNullOrBlank()) {
                Log.e(TAG, "   - ❌ CRITICAL: Could not find any freex2line link.")
                // لإظهار محتوى الصفحة بالكامل في حالة الفشل التام (للتصحيح العميق)
                // Log.v(TAG, "Page HTML: ${moviePageDoc.html()}")
                throw ErrorLoadingException("Failed to find intermediate link.")
            }
            Log.d(TAG, "   ✅ Found intermediate link: $intermediateLink")

            // ========== [3] تجاوز الرابط المختصر ==========
            Log.i(TAG, "[3/5] Resolving shortlink via resolveFreex2line...")
            val finalCimaNowUrl = resolveFreex2line(intermediateLink, this.context)

            if (finalCimaNowUrl.isNullOrBlank()) {
                Log.e(TAG, "   - ❌ CRITICAL: resolveFreex2line returned null.")
                throw ErrorLoadingException("Failed to bypass shortlink.")
            }
            Log.i(TAG, "   ✅ Watch page URL obtained: $finalCimaNowUrl")

            // ========== [4] جلب وفك تشفير صفحة المشاهدة ==========
            Log.i(TAG, "[4/5] Fetching and decoding watch page...")
            val watchDoc = app.get(finalCimaNowUrl, referer = data).document
            val decodedDoc = decodeHtml(watchDoc)
            val serverElements = decodedDoc.select("ul.tabcontent li")
            if (serverElements.isEmpty()) {
                Log.e(TAG, "   - ❌ CRITICAL: No server elements found after decoding.")
                return false
            }
            Log.i(TAG, "   ✅ Found ${serverElements.size} server elements.")

            // ========== [5] استخراج روابط السيرفرات ==========
            Log.i(TAG, "[5/5] Processing server elements...")
            serverElements.apmap { serverElement ->
                val dataIndex = serverElement.attr("data-index")
                val dataId = serverElement.attr("data-id")
                val name = serverElement.text().trim()
                Log.d(TAG, "   -> Processing server: '$name' (id=$dataId, index=$dataIndex)")

                val serverUrl = "$mainUrl/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=$dataIndex&id=$dataId"

                try {
                    val playerDoc = app.get(serverUrl, referer = finalCimaNowUrl).document
                    val iframeUrl = playerDoc.selectFirst("iframe")?.attr("src")?.let {
                        if (it.startsWith("//")) "https:$it" else it
                    }

                    if (iframeUrl.isNullOrBlank()) {
                        Log.w(TAG, "      - ⚠️ Iframe URL is blank for server '$name'.")
                        return@apmap // التخطي إلى السيرفر التالي
                    }

                    Log.d(TAG, "      - Got iframe URL: $iframeUrl")
                    Log.d(TAG, "      - Dispatching to handler for '$name'...")

                    // توجيه الرابط للدالة المناسبة
                    when {
                        name.contains("Cima Now", true) -> handlecima(iframeUrl, name, callback)
                        name.contains("VidPro", true) -> handleVidPro(iframeUrl, name, callback)
                        name.contains("Govid", true) || name.contains("Goovid", true) -> handleGovid(iframeUrl, name, callback)
                        name.contains("Vidlook", true) -> handleVidlook(iframeUrl, name, callback)
                        name.contains("Streamwish", true) -> handleStreamwish(iframeUrl, name, callback)
                        name.contains("Streamfile", true) || name.contains("Luluvid", true) -> handleStreamfileAndLuluvid(iframeUrl, name, callback)
                        name.contains("Vadbam", true) || name.contains("Viidshare", true) -> handleVadbamAndViidshare(iframeUrl, name, callback)
                        else -> {
                            Log.d(TAG, "      - No specific handler for '$name', using generic loadExtractor.")
                            try {
                                loadExtractor(iframeUrl, finalCimaNowUrl, subtitleCallback, callback)
                            } catch (e: Exception) {
                                Log.e(TAG, "         - ❌ Error in loadExtractor for '$name': ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "   - ❌ Failed to fetch iframe for server '$name': ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "💥 FATAL ERROR in loadLinks: ${e.message}", e)
        } finally {
            Log.i(TAG, "================ [END LOADLINKS] =================")
        }

        return true
    }


    private suspend fun handlecima(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            val iframeResponse = app.get(finalUrl, referer = finalUrl).text

            val regex = Regex("""\[(\d+p)]\s+(/uploads/[^\"]+\.mp4)""")
            val baseUrl = Regex("""(https?://[^/]+)""").find(finalUrl)?.groupValues?.get(1) ?: ""
            val links = mutableListOf<ExtractorLink>()
            regex.findAll(iframeResponse).forEach { match ->
                val qualityStr = match.groupValues[1]
                val filePath = match.groupValues[2]
                val videoUrl = baseUrl + filePath

                links.add(
                    newExtractorLink(
                        source = "CimaNow",
                        name = "CimaNow",
                        url = videoUrl
                    ).apply {
                        this.quality = getQualityFromName(qualityStr) // استخدم getQualityFromName للحصول على قيمة رقمية
                        this.referer = finalUrl
                    }
                )
            }
            links.sortByDescending { it.quality }
            links.forEach { link ->

                callback.invoke(link)
            }

        } catch (e: Exception) {

        }
    }
    private suspend fun handleVidPro(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try {
            val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, {}, callback) // no subtitleCallback here; loadExtractor overloads may vary
        } catch (e: Exception) {

        }
    }
    private suspend fun handleGovid(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try { val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, {}, callback)
        } catch (e: Exception) { Log.e("handleGovid", "error: ${e.message}") }
    }
    private suspend fun handleVidlook(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try { val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, {}, callback)
        } catch (e: Exception) { Log.e("handleVidlook", "error: ${e.message}") }
    }
    private suspend fun handleStreamwish(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try { val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, {}, callback)
        } catch (e: Exception) { Log.e("handleStreamwish", "error: ${e.message}") }
    }
    private suspend fun handleStreamfileAndLuluvid(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try { val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, {}, callback)
        } catch (e: Exception) { Log.e("handleStreamfile", "error: ${e.message}") }
    }
    private suspend fun handleVadbamAndViidshare(iframeUrl: String, name: String, callback: (ExtractorLink) -> Unit) {
        try { val finalUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl
            loadExtractor(finalUrl, mainUrl, {}, callback)
        } catch (e: Exception) { Log.e("handleVadbam", "error: ${e.message}") }
    }
    private fun toSearchResponse(element: Element): SearchResponse? {
        if (element.select("a").text().contains("الكل")) return null

        val urlElement = element.selectFirst("a")
        val posterUrl = element.select("img.lazy").attr("data-src").ifBlank {
            element.select("img.lazy").attr("src")
        }
        val category = element.select("ul.info li[aria-label=tab]").text()
        val extype = element.select("ul.info li[aria-label=tab]")
        val title = element.selectFirst("li[aria-label=title]")?.let {
            it.select("em").remove()
            it.text()
        } ?: ""

        val year = element.select("li[aria-label=year]").text().toIntOrNull()
        val qualitiesSelector = element.select("li[aria-label=ribbon]").mapNotNull {
            it.text().takeIf { text -> text.contains(Regex("""\d+""")) }
        }.joinToString(" ")
        val quality = getQualityFromString(qualitiesSelector)

        val type = if (extype.text().contains("مسلسلات", true) || extype.text().contains("موسم", true)) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return urlElement?.attr("href")?.let { href ->
            newMovieSearchResponse(
                name = title.replace(Regex("$category|موسم 1|برنامج|فيلم|مترجم|اون لاين|مسلسل|مشاهدة|انمي|أنمي|\\||"), ""),
                url = href,
                type = type,
            ) {
                this.posterUrl = posterUrl
                this.year = year
                this.quality = quality
            }
        }
    }

}
