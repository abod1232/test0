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

    // =================== إعدادات ثابتة ===================
    val INPUT_URL = "https://tuniflix.site/episode/home-for-christmas-1x1"
    val KEY = "kiemtienmua911ca".toByteArray(Charsets.UTF_8) // 16 bytes
    val API_BASE = "https://watch.strp2p.site"
    val DEFAULT_HEADERS = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "https://tuniflix.site/"
    )

    // ------------------- أدوات مساعدة -------------------
    fun fixUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var u = url.replace("&#038;", "&").replace("&amp;", "&")
        return when {
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> "https://tuniflix.site$u"
            else -> u
        }
    }

    fun sanitizeFinalUrl(rawLink: String): String {
        val regex = Regex("([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,10}/.*)")
        val m = regex.find(rawLink)
        return if (m != null) {
            "https://${m.groupValues[1]}"
        } else rawLink
    }

    // ------------------- فك التشفير -------------------
    fun getIv(D: Int, W: Int): ByteArray {
        return try {
            val part1 = (1..9).map { (it + D).toChar() }.joinToString("")
            val part2Chars = listOf(D, 111, W, 128, 132, 97, 95)
            val part2 = part2Chars.map { it.toChar() }.joinToString("")
            val ivBytes = (part1 + part2).toByteArray(Charsets.UTF_8)
            if (ivBytes.size >= 16) ivBytes.sliceArray(0 until 16)
            else {
                val out = ByteArray(16)
                System.arraycopy(ivBytes, 0, out, 0, ivBytes.size.coerceAtMost(16))
                out
            }
        } catch (e: Exception) {
            ByteArray(16) { 0 }
        }
    }

    fun hexStringToByteArray(s: String): ByteArray? {
        val clean = s.replace("\"", "").trim()
        val even = if (clean.length % 2 != 0) clean.substring(0, clean.length - 1) else clean
        return try {
            val len = even.length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(even[i], 16) shl 4) + Character.digit(even[i + 1], 16)).toByte()
                i += 2
            }
            data
        } catch (e: Exception) {
            null
        }
    }

    fun decryptPayload(encryptedHex: String): org.json.JSONObject? {
        var hex = encryptedHex.trim().replace("\"", "")
        if (hex.length % 2 != 0) hex = hex.substring(0, hex.length - 1)
        val encryptedBytes = hexStringToByteArray(hex) ?: return null

        val candidates = listOf(
            Pair(48, 105),
            Pair(48, 141),
            Pair(48, 0),
            Pair(48, 189),
            Pair(48, 63),
            Pair(323, 0)
        )

        for ((D, W) in candidates) {
            try {
                val iv = getIv(D, W)
                val secretKey = javax.crypto.spec.SecretKeySpec(KEY, "AES")
                val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, javax.crypto.spec.IvParameterSpec(iv))

                val firstBlock = if (encryptedBytes.size >= 16) encryptedBytes.sliceArray(0 until 16) else encryptedBytes
                try {
                    val decFirst = cipher.doFinal(firstBlock)
                    val firstByte = decFirst[0].toInt() and 0xFF
                    if (firstByte == 0x7B || firstByte == 0x20) {
                        val iv2 = getIv(D, W)
                        val cipher2 = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                        cipher2.init(javax.crypto.Cipher.DECRYPT_MODE, secretKey, javax.crypto.spec.IvParameterSpec(iv2))
                        val fullDecrypted = cipher2.doFinal(encryptedBytes)
                        val jsonStr = String(fullDecrypted, Charsets.UTF_8)
                        return org.json.JSONObject(jsonStr)
                    }
                } catch (inner: Exception) {
                    // try next candidate
                }
            } catch (e: Exception) {
                // continue
            }
        }
        return null
    }

    // ------------------- استخراج المعرف بشكل متكرر -------------------
    fun getVideoIdRecursive(url: String, depth: Int = 0): String? {
        if (depth > 3) return null
        println("[*] Scraping (Depth $depth): $url")
        return try {
            val headers = HashMap<String, String>(DEFAULT_HEADERS)
            if (depth > 0) headers["Referer"] = url

            val doc = org.jsoup.Jsoup.connect(url).headers(headers).timeout(10000).get()

            val iframes = doc.select("iframe")
            for (iframe in iframes) {
                val srcRaw = iframe.attr("src")
                val src = fixUrl(srcRaw) ?: continue

                if ("strp2p.site" in src) {
                    println("    [+] Found Player Iframe: $src")
                    if ("#" in src) {
                        val frag = src.split("#", limit = 2)[1]
                        val id = frag.split("&")[0]
                        return id
                    }
                    if ("id=" in src) {
                        return src.split("id=")[1].split("&")[0]
                    }
                }

                if ((src.contains("trembed=") || src.contains("trid=")) && src != url) {
                    println("    -> Following Embed: $src")
                    val res = getVideoIdRecursive(src, depth + 1)
                    if (res != null) return res
                }
            }
            null
        } catch (e: Exception) {
            println("[!] Error: ${e.message}")
            null
        }
    }

    // ------------------- التنفيذ الرئيسي داخل الدالة -------------------
    try {
        println("--- Processing: $INPUT_URL ---")
        val videoId = getVideoIdRecursive(INPUT_URL)
        if (videoId.isNullOrBlank()) {
            println("❌ Failed to find Video ID.")
            return false
        }

        println("\n[+] Target ID: $videoId")
        val apiUrl = "$API_BASE/api/v1/video?id=$videoId&w=1366&h=768&r=null"
        println("[*] Calling API: $apiUrl")

        val apiHeaders = mapOf(
            "Referer" to "https://watch.strp2p.site/",
            "Origin" to "https://watch.strp2p.site",
            "User-Agent" to DEFAULT_HEADERS["User-Agent"]!!
        )

        val apiResponse = org.jsoup.Jsoup.connect(apiUrl)
            .ignoreContentType(true)
            .headers(apiHeaders)
            .timeout(10000)
            .execute()

        val bodyText = apiResponse.body()
        val dataJson = decryptPayload(bodyText)

        if (dataJson != null) {
            println("[+] Decryption Successful!")

            val sourceLink = if (dataJson.has("source")) dataJson.optString("source", null) else null
            val cfLink = if (dataJson.has("cf")) dataJson.optString("cf", null) else null

            var rawLink: String? = null
            if (!sourceLink.isNullOrBlank() && sourceLink.contains("://")) {
                println("    -> Priority: 'source' link found.")
                rawLink = sourceLink
            } else if (!cfLink.isNullOrBlank()) {
                println("    -> Fallback: Using 'cf' link.")
                rawLink = cfLink
            }

            if (rawLink != null) {
                val finalLink = sanitizeFinalUrl(rawLink)
                println("--------------------------------------------------")
                println("🎥 FINAL M3U8 URL: $finalLink")
                println("--------------------------------------------------")

                // فحص الحالة عبر HEAD
                try {
                    val headResp = org.jsoup.Jsoup.connect(finalLink)
                        .ignoreHttpErrors(true)
                        .method(org.jsoup.Connection.Method.HEAD)
                        .headers(apiHeaders)
                        .timeout(5000)
                        .execute()
                    println("✅ STATUS: ${headResp.statusCode()} OK")
                } catch (e: Exception) {
                    println("⚠️ Validation Error: ${e.message}")
                }

                // ==== هنا: استدعاء callback بالطريقة اللي طلبتها ====
                callback.invoke(
                    newExtractorLink(
                        "Tuniflix",
                        "Tuniflix Server",
                        finalLink,
                    ) {
                        this.referer = "https://watch.strp2p.site/"
                        this.quality = Qualities.Unknown.value
                    }
                )

                return true
            } else {
                println("[-] Decrypted, but no valid 'source' or 'cf' link found.")
                println(dataJson.toString(4))
                return false
            }
        } else {
            println("❌ Decryption Failed.")
            return false
        }

    } catch (e: Exception) {
        println("Error: ${e.message}")
        return false
    }
}
}
