package com.iptv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Scanner
import okhttp3.Request

class VipTV : MainAPI() {
    override var mainUrl = "http://vipphyeeasph.top:8080"
    override var name = "VIP IPTV2027"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ar"
    override val hasMainPage = true

    private val m3uUrl = "http://vipphyeeasph.top:8080/get.php?username=VIP016801731572621260&password=53ba9e59e315&type=m3u_plus&output=ts"

    // ============================================================
    // 1. متغير لتخزين القنوات في الذاكرة (Cache)
    // نضعه في companion object ليبقى موجوداً طوال فترة تشغيل التطبيق
    // ============================================================
    companion object {
        // لتخزين القنوات: (اسم القناة -> رابط القناة) أو كائنات كاملة
        // سنخزن هنا قائمة SearchResponse جاهزة
        private var cachedChannels = listOf<SearchResponse>()

        // خريطة لتخزين الفئات (للصفحة الرئيسية)
        private var cachedCategories = mapOf<String, List<SearchResponse>>()
    }

    // دالة مساعدة لتحميل البيانات (تُستدعى مرة واحدة فقط)
    private suspend fun fetchAndParseChannelsIfNeeded() {
        if (cachedChannels.isNotEmpty()) return // إذا كانت البيانات موجودة، لا تفعل شيئاً
        var currentLogo: String? = null
        val tempAllChannels = mutableListOf<SearchResponse>()
        val tempCategoryMap = mutableMapOf<String, MutableList<SearchResponse>>()

        try {
            val req = Request.Builder().url(m3uUrl).build()
            val response = app.baseClient.newCall(req).execute()
            val bodyStream = response.body?.byteStream()

            if (bodyStream != null) {
                val scanner = Scanner(bodyStream, "UTF-8")
                var currentGroup = "Uncategorized"
                var currentName = ""

                while (scanner.hasNextLine()) {
    val line = scanner.nextLine().trim()

    if (line.startsWith("#EXTINF")) {

        val groupMatch = Regex("group-title=\"(.*?)\"").find(line)
        currentGroup = groupMatch?.groupValues?.get(1) ?: "Uncategorized"

        val logoMatch = Regex("tvg-logo=\"(.*?)\"").find(line)
        currentLogo = logoMatch?.groupValues?.get(1)

        currentName = line.substringAfterLast(",").trim()

    } else if (line.startsWith("http")) {

        if (currentName.isNotEmpty()) {

            val channelData = newLiveSearchResponse(currentName, line, TvType.Live) {
                this.posterUrl = currentLogo
            }

            tempAllChannels.add(channelData)

            tempCategoryMap
                .getOrPut(currentGroup) { mutableListOf() }
                .add(channelData)
        }
    }
}
                }
                scanner.close()
                response.close()
            }

            // حفظ النتائج في المتغيرات الثابتة (Static)
            cachedChannels = tempAllChannels
            cachedCategories = tempCategoryMap

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // التأكد من تحميل البيانات
        fetchAndParseChannelsIfNeeded()

        val items = mutableListOf<HomePageList>()

        // استخدام البيانات المخزنة
        cachedCategories.forEach { (category, channels) ->
            if (channels.isNotEmpty()) {
                items.add(
                    HomePageList(
                        name = category,
                        list = channels
                    )
                )
            }
        }

        return newHomePageResponse(items)
    }

    // ============================================================
    // 2. تفعيل دالة البحث
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        // التأكد من تحميل البيانات (في حال بحث المستخدم قبل فتح الصفحة الرئيسية)
        fetchAndParseChannelsIfNeeded()

        // البحث داخل القائمة المخزنة في الذاكرة
        return cachedChannels.filter { channel ->
            channel.name.contains(query, ignoreCase = true)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        return newMovieLoadResponse(
            name = name,
            url = url,          // رابط الصفحة
            dataUrl = url,      // رابط البث الحقيقي
            type = TvType.Movie // ✅ هذا هو المطلوب
        ) {
            this.posterUrl = null
            this.plot = "IPTV Live Channel"
            this.tags = listOf("Live", "IPTV")
        }
    }




    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
            ) {
                referer = mainUrl
                quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
