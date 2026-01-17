package com.iptv

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.util.UUID

class VipTV : MainAPI() {

    override var name = "Viu MENA"
    override var mainUrl = "https://www.viu.com"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true

    // ===================== CONFIG =====================

    private val apiBase = "https://api-gateway-global.viu.com/api"
    private val mobileApi = "$apiBase/mobile"
    private val tokenApi = "$apiBase/auth/token"
    private val playbackApi = "$apiBase/playback/distribute"

    private val areaId = "1004"
    private val countryCode = "IQ"
    private val languageId = "6"

    private var cachedToken: String? = null
    private var tokenExpire = 0L
    private val deviceId = UUID.randomUUID().toString()

    // ===================== HEADERS =====================

    private val baseHeaders = mapOf(
        "user-agent" to "okhttp/4.12.0",
        "accept" to "application/json"
    )

    // ===================== AUTH =====================

    private suspend fun getAuthToken(): String {
        val now = System.currentTimeMillis() / 1000
        if (cachedToken != null && now < tokenExpire) return cachedToken!!

        val payload = mapOf(
            "platform" to "android",
            "platformFlagLabel" to "phone",
            "countryCode" to countryCode,
            "language" to languageId,
            "deviceId" to deviceId,
            "appVersion" to "2.23.0",
            "buildVersion" to "790",
            "appBundleId" to "com.vuclip.viu"
        )

        val res = app.post(
            tokenApi,
            data = payload,
            headers = baseHeaders
        ).parsedSafe<TokenResponse>() ?: throw Error("Token failed")

        cachedToken = res.token
        tokenExpire = now + (res.expiresIn ?: 3600)
        return cachedToken!!
    }

    private suspend fun authHeaders(): Map<String, String> =
        baseHeaders + mapOf("authorization" to "Bearer ${getAuthToken()}")

    // ===================== MAIN PAGE =====================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        return try {
            val url =
                "$mobileApi?platform_flag_label=phone" +
                "&r=/product/list" +
                "&category_id=726" +
                "&size=20" +
                "&area_id=$areaId" +
                "&language_flag_id=$languageId"

            val res = app.get(url, headers = authHeaders())
                .parsedSafe<ViuResponse>() ?: return newHomePageResponse(emptyList())

            val items = res.data?.items?.mapNotNull { it.toSearch() } ?: emptyList()

            newHomePageResponse(
                listOf(HomePageList("مسلسلات عربية", items))
            )
        } catch (e: Exception) {
            newHomePageResponse(emptyList())
        }
    }

    // ===================== SEARCH =====================

    override suspend fun search(query: String): List<SearchResponse> {
        val url =
            "$mobileApi?platform_flag_label=phone&r=/search/video" +
            "&keyword=$query&page=1&limit=20" +
            "&area_id=$areaId&language_flag_id=$languageId"

        val res = app.get(url, headers = authHeaders())
            .parsedSafe<ViuSearchResponse>()

        return res?.data?.series?.mapNotNull { it.toSearch() } ?: emptyList()
    }

    // ===================== LOAD =====================

    override suspend fun load(url: String): LoadResponse? {
        val seriesId = url.substringAfterLast("/")

        val listUrl =
            "$mobileApi?platform_flag_label=phone&os_flag_id=2" +
            "&r=/vod/product-list&series_id=$seriesId&size=1000" +
            "&area_id=$areaId&language_flag_id=$languageId"

        val res = app.get(listUrl, headers = authHeaders())
            .parsedSafe<ViuEpisodeListResponse>() ?: return null

        val eps = res.data?.products ?: return null
        val first = eps.firstOrNull() ?: return null

        val episodes = eps.mapNotNull {
            if (it.productId == null || it.ccsProductId == null) return@mapNotNull null

            newEpisode(it.productId) {
                name = it.synopsis ?: "Episode ${it.number}"
                episode = it.number?.toIntOrNull()
                posterUrl = it.coverImage
                data = "ccs:${it.ccsProductId}"
            }
        }

        return newTvSeriesLoadResponse(
            first.seriesName ?: "Viu Series",
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = first.seriesCover ?: first.coverImage
            plot = first.description
        }
    }

    // ===================== LOAD LINKS =====================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val ccsId = data.removePrefix("ccs:")

        val headers = authHeaders() + mapOf(
            "platform" to "android",
            "content-type" to "application/json"
        )

        val playUrl =
            "$playbackApi?ccs_product_id=$ccsId" +
            "&platform_flag_label=phone" +
            "&language_flag_id=$languageId" +
            "&ut=0&area_id=$areaId&os_flag_id=2" +
            "&countryCode=$countryCode"

        val res = app.get(playUrl, headers = headers)
            .parsedSafe<ViuPlaybackResponse>() ?: return false

        val streams = res.data?.stream?.url ?: return false

        streams.forEach { (q, link) ->
            callback(
                newExtractorLink(
                    source = name,
                    name = "Viu ${q.uppercase()}",
                    url = link
                ) {
                    
                    quality = when {
                        q.contains("1080") -> Qualities.P1080.value
                        q.contains("720") -> Qualities.P720.value
                        q.contains("480") -> Qualities.P480.value
                        q.contains("240") -> 240
                        else -> Qualities.Unknown.value
                    }
                }
            )
        }
        return true
    }

    // ===================== MODELS =====================

    data class TokenResponse(
        @JsonProperty("token") val token: String,
        @JsonProperty("expires_in") val expiresIn: Long?
    )

    data class ViuSearchResponse(
        @JsonProperty("data") val data: SearchData?
    )

    data class SearchData(
        @JsonProperty("series") val series: List<ViuItem>?
    )

    data class ViuResponse(
        @JsonProperty("data") val data: ViuData?
    )

    data class ViuData(
        @JsonProperty("items") val items: List<ViuItem>?
    )

    data class ViuEpisodeListResponse(
        @JsonProperty("data") val data: EpisodeData?
    )

    data class EpisodeData(
        @JsonProperty("product_list") val products: List<ViuItem>?
    )

    data class ViuPlaybackResponse(
        @JsonProperty("data") val data: PlaybackData?
    )

    data class PlaybackData(
        @JsonProperty("stream") val stream: StreamData?
    )

    data class StreamData(
        @JsonProperty("url") val url: Map<String, String>?
    )

    data class ViuItem(
        @JsonProperty("product_id") val productId: String?,
        @JsonProperty("ccs_product_id") val ccsProductId: String?,
        @JsonProperty("number") val number: String?,
        @JsonProperty("synopsis") val synopsis: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("series_name") val seriesName: String?,
        @JsonProperty("cover_image_url") val coverImage: String?,
        @JsonProperty("series_cover_landscape_image_url") val seriesCover: String?
    )

    private fun ViuItem.toSearch(): SearchResponse =
        newTvSeriesSearchResponse(
            seriesName ?: "Viu",
            "$mainUrl/${productId}",
            TvType.TvSeries
        ) {
            posterUrl = coverImage
        }
}
