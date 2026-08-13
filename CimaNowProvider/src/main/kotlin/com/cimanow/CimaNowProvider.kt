package com.cimanow

import android.content.Context
import android.util.Log
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.awaitAll
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import com.lagradost.cloudstream3.utils.getQualityFromName
import android.app.Activity
import android.widget.Toast
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay
import java.util.regex.Pattern
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.net.URLEncoder
import java.io.ByteArrayOutputStream
import kotlin.ranges.contains
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

class CimaNowProvider(private val context: Context) : MainAPI() {
    override var name = "Cimanow2"
    override var mainUrl = "https://cimanow.cc"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val usesWebView = false

    data class SvgObject(val stream: String, val hash: String)
    private val TAG = "CimaNowDebug"

    private fun getIntFromText(text: String): Int? {
        return Regex("""\d+""").find(text)?.value?.toIntOrNull()
    }


}