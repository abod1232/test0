package com.iptv

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType

class TestProvider : MainAPI() {

    override var name = "Test"
    override var mainUrl = "https://example.com"
    override var lang = "ar"

    override val supportedTypes = setOf(
        TvType.Movie
    )
}
