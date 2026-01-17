override suspend fun search(query: String): List<SearchResponse> {
    val headers = getAuthenticatedHeaders()

    val url =
        "$mobileApiUrl?platform_flag_label=web&r=/search/video" +
        "&keyword=$query&page=1&limit=20" +
        "&area_id=$areaId&language_flag_id=$languageId"

    val resp = app.get(url, headers = headers)
        .parsedSafe<ViuSearchResponse>()
        ?: return emptyList()

    val results = ArrayList<SearchResponse>()

    // ===== SERIES =====
    resp.data?.series?.forEach { item ->
        val seriesId = item.seriesId ?: item.id ?: return@forEach
        val title = item.seriesName ?: item.name ?: return@forEach

        results.add(
            newTvSeriesSearchResponse(
                title,
                "viu://series/$seriesId",
                TvType.TvSeries
            ) {
                posterUrl = item.coverImage ?: item.posterUrl
            }
        )
    }

    // ===== MOVIES =====
    resp.data?.movies?.forEach { item ->
        val productId = item.productId ?: return@forEach
        val title = item.name ?: item.title ?: return@forEach

        results.add(
            newMovieSearchResponse(
                title,
                "viu://movie/$productId",
                TvType.Movie
            ) {
                posterUrl = item.coverImage ?: item.posterUrl
            }
        )
    }

    return results
}


override suspend fun load(url: String): LoadResponse? {
    if (!url.startsWith("viu://")) return null

    val headers = getAuthenticatedHeaders()
    val parts = url.removePrefix("viu://").split("/")

    if (parts.size < 2) return null

    val type = parts[0]
    val id = parts[1]

    // ==========================================================
    // 🎬 MOVIE
    // ==========================================================
    if (type == "movie") {
        val detailUrl =
            "$mobileApiUrl?platform_flag_label=phone&os_flag_id=2" +
            "&r=/vod/product-detail&product_id=$id" +
            "&area_id=$areaId&language_flag_id=$languageId"

        val resp = app.get(detailUrl, headers = headers)
            .parsedSafe<ViuDetailResponse>()
            ?: return null

        val product = resp.data?.product ?: resp.data?.currentProduct ?: return null

        return newMovieLoadResponse(
            product.name ?: "Unknown",
            url,
            TvType.Movie,
            product.productId
        ) {
            posterUrl = product.coverImage
            plot = product.description ?: product.synopsis
        }
    }

    // ==========================================================
    // 📺 SERIES
    // ==========================================================
    if (type == "series") {

        // 1️⃣ جلب الحلقات مباشرة (بدون product-detail)
        val epUrl =
            "$mobileApiUrl?platform_flag_label=phone&os_flag_id=2" +
            "&r=/vod/product-list&series_id=$id&size=1000" +
            "&area_id=$areaId&language_flag_id=$languageId"

        val epResp = app.get(epUrl, headers = headers)
            .parsedSafe<ViuEpisodeListResponse>()
            ?: return null

        val episodes = epResp.data?.products
            ?.mapNotNull { ep ->
                val pid = ep.productId ?: return@mapNotNull null

                newEpisode(pid) {
                    name = ep.synopsis ?: "Episode ${ep.number}"
                    episode = ep.number?.toIntOrNull()
                    posterUrl = ep.coverImage
                }
            }
            ?.sortedBy { it.episode }
            ?: emptyList()

        if (episodes.isEmpty()) return null

        // 2️⃣ نأخذ بيانات السلسلة من أول حلقة
        val first = epResp.data?.products?.firstOrNull()

        return newTvSeriesLoadResponse(
            first?.seriesName ?: "Unknown",
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = first?.seriesCoverLandscapeImageUrl
                ?: first?.coverImage
            plot = first?.description ?: first?.synopsis
        }
    }

    return null
}
