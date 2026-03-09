package com.lagradost.cloudstream3.plugins
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class eishkPlugin: Plugin() {
    override fun load(context: Context) {
        // يجب تمرير الـ context هنا إلى الكلاس ليعمل الـ WebView بشكل سليم
        registerMainAPI(CimaWbas(context))
    }
}
