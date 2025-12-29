package com.iptv
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.plugins.VipTV

@CloudstreamPlugin
class eishkPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(VipTV())
    }
}