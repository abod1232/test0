package com.youtube

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.SharedPreferences
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.ar.youtube.YoutubeProvider.PlayerResponse
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlin.text.isNullOrBlank

@CloudstreamPlugin
class YoutubeTokenPlugin: Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("YouTube", Context.MODE_PRIVATE)
        registerMainAPI(com.lagradost.cloudstream3.ar.youtube.YoutubeProvider(sharedPref))

        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            com.youtube.YoutubeSettingsBottomSheet.show(activity.supportFragmentManager)

        }
    }
}
//override suspend fun loadLinks(
//    data: String,
//    isCasting: Boolean,
//    subtitleCallback: (SubtitleFile) -> Unit,
//    callback: (ExtractorLink) -> Unit
//): Boolean {
//    return runCatching {
//        val url = when {
//            data.startsWith("http") -> data
//            data.length == 11 -> "https://www.youtube.com/watch?v=$data"
//            else -> return false
//        }
//
//        YoutubeExtractor().getUrl(
//            url,
//            null,
//            subtitleCallback,
//            callback
//        )
//
//        true
//    }.getOrDefault(false)
//}
