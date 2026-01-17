
private suspend fun getPlaybackHeaders(): Map<String, String> {
    val token = getAuthToken()

    return mapOf(
        "Authorization" to "Bearer $token",
        "platform" to "android",
        "content-type" to "application/json",
        "user-agent" to "okhttp/4.12.0",
        "accept-encoding" to "gzip"
    )
}


override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {

    // ==========================================================
    // 0️⃣ Headers مطابقة للتطبيق الرسمي (مهم جدًا)
    // ==========================================================
    val playbackHeaders = mapOf(
        "Authorization" to "Bearer ${getAuthToken()}",
        "platform" to "android",
        "content-type" to "application/json",
        "user-agent" to "okhttp/4.12.0",
        "accept-encoding" to "gzip"
    )

    // ==========================================================
    // 1️⃣ جلب تفاصيل الحلقة لاستخراج ccs_product_id
    // ==========================================================
    val detailUrl =
        "$mobileApiUrl?platform_flag_label=phone&os_flag_id=2" +
        "&r=/vod/product-detail&product_id=$data" +
        "&area_id=$areaId&language_flag_id=$languageId"

    val detailResp = app.get(
        detailUrl,
        headers = playbackHeaders
    ).parsedSafe<ViuDetailResponse>() ?: return false

    val product =
        detailResp.data?.product
            ?: detailResp.data?.currentProduct
            ?: return false

    val ccsId = product.ccsProductId ?: return false

    // ==========================================================
    // 2️⃣ طلب التشغيل playback/distribute
    // ==========================================================
    val playUrl =
        "$playbackUrl?ccs_product_id=$ccsId" +
        "&platform_flag_label=phone" +
        "&language_flag_id=$languageId" +
        "&ut=0" +
        "&area_id=$areaId" +
        "&os_flag_id=2" +
        "&countryCode=$countryCode"

    val playResp = app.get(
        playUrl,
        headers = playbackHeaders
    ).parsedSafe<ViuPlaybackResponse>() ?: return false

    val streams = playResp.data?.stream?.url ?: return false

    // ==========================================================
    // 3️⃣ إضافة روابط m3u8 مباشرة (تعمل فورًا)
    // ==========================================================
    streams.forEach { (qualityKey, streamUrl) ->
        if (streamUrl.isNullOrBlank()) return@forEach

        callback(
            newExtractorLink(
                source = name,
                name = "Viu ${qualityKey.uppercase()}",
                url = streamUrl
            ) {
                referer = "https://www.viu.com/"
                isM3u8 = true
                quality = when {
                    qualityKey.contains("1080") -> Qualities.P1080.value
                    qualityKey.contains("720") -> Qualities.P720.value
                    qualityKey.contains("480") -> Qualities.P480.value
                    qualityKey.contains("240") -> 240
                    else -> Qualities.Unknown.value
                }
            }
        )
    }

    return true
}



