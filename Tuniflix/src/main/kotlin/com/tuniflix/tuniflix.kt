package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Base64 // قد نحتاجها أحياناً، لكن الاعتماد الأساسي على Hex

class Tuniflix : MainAPI() {
    override var mainUrl = "https://tuniflix.site"
    override var name = "Tuniflix"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "أفلام",
        "$mainUrl/series/page/" to "مسلسلات",
        "$mainUrl/tg/tunisian-movies/page/" to "أفلام تونسية",
        "$mainUrl/tg/arabic-movies/page/" to "أفلام عربية",
        "$mainUrl/tg/turkish-series/page/" to "مسلسلات تركية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        val home = document.select("article.TPost.B").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val href = link.attr("href")
        val title = this.selectFirst(".Title")?.text() ?: return null

        var posterUrl = this.selectFirst(".Image img")?.let { img ->
            img.attr("data-src") ?: img.attr("src")
        }
        if (posterUrl?.startsWith("//") == true) {
            posterUrl = "https:$posterUrl"
        }

        return if (href.contains("/serie/") || this.select(".TpTv").text()
                .contains("Serie", true)
        ) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article.TPost.B").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.Title")?.text()?.trim() ?: "Unknown"
        val desc = document.selectFirst(".Description p")?.text()?.trim()

        var poster = document.selectFirst(".Image img.TPostBg")?.attr("src")
            ?: document.selectFirst(".Image img")?.attr("src")
        if (poster?.startsWith("//") == true) poster = "https:$poster"

        val year = document.selectFirst(".Date")?.text()?.toIntOrNull()
        val tags = document.select(".Tags a").map { it.text() }

        val isSeries = url.contains("/serie/") || document.select(".SeasonBx").isNotEmpty()

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val seasonLinks = document.select(".SeasonBx .Title a").map { it.attr("href") }

            seasonLinks.amap { seasonUrl ->
                try {
                    val seasonDoc = app.get(seasonUrl).document
                    val seasonTitle = seasonDoc.selectFirst("h1.Title")?.text() ?: ""
                    val seasonNum = Regex("Season\\s*(\\d+)").find(seasonTitle)?.groupValues?.get(1)
                        ?.toIntOrNull() ?: 1

                    seasonDoc.select(".TPTblCn table tr").forEach { tr ->
                        val epLink = tr.selectFirst("a")?.attr("href") ?: return@forEach
                        val epName = tr.selectFirst(".MvTbTtl a")?.text() ?: "Episode"
                        val epNum = tr.selectFirst(".Num")?.text()?.toIntOrNull()

                        episodes.add(newEpisode(epLink) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = poster
                        })
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.tags = tags
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

        // البحث الشامل عن الروابط (Iframe أو Script src)
        // 1. البحث عن روابط المشغل المباشرة
        val playerFrames = document.select("iframe[src*='strp2p'], script[src*='strp2p']")
        playerFrames.forEach { frame ->
            val src = fixUrl(frame.attr("src"))
            Strp2p.extract(src, callback)
        }

        // 2. البحث عن روابط التضمين الداخلية (trembed) للدخول في "جحر الأرنب"
        val embedFrames = document.select("iframe[src*='trembed'], iframe[src*='trid']")
        embedFrames.forEach { frame ->
            val src = fixUrl(frame.attr("src"))
            // الدخول للصفحة الداخلية
            val embedDoc = app.get(src, referer = mainUrl).document
            // البحث عن المشغل داخل الصفحة الداخلية
            val innerPlayer = embedDoc.selectFirst("iframe[src*='strp2p'], script[src*='strp2p']")
            if (innerPlayer != null) {
                val playerSrc = fixUrl(innerPlayer.attr("src"))
                Strp2p.extract(playerSrc, callback)
            }
        }

        // 3. استخراج السيرفرات العادية
        document.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (!src.contains("trembed") && !src.contains("strp2p")) {
                loadExtractor(src, mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return mainUrl + url
        return url
    }

    // === كلاس فك التشفير (The Smart Decryptor) ===
    // === كلاس فك التشفير واستخراج الروابط ===
    object Strp2p {
        private const val KEY_STRING = "kiemtienmua911ca"
        private const val API_BASE = "https://watch.strp2p.site"

        // دالة توليد الـ IV (محاكاة لمنطق JS وبايثون)
        private fun getIv(D: Int, W: Int): ByteArray {
            try {
                // الجزء الأول: (1..9) + D
                val part1 = (1..9).map { (it + D).toChar() }.joinToString("")

                // الجزء الثاني: القيم الثابتة والمتغيرة [D, 111, W, 128, 132, 97, 95]
                val part2Chars = intArrayOf(D, 111, W, 128, 132, 97, 95)
                val part2 = part2Chars.map { it.toChar() }.joinToString("")

                val fullString = part1 + part2
                // نأخذ أول 16 بايت فقط
                return fullString.toByteArray(Charsets.UTF_8).copyOfRange(0, 16)
            } catch (e: Exception) {
                return ByteArray(16)
            }
        }

        // دالة فك التشفير
        private fun decrypt(encryptedHex: String, D: Int, W: Int): String? {
            return try {
                var cleanHex = encryptedHex.trim().replace("\"", "")
                // إصلاح الطول الفردي (Odd-length string)
                if (cleanHex.length % 2 != 0) {
                    cleanHex = cleanHex.dropLast(1)
                }

                val encryptedBytes = cleanHex.chunked(2)
                    .map { it.toInt(16).toByte() }
                    .toByteArray()

                val skeySpec = SecretKeySpec(KEY_STRING.toByteArray(Charsets.UTF_8), "AES")
                val ivSpec = IvParameterSpec(getIv(D, W))

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, skeySpec, ivSpec)

                val original = cipher.doFinal(encryptedBytes)
                String(original, Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        }

        // --- الدالة الرئيسية (extract) ---
        suspend fun extract(initialUrl: String, callback: (ExtractorLink) -> Unit) {
            try {
                var videoId = ""

                // 1. محاولة استخراج ID مباشرة إذا كان الرابط هو المشغل
                if (initialUrl.contains("strp2p.site")) {
                    videoId = if (initialUrl.contains("#")) {
                        initialUrl.substringAfter("#").substringBefore("&")
                    } else {
                        initialUrl.substringAfter("id=").substringBefore("&")
                    }
                }
                // 2. إذا كان الرابط هو Tuniflix Embed، نبحث عن iframe المشغل بداخله
                else if (initialUrl.contains("tuniflix.site")) {
                    val doc = app.get(initialUrl).document
                    val playerIframe = doc.selectFirst("iframe[src*='strp2p']")?.attr("src")
                        ?: doc.selectFirst("iframe[src*='trembed']")
                            ?.attr("src") // في حال كان هناك embed داخل embed

                    if (playerIframe != null) {
                        // إذا وجدنا رابط داخلي، نعيد استدعاء الدالة عليه (Recursion) أو نستخرج الـ ID
                        if (playerIframe.contains("strp2p")) {
                            videoId = playerIframe.substringAfter("#").substringBefore("&")
                        } else {
                            // لو كان رابط وسيط آخر، ندخل إليه
                            extract(
                                if (playerIframe.startsWith("//")) "https:$playerIframe" else playerIframe,
                                callback
                            )
                            return
                        }
                    }
                }

                if (videoId.isEmpty()) return

                // 3. طلب الـ API
                val apiUrl = "$API_BASE/api/v1/video?id=$videoId"
                val headers = mapOf(
                    "Referer" to "https://watch.strp2p.site/",
                    "Origin" to "https://watch.strp2p.site",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )

                val encryptedResponse = app.get(apiUrl, headers = headers).text

                // 4. كسر التشفير (Brute Force المصغر)
                val candidates = listOf(
                    Pair(48, 0),    // الأكثر شيوعاً
                    Pair(48, 141),
                    Pair(48, 189),
                    Pair(48, 63),
                    Pair(323, 0)
                )

                for ((D, W) in candidates) {
                    val jsonResult = decrypt(encryptedResponse, D, W)

                    // إذا بدأ النص بـ { فهذا يعني نجاح فك التشفير
                    if (jsonResult != null && jsonResult.trim().startsWith("{")) {
                        try {
                            val data = AppUtils.parseJson<StrpResponse>(jsonResult)

                            // الأولوية لرابط Cloudflare (cf) ثم المصدر (source)
                            val rawLink = data.cf ?: data.source

                            if (!rawLink.isNullOrEmpty()) {
                                // 5. تنظيف الرابط (Sanitization) - الحل الجذري لمشكلة http%...
                                // نستخدم Regex لاستخراج الدومين والمسار فقط وتجاهل أي بادئة مشوهة
                                // النمط: يبدأ بحروف/أرقام، يحتوي على نقطة، ثم امتداد من حرفين على الأقل، ثم باقي المسار
                                val urlRegex = Regex("""([a-zA-Z0-9.-]+\.[a-zA-Z]{2,6}/.*)""")
                                val match = urlRegex.find(rawLink)

                                val finalLink = if (match != null) {
                                    "https://${match.value}"
                                } else {
                                    // حل احتياطي: إذا لم ينجح الـ Regex نحاول الإصلاح اليدوي
                                    if (rawLink.contains("://")) {
                                        "https://" + rawLink.substringAfter("://")
                                    } else if (!rawLink.startsWith("http")) {
                                        "https://$rawLink"
                                    } else {
                                        rawLink
                                    }
                                }

                                // التأكد النهائي من صحة الرابط وإرساله
                                callback.invoke(
                                    newExtractorLink(
                                        "Tuniflix",
                                        "Tuniflix Server",
                                        finalLink,
                                    ) {
                                        "https://watch.strp2p.site/"
                                        Qualities.Unknown.value
                                    }
                                )
                                return // وجدنا الرابط، نخرج من الدالة
                            }
                        } catch (e: Exception) {
                            // فشل تحليل JSON، نجرب القيم التالية
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        data class StrpResponse(
            @JsonProperty("source") val source: String?,
            @JsonProperty("cf") val cf: String?
        )
    }
}