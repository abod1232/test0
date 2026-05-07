package com.cimanow
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CimaNow: Plugin() {
    override fun load(context: Context) {
        // تسجيل المزود
        registerMainAPI(AlooyTvProvider())
    }
}
