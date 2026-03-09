package com.youtube

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.graphics.Color
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

class YoutubeSettingsBottomSheet : DialogFragment() {

    // ---------------- Prefs fragment ----------------
    class PrefsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            val screen = preferenceManager.createPreferenceScreen(ctx)
            preferenceScreen = screen

            val category = PreferenceCategory(ctx).apply { title = "اختبار وفحص مشغل Anime3rb" }
            screen.addPreference(category)

            val solvePref = Preference(ctx).apply {
                title = "اختبار استخراج الروابط (مرئي)"
                summary = "اضغط لفتح رابط الحلقة وتتبع عملية صيد الروابط في الـ Terminal."
                setOnPreferenceClickListener {
                    VideoExtractorTestDialog().show(parentFragmentManager, "extractor_test_dialog")
                    true
                }
            }
            category.addPreference(solvePref)

            val closePref = Preference(ctx).apply {
                title = "إغلاق"
                setOnPreferenceClickListener {
                    (parentFragment as? DialogFragment)?.dismiss()
                    true
                }
            }
            screen.addPreference(closePref)
        }
    }

    // ---------------- WebView dialog (أداة الاستخراج والـ Terminal) ----------------
    class VideoExtractorTestDialog : DialogFragment() {
        private lateinit var webView: WebView
        private lateinit var logTextView: TextView
        private lateinit var logScrollView: ScrollView
        private lateinit var showLinksBtn: Button
        private lateinit var showRawBtn: Button

        private val handler = Handler(Looper.getMainLooper())
        
        // متغيرات الاستخراج
        private var isSolved = false
        private var isProcessingClick = false
        private val extractedLinks = mutableListOf<Pair<String, String>>()
        private var interceptedRawContent = ""

        // 🔴 الرابط المطلوب اختباره 🔴
        private val targetEpisodeUrl = "https://anime3rb.com/episode/himesama-goumon-no-jikan-desu-2nd-season/9"

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val ctx = requireActivity()

            val rootLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.WHITE)
            }

            // 1. شريط الأزرار العلوي
            val buttonsLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 16, 16, 16)
                setBackgroundColor(Color.parseColor("#222222"))
                gravity = Gravity.CENTER_VERTICAL
            }

            val closeBtn = Button(ctx).apply { text = "إغلاق"; setTextColor(Color.RED); setBackgroundColor(Color.TRANSPARENT) }
            showRawBtn = Button(ctx).apply { text = "عرض الرد (JSON)"; isEnabled = false; setBackgroundColor(Color.DKGRAY); setTextColor(Color.WHITE) }
            showLinksBtn = Button(ctx).apply { text = "الروابط المستخرجة"; isEnabled = false; setBackgroundColor(Color.parseColor("#007AFF")); setTextColor(Color.WHITE) }

            buttonsLayout.addView(closeBtn)
            buttonsLayout.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) }) // مسافة
            buttonsLayout.addView(showRawBtn)
            buttonsLayout.addView(showLinksBtn)

            // 2. سجل الأحداث (Terminal Log)
            logScrollView = ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400)
                setBackgroundColor(Color.BLACK)
            }
            logTextView = TextView(ctx).apply {
                setTextColor(Color.GREEN)
                setPadding(16, 16, 16, 16)
                textSize = 12f
                text = "=> بدء نظام فحص واستخراج Anime3rb...\n=> الرابط: $targetEpisodeUrl\n"
            }
            logScrollView.addView(logTextView)

            // 3. متصفح الويب
            webView = WebView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                isFocusable = true
                isFocusableInTouchMode = true
            }

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = DEFAULT_USER_AGENT
                cacheMode = WebSettings.LOAD_DEFAULT
                // 🔴 مهم جداً: السماح بالتشغيل التلقائي ليقوم المشغل بطلب الروابط فوراً
                mediaPlaybackRequiresUserGesture = false 
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

            // تجميع الواجهة
            rootLayout.addView(buttonsLayout)
            rootLayout.addView(logScrollView)
            rootLayout.addView(webView)

            // أحداث الأزرار
            closeBtn.setOnClickListener { dismiss() }

            showRawBtn.setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("البيانات الخام المستلمة")
                    .setMessage(if (interceptedRawContent.length > 3000) interceptedRawContent.take(3000) + "\n... (مقطوع)" else interceptedRawContent)
                    .setPositiveButton("حسناً", null)
                    .show()
            }

            showLinksBtn.setOnClickListener {
                val linksText = extractedLinks.joinToString("\n\n") { "الجودة: ${it.second}\nالرابط: ${it.first}" }
                AlertDialog.Builder(ctx)
                    .setTitle("📺 روابط الفيديو الجاهزة للتشغيل")
                    .setMessage(if (extractedLinks.isEmpty()) "لم يتم استخراج روابط بعد." else linksText)
                    .setPositiveButton("ممتاز", null)
                    .show()
            }

            // بدء العمل
            setupWebViewInterceptor()
            webView.loadUrl(targetEpisodeUrl)

            return rootLayout
        }

        // دالة طباعة الأحداث في الشاشة السوداء
        private fun logUI(msg: String) {
            activity?.runOnUiThread {
                logTextView.append("$msg\n")
                logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        // --- قلب النظام: صيد الطلبات وتخطي الحماية ---
        private fun setupWebViewInterceptor() {
            webView.webViewClient = object : WebViewClient() {

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(v: WebView?, h: SslErrorHandler?, e: SslError?) = h!!.proceed()

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!isSolved) {
                        logUI("=> تم تحميل الصفحة. جاري فحص وجود Cloudflare أو انتظار المشغل...")
                        isProcessingClick = false
                        startCloudflarePolling()
                    }
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                    if (reqUrl.contains("cf_clearance")) {
                        logUI("=> [شبكة] تم رصد طلب كوكيز (cf_clearance)...")
                    }

                    // 1. المسار الأول: مسار المشغل العادي
                    if (reqUrl.contains("/player/") && !reqUrl.contains("cf_token=")) {
                        logUI("=> [شبكة] 🎯 تم التقاط طلب مشغل رئيسي! جاري سحب الـ HTML...")
                        Thread { extractFromHtml(reqUrl, request.requestHeaders) }.start()
                        return super.shouldInterceptRequest(view, request)
                    }

                    // 2. المسار الثاني: مسار الـ API المحمي
                    if (reqUrl.contains("/sources") && reqUrl.contains("cf_token=")) {
                        logUI("=> [شبكة] 🔥 تم التقاط طلب API محمي (cf_token)! جاري اعتراض الرد...")
                        try {
                            val connection = URL(reqUrl).openConnection() as HttpURLConnection
                            connection.requestMethod = "GET"
                            request.requestHeaders?.forEach { (k, v) ->
                                if (!k.equals("Accept-Encoding", true)) connection.setRequestProperty(k, v)
                            }
                            CookieManager.getInstance().getCookie(reqUrl)?.let { connection.setRequestProperty("Cookie", it) }

                            val responseBytes = (if (connection.responseCode < 400) connection.inputStream else connection.errorStream).readBytes()
                            val jsonString = String(responseBytes, Charsets.UTF_8)
                            interceptedRawContent = jsonString

                            logUI("=> تمت قراءة الـ JSON بنجاح. جاري تحليل الروابط...")
                            parseJsonLinks(jsonString)

                            val contentType = connection.contentType?.split(";")?.get(0) ?: "application/json"
                            return WebResourceResponse(contentType, "UTF-8", ByteArrayInputStream(responseBytes)).apply {
                                responseHeaders = mutableMapOf("Access-Control-Allow-Origin" to "*")
                            }

                        } catch (e: Exception) {
                            logUI("=> ❌ فشل الاعتراض: ${e.message}")
                        }
                    }

                    return super.shouldInterceptRequest(view, request)
                }
            }
        }

        // استخراج البيانات إذا كان الرد HTML يحتوي على مصفوفة
        private fun extractFromHtml(reqUrl: String, headers: Map<String, String>?) {
            try {
                val connection = URL(reqUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                headers?.forEach { (k, v) ->
                    if (!k.equals("Accept-Encoding", true)) connection.setRequestProperty(k, v)
                }
                CookieManager.getInstance().getCookie(targetEpisodeUrl)?.let { connection.setRequestProperty("Cookie", it) }
                connection.setRequestProperty("Referer", targetEpisodeUrl)

                val playerHtml = (if (connection.responseCode < 400) connection.inputStream else connection.errorStream).bufferedReader().readText()
                interceptedRawContent = playerHtml

                val jsonPattern = """var\s+video_sources\s*=\s*(\[[^;]+]);""".toRegex()
                val jsonMatch = jsonPattern.find(playerHtml)

                if (jsonMatch != null) {
                    logUI("=> تم استخراج مصفوفة الروابط (JSON) من داخل المشغل!")
                    parseJsonLinks(jsonMatch.groupValues[1])
                } else {
                    logUI("=> لم يتم العثور على مصفوفة روابط في الـ HTML المستلم.")
                }
            } catch (e: Exception) {
                logUI("=> ❌ خطأ في قراءة المشغل: ${e.message}")
            }
        }

        // تحليل الـ JSON وإضافته للقائمة وتفعيل الأزرار
        private fun parseJsonLinks(jsonStr: String) {
            try {
                val jsonArray = JSONArray(jsonStr)
                var count = 0
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val src = obj.optString("src", obj.optString("file", ""))
                    val label = obj.optString("label", "Default")
                    if (src.isNotEmpty()) {
                        extractedLinks.add(src to label)
                        count++
                    }
                }

                if (count > 0) {
                    logUI("=> ✅ نجاح! تم صيد $count روابط للفيديو.")
                    activity?.runOnUiThread {
                        showRawBtn.isEnabled = true
                        showLinksBtn.isEnabled = true
                    }
                } else {
                    logUI("=> ⚠️ الـ JSON لا يحتوي على مسارات فيديو.")
                }
            } catch (e: Exception) {
                logUI("=> ❌ خطأ أثناء تحليل JSON: ${e.message}")
            }
        }

        // =========================================================================
        // كود النقر التلقائي وتخطي Cloudflare (Auto-Clicker)
        // =========================================================================
        private fun simulateRealTouch(view: WebView, cssX: Float, cssY: Float) {
            val density = requireContext().resources.displayMetrics.density
            val realX = cssX * density
            val realY = cssY * density
            val downTime = SystemClock.uptimeMillis()
            val eventTime = SystemClock.uptimeMillis() + 50
            val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, realX, realY, 0)
            view.dispatchTouchEvent(downEvent)
            view.postDelayed({
                val upEvent = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, realX, realY, 0)
                view.dispatchTouchEvent(upEvent)
                downEvent.recycle()
                upEvent.recycle()
            }, 50)
        }

        private fun startCloudflarePolling() {
            val targetCssPath = "html > body > div:nth-of-type(1) > div > div:nth-of-type(2) > div"
            val runnable = object : Runnable {
                override fun run() {
                    if (isSolved) return
                    if (!isProcessingClick) {
                        val jsGetCoords = """
                            (function(){
                                try{
                                    var box = document.querySelector("$targetCssPath");
                                    if(!box) return "NO_BOX";
                                    try { box.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'center' }); } catch(e) {}
                                    var r = box.getBoundingClientRect();
                                    if(r.width === 0 && r.height === 0) return "NO_BOX";
                                    var size = Math.min(36, Math.max(18, Math.round(r.height * 0.55)));
                                    var margin = Math.round(Math.max(8, r.width * 0.03));
                                    var centerY = r.top + (r.height / 2);
                                    var rightSideX = r.right - (size / 2) - margin;
                                    var leftSideX = r.left + (size / 2) + margin;
                                    return rightSideX + "," + centerY + "|" + leftSideX + "," + centerY;
                                }catch(e){ return "ERROR"; }
                            })();
                        """.trimIndent()

                        webView.evaluateJavascript(jsGetCoords) { res ->
                            try {
                                val clean = res?.replace("\"", "") ?: ""
                                if (clean.contains("|")) {
                                    isProcessingClick = true
                                    logUI("=> ⚡ تم العثور على تحدي Cloudflare! جاري النقر التلقائي...")
                                    val sides = clean.split("|")
                                    val rx = sides[0].split(",")[0].toFloatOrNull()
                                    val ry = sides[0].split(",")[1].toFloatOrNull()
                                    val lx = sides[1].split(",")[0].toFloatOrNull()
                                    val ly = sides[1].split(",")[1].toFloatOrNull()

                                    if (rx != null && ry != null && lx != null && ly != null) {
                                        simulateRealTouch(webView, rx, ry)
                                        handler.postDelayed({
                                            simulateRealTouch(webView, lx, ly)
                                            handler.postDelayed({ isProcessingClick = false }, 3000)
                                        }, 250)
                                    } else {
                                        isProcessingClick = false
                                    }
                                }
                            } catch (e: Exception) { isProcessingClick = false }
                        }
                    }
                    handler.postDelayed(this, 2000)
                }
            }
            handler.post(runnable)
        }

        override fun onStart() {
            super.onStart()
            dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.95).toInt()
            val height = (resources.displayMetrics.heightPixels * 0.95).toInt()
            dialog?.window?.setLayout(width, height)
        }

        override fun onDestroyView() {
            super.onDestroyView()
            handler.removeCallbacksAndMessages(null)
            isSolved = true
            webView.destroy()
        }
    }

    // ---------------- Container ----------------
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val fragmentContainer = FragmentContainerView(requireContext())
        fragmentContainer.id = View.generateViewId()
        fragmentContainer.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return fragmentContainer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment())
            .commit()
    }

    companion object {
        fun show(fm: FragmentManager) {
            YoutubeSettingsBottomSheet().show(fm, "anime_settings")
        }
    }
}
