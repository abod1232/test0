package com.cimanow

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class cimanowsetting : BottomSheetDialogFragment() {

    private var webView: WebView? = null
    private var logScrollView: ScrollView? = null
    private var logTextView: TextView? = null

    private val logBuilder = StringBuilder("--- بدء تشغيل سجل اتصالات الإضافة (مزامنة الهوية الكاملة) ---\n\n")
    private val isFinished = AtomicBoolean(false)

    // دالة مساعدة لطباعة السجل في الوقت الفعلي داخل الواجهة بشكل متزامن وآمن
    private fun writeToLog(message: String) {
        Handler(Looper.getMainLooper()).post {
            synchronized(logBuilder) {
                logBuilder.append(message).append("\n")
                logTextView?.text = logBuilder.toString()
            }
            logScrollView?.post {
                logScrollView?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()

        // [1] الحاوية الكلية (LinearLayout رأسي)
        val mainContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // [2] شريط التحكم العلوي بالأزرار
        val actionBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(24, 16, 24, 16)
            }
        }

        val logToggleBtn = MaterialButton(context).apply {
            text = "عرض السجل (Log)"
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                setMargins(0, 0, 12, 0)
            }
            setOnClickListener {
                if (logScrollView?.visibility == View.VISIBLE) {
                    logScrollView?.visibility = View.GONE
                    webView?.visibility = View.VISIBLE
                    text = "عرض السجل (Log)"
                } else {
                    logScrollView?.visibility = View.VISIBLE
                    webView?.visibility = View.GONE
                    text = "عرض المتصفح (WebView)"
                }
            }
        }

        val copyLogBtn = MaterialButton(context).apply {
            text = "نسخ السجل"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 12, 0)
            }
            setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Network Log", logBuilder.toString())
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "📋 تم نسخ تقرير الشبكة الكامل!", Toast.LENGTH_SHORT).show()
            }
        }

        // زر إغلاق يدوي لكي لا تغلق النافذة تلقائياً
        val closeBtn = MaterialButton(context).apply {
            text = "إغلاق"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                dismissAllowingStateLoss()
            }
        }

        actionBar.addView(logToggleBtn)
        actionBar.addView(copyLogBtn)
        actionBar.addView(closeBtn)
        mainContainer.addView(actionBar)

        // [3] حاوية العرض المشتركة (المتصفح + لوحة السجل)
        val contentFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        webView = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // شاشة الـ Log البرمجية السوداء
        logScrollView = ScrollView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#121212"))
        }

        logTextView = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(24, 24, 24, 24)
            }
            setTextColor(Color.parseColor("#00FF00"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            text = logBuilder.toString()
        }

        logScrollView?.addView(logTextView)
        contentFrame.addView(webView)
        contentFrame.addView(logScrollView)
        mainContainer.addView(contentFrame)

        setupWebView()
        return mainContainer
    }

    override fun onStart() {
        super.onStart()
        dialog?.let { d ->
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    private fun setupWebView() {
        val wv = webView ?: return

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true)
        }
        wv.removeJavascriptInterface("android")

        // تفعيل كامل الصلاحيات البرمجية للمتصفح
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)

            // [تمت الإزالة]: تم حذف سطر التعيين اليدوي لـ userAgentString ليدع نظام أندرويد يحدده ويتحكم به كلياً بشكل قياسي آمن ويتطابق مع ترويسات الـ sec-ch-ua
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val dummyWebView = WebView(view!!.context)
                dummyWebView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(v: WebView?, u: String?, f: Bitmap?) {
                        writeToLog("🛡️ [POPUP BLOCKED] تم اصطياد نافذة إعلان منبثقة وإلغاؤها: $u")
                        v?.stopLoading()
                        v?.destroy()
                    }
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                if (transport != null) {
                    transport.webView = dummyWebView
                }
                resultMsg?.sendToTarget()
                return true
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val reqUrl = request?.url?.toString() ?: return false
                val host = request?.url?.host ?: ""

                // التقاط الرابط الحقيقي للمشاهدة فور التوجيه إليه (وتفادي فخ pig)
                if ((reqUrl.contains("watching") || reqUrl.contains("watching/")) && !reqUrl.contains("pig")) {
                    if (isFinished.compareAndSet(false, true)) {
                        writeToLog("\n🎉 [SUCCESS] تم التقاط رابط المشاهدة الأصلي والنهائي بنجاح ومحاكاة متصفح Via:")
                        writeToLog("   -> الرابط الملتقط: $reqUrl\n")
                        writeToLog("ℹ️ تم حفظ السجل بنجاح؛ يرجى نسخ الـ Log والضغط على زر 'إغلاق' العلوي يدوياً بعد انتهائك.")

                        val clipboard = view?.context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("CimaNow Watch Link", reqUrl)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(view?.context, "🎉 تم التقاط رابط الفيديو الفعلي ونسخه للحافظة بنجاح!", Toast.LENGTH_LONG).show()
                    }
                    return true // حظر تحميل صفحة الفيديو لتجنب ظهور إعلانات الموقع المزعجة في النهاية
                }

                if (!reqUrl.startsWith("http://") && !reqUrl.startsWith("https://")) {
                    writeToLog("🚫 [BLOCKED PROTOCOL] تم منع تحويل خارجي آلي غريب: $reqUrl")
                    return true
                }

                if (host.contains("play.google.com") || reqUrl.contains("play.google.com")) {
                    writeToLog("🚫 [BLOCKED MARKET] تم منع إعلان من فتح متجر جوجل بلاي.")
                    return true
                }

                writeToLog("➡️ [REDIRECT] توجيه داخلي: $reqUrl")
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                val host = request.url?.host ?: ""
                val method = request.method ?: "GET"

                // [مراقبة صامتة]: طباعة طلب get-link.php لتمكنك من رؤيته بوضوح دون اعتراض أو تجميد ليعمل بشكل سليم
                if (reqUrl.contains("get-link.php") && request.method == "POST") {
                    writeToLog("🎯 [AJAX POST] السكربت يطلب الآن get-link.php يدوياً...")
                    writeToLog("   -> الرابط الكامل: $reqUrl")
                    writeToLog("   -> الترويسات (Headers): ${request.requestHeaders}")
                    return null // السماح بمروره طبيعياً لتخطي الحماية
                }

                // [2] اعتراض وتعديل طلبات الـ GET (الشاملة لكل صفحات وملفات الموقع الرئيسي للتخطي)
                if (request.method == "GET" && reqUrl.startsWith("http")) {

                    // 🌟 [تصفية النطاق الحكيمة]: حماية الخدمات الخارجية (مثل href.li) من إرسال الترويسة المسببة للـ 403
                    if (!host.contains("freex2line.online")) {
                        writeToLog("🌐 [GET NATIVE SERVICE] -> $reqUrl")
                        return super.shouldInterceptRequest(view, request)
                    }

                    try {
                        // عزل برمجى للـ GET الخاص بنطاق الموقع وحقن الترويسة لجميع طلبات الملفات والتحويلات
                        return kotlinx.coroutines.runBlocking {
                            val originalHeaders = request.requestHeaders ?: emptyMap()
                            val mergedHeaders = originalHeaders.toMutableMap()

                            mergedHeaders["X-Requested-With"] = "mark.via.gp"

                            // 🌟 [مزامنة الـ User-Agent]: سحب الـ User-Agent القياسي الفعلي لهاتفك النشط وحقنه في طلب الـ Proxy البرمجي لتفادي حظر Cloudflare كلياً
                            val activeUA = view?.settings?.userAgentString ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            mergedHeaders["User-Agent"] = activeUA
                            mergedHeaders["user-agent"] = activeUA

                            val cookiesVal = CookieManager.getInstance().getCookie(reqUrl)
                            if (!cookiesVal.isNullOrBlank()) {
                                mergedHeaders["Cookie"] = cookiesVal
                            }

                            val response = com.lagradost.cloudstream3.app.get(
                                url = reqUrl,
                                headers = mergedHeaders,
                                allowRedirects = true
                            )

                            if (response.code == 200) {
                                writeToLog("🟢 [200 OK] GET -> $reqUrl")
                            } else {
                                writeToLog("⚠️ [HTTP ${response.code}] GET -> $reqUrl")
                            }

                            val rawBody = response.okhttpResponse.body ?: return@runBlocking null
                            val contentType = response.headers["Content-Type"] ?: response.headers["content-type"] ?: "text/html"
                            val mimeType = contentType.substringBefore(";").trim()
                            val encoding = if (contentType.contains("charset=")) contentType.substringAfter("charset=").substringBefore(";").trim() else "utf-8"

                            WebResourceResponse(
                                mimeType,
                                encoding,
                                response.code,
                                "OK",
                                response.headers.toMultimap().mapValues { it.value.joinToString(", ") },
                                rawBody.byteStream()
                            )
                        }
                    } catch (e: Exception) {
                        writeToLog("❌ [FAILED] GET -> $reqUrl | Error: ${e.message}")
                        return null
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }
        }

        val targetUrl = "https://rm.freex2line.online/loadon/?link=aHR0cHM6Ly9jaW1hbm93LmNjLyVkOSU4NSVkOCViMyVkOSU4NCVkOCViMyVkOSU4NC1hLXNob3AtZm9yLWtpbGxlcnMtJWQ4JWFjMi0lZDglYWQxLSVkOSU4NSVkOCVhYSVkOCViMSVkOCVhYyVkOSU4NSVkOCVhOS93YXRjaGluZy8="
        val extraHeaders = mapOf("X-Requested-With" to "mark.via.gp")
        wv.loadUrl(targetUrl, extraHeaders)
        writeToLog("🚀 [START] تم تحميل الرابط الرئيسي الأول بنجاح ومحاكاة التوجيه.")
    }

    private fun triggerFinalBypass(tokenUrl: String) {
        if (!isFinished.compareAndSet(false, true)) return

        val context = context ?: return
        writeToLog("⚙️ [BYPASSING] جاري معالجة الكوكيز والتخطي النهائي للرابط برمجياً...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val referer = "https://rm.freex2line.online/"
                val cookiesVal = CookieManager.getInstance().getCookie(referer)

                val headers = if (!cookiesVal.isNullOrBlank()) {
                    mapOf(
                        "Cookie" to cookiesVal,
                        "X-Requested-With" to "mark.via.gp"
                    )
                } else {
                    mapOf(
                        "X-Requested-With" to "mark.via.gp"
                    )
                }

                val finalLink = com.lagradost.cloudstream3.app.get(tokenUrl, referer = referer, headers = headers).text.trim()

                withContext(Dispatchers.Main) {
                    if (finalLink.startsWith("http")) {
                        writeToLog("\n🎉 [SUCCESS] تم فك الرابط بنجاح:\n$finalLink")
                        writeToLog("\nℹ️ تم حفظ السجل بنجاح؛ يرجى نسخ الـ Log والضغط على زر 'إغلاق' العلوي يدوياً بعد انتهائك.")

                        // نسخ الرابط إلى حافظة الهاتف
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("CimaNow Resolved Link", finalLink)
                        clipboard.setPrimaryClip(clip)

                        Toast.makeText(context, "🎉 تم فك تشفير رابط الفيديو ونسخه للحافظة!", Toast.LENGTH_LONG).show()
                    } else {
                        writeToLog("❌ [SERVER ERROR] استجابة الخادم غير صالحة: $finalLink")
                        Toast.makeText(context, "فشل فك التوجيه: $finalLink", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    writeToLog("💥 [EXCEPTION] خطأ قاتل أثناء معالجة الرابط: ${e.message}")
                    Toast.makeText(context, "حدث خطأ أثناء فك الرابط: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            webView?.destroy()
            webView = null
        } catch (_: Exception) {}
    }
}