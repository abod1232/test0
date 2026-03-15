package com.youtube

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import okhttp3.OkHttpClient
import okhttp3.Request

class YoutubeSettingsBottomSheet : DialogFragment() {

    private lateinit var webViewContainer: FrameLayout
    private lateinit var mainWebView: WebView
    
    // سجن الإعلانات (تخزين النوافذ الوهمية لتدميرها لاحقاً)
    private val popupsList = mutableListOf<WebView>()
    
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .build()

    private var currentIframeUrl: String = ""
    private var extractedVideoUrl: String? = null

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    companion object {
        fun show(fm: FragmentManager) {
            YoutubeSettingsBottomSheet().show(fm, "video_player_dialog")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()

        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val buttonsLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#222222"))
            gravity = Gravity.CENTER_VERTICAL
        }

        val closeBtn = Button(ctx).apply { 
            text = "إغلاق"
            setTextColor(Color.RED)
            setBackgroundColor(Color.TRANSPARENT) 
        }

        val urlInput = EditText(ctx).apply {
            hint = "أدخل رابط المشغل..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(20, 0, 20, 0)
        }

        val playBtn = Button(ctx).apply { 
            text = "تشغيل"
            setBackgroundColor(Color.parseColor("#007AFF"))
            setTextColor(Color.WHITE) 
        }

        val backBtn = Button(ctx).apply { 
            text = "رجوع"
            setBackgroundColor(Color.parseColor("#555555"))
            setTextColor(Color.WHITE) 
        }

        val copyBtn = Button(ctx).apply {
            text = "نسخ الرابط"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }

        buttonsLayout.addView(closeBtn)
        buttonsLayout.addView(urlInput)
        buttonsLayout.addView(playBtn)
        buttonsLayout.addView(backBtn)
        buttonsLayout.addView(copyBtn)

        webViewContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.BLACK)
        }

        mainWebView = WebView(ctx)
        webViewContainer.addView(mainWebView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        rootLayout.addView(buttonsLayout)
        rootLayout.addView(webViewContainer)

        setupFaselWebView(mainWebView, copyBtn, isMainPlayer = true)

        closeBtn.setOnClickListener { dismiss() }

        playBtn.setOnClickListener {
            var url = urlInput.text.toString().replace("\\s+".toRegex(), "").trim()
            if (url.isNotEmpty()) {
                if (!url.startsWith("http")) url = "https://$url"
                
                currentIframeUrl = url
                extractedVideoUrl = null
                copyBtn.visibility = View.GONE

                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(urlInput.windowToken, 0)

                val headers = mapOf("Referer" to url)
                mainWebView.loadUrl(url, headers)
            }
        }

        backBtn.setOnClickListener {
            if (popupsList.isNotEmpty()) {
                val lastPopup = popupsList.removeLast()
                webViewContainer.removeView(lastPopup)
                lastPopup.destroy()
            } else if (mainWebView.canGoBack()) {
                mainWebView.goBack()
            }
        }

        copyBtn.setOnClickListener {
            extractedVideoUrl?.let { link ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Video Link", link)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "تم نسخ الرابط!", Toast.LENGTH_SHORT).show()
                
                AlertDialog.Builder(requireContext())
                    .setTitle("الرابط النقي")
                    .setMessage(link)
                    .setPositiveButton("موافق", null)
                    .show()
            }
        }

        return rootLayout
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val width = (resources.displayMetrics.widthPixels * 0.95).toInt()
        val height = (resources.displayMetrics.heightPixels * 0.95).toInt()
        dialog?.window?.setLayout(width, height)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupFaselWebView(webView: WebView, copyBtn: Button, isMainPlayer: Boolean = false) {
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        
        settings.allowContentAccess = true
        settings.allowFileAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.mediaPlaybackRequiresUserGesture = false // مهم للتشغيل التلقائي
        
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.userAgentString = USER_AGENT

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                val msg = cm?.message() ?: ""
                try {
                    if (msg.startsWith("NET_M3U8::") || msg.startsWith("JW_M3U8::")) {
                        val url = msg.substringAfter("::").trim()
                        if (extractedVideoUrl == null && url.isNotBlank()) {
                            extractedVideoUrl = url
                            Log.d("FASEL_DEBUG", "🎯 Captured via JS: $url")
                            Handler(Looper.getMainLooper()).post { copyBtn.visibility = View.VISIBLE }
                        }
                    }
                } catch (_: Exception) {}
                return true
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                try {
                    val newWebView = WebView(requireContext())
                    // النافذة الجديدة هي إعلان وليست المشغل الأساسي
                    setupFaselWebView(newWebView, copyBtn, isMainPlayer = false) 

                    // 🔴 سجن الإعلانات: نجعل حجم نافذة الإعلان 1x1 بكسل فقط لكي لا تظهر لك!
                    newWebView.layoutParams = FrameLayout.LayoutParams(1, 1)
                    
                    webViewContainer.addView(newWebView)
                    popupsList.add(newWebView)

                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                    transport?.webView = newWebView
                    resultMsg?.sendToTarget()
                    
                    return true
                } catch (e: Exception) {
                    return false
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                super.onShowCustomView(view, callback)
                view?.let { webViewContainer.addView(it) }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                
                // 🔴 إذا كان هذا هو المشغل الرئيسي، نحقن كود التشغيل التلقائي والصيد
                if (isMainPlayer) {
                    val js = """
                    (function() {
                        // 1. تثبيت صائد الروابط
                        try {
                            if (!window.__NET_HOOKED__) {
                                window.__NET_HOOKED__ = true;
                                const _fetch = window.fetch;
                                if (_fetch) {
                                    window.fetch = function() {
                                        return _fetch.apply(this, arguments).then(function(resp) {
                                            try {
                                                const u = resp && resp.url ? resp.url : '';
                                                if (u && u.indexOf('.m3u8') !== -1) { console.log('NET_M3U8::' + u); }
                                            } catch(e){}
                                            return resp;
                                        });
                                    };
                                }
                                const _open = XMLHttpRequest.prototype.open;
                                XMLHttpRequest.prototype.open = function(method, u) {
                                    this.addEventListener('load', function() {
                                        try {
                                            if (typeof u === 'string' && u.indexOf('.m3u8') !== -1) { console.log('NET_M3U8::' + u); }
                                        } catch(e){}
                                    });
                                    return _open.apply(this, arguments);
                                };
                            }
                        } catch(err){}

                        // 2. النقر التلقائي العنيف (يعمل كل نصف ثانية حتى يشتغل الفيديو)
                        let clickAttempts = 0;
                        let clickInterval = setInterval(function() {
                            clickAttempts++;
                            
                            // أ. إخفاء طبقات الإعلانات الشفافة إن وجدت
                            document.querySelectorAll('div').forEach(d => {
                                if(d.style.zIndex > 1000) d.style.display = 'none';
                            });

                            // ب. محاولة تشغيل الفيديو مباشرة برمجياً
                            let vids = document.querySelectorAll('video');
                            vids.forEach(v => {
                                if (v.paused) {
                                    v.play().catch(e => {});
                                }
                            });

                            // ج. النقر على أزرار التشغيل المعروفة
                            var sels = ['.jw-video', '.jw-icon-display', '.vjs-big-play-button', '.plyr__control--overlaid', 'video', '#player'];
                            for (var i=0;i<sels.length;i++){
                                try {
                                    var el = document.querySelector(sels[i]);
                                    if (el) { el.click(); }
                                } catch(e){}
                            }

                            // د. تشغيل مشغل JWPlayer عبر الـ API
                            try {
                                if (typeof jwplayer === 'function') {
                                    let p = jwplayer();
                                    if (p && p.getState() !== 'playing') p.play();
                                }
                            } catch(e){}

                            // إيقاف المحاولات بعد 10 ثوانٍ لمنع استهلاك المعالج
                            if(clickAttempts > 20) clearInterval(clickInterval);

                        }, 500);
                    })();
                    """.trimIndent()
                    view?.evaluateJavascript(js, null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()

                // 🔴 تجميد الصفحة: إذا كان هذا هو المشغل الرئيسي، نمنع أي محاولة لتغيير رابط الصفحة الحالية!
                if (isMainPlayer && currentIframeUrl.isNotEmpty() && url != currentIframeUrl) {
                    Log.d("FASEL_DEBUG", "Blocked Redirect to: $url")
                    return true // إرجاع true يعني "تجاهل هذا الرابط ولا تنتقل إليه"
                }

                if (url.startsWith("intent://") || url.startsWith("market://") || url.startsWith("tg://")) {
                    return true 
                }

                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                val method = request.method
                val lower = url.lowercase()

                if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".woff2") || lower.endsWith(".css")) {
                    return super.shouldInterceptRequest(view, request)
                }

                // اصطياد m3u8 وإرساله عبر OkHttp
                if (method.equals("GET", ignoreCase = true) &&
                    (lower.contains(".m3u8") || lower.contains(".mp4")) &&
                    !lower.endsWith(".js")
                ) {
                    if (extractedVideoUrl == null) {
                        extractedVideoUrl = url
                        Handler(Looper.getMainLooper()).post { copyBtn.visibility = View.VISIBLE }
                    }
                    
                    try {
                        val reqBuilder = Request.Builder().url(url)
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", currentIframeUrl) 
                            
                        try { cookieManager.getCookie(url)?.let { ck -> reqBuilder.header("Cookie", ck) } } catch (_: Exception) {}
                        
                        val response = httpClient.newCall(reqBuilder.build()).execute()
                        if (!response.isSuccessful) return null
                        
                        response.headers("Set-Cookie").forEach { try { cookieManager.setCookie(url, it) } catch (_: Exception) {} }
                        
                        val contentType = response.header("content-type")?.split(";")?.first() ?: "application/vnd.apple.mpegurl"
                        return WebResourceResponse(contentType, "utf-8", response.body?.byteStream())
                    } catch (e: Exception) {
                        return null
                    }
                }

                if (method.equals("GET", ignoreCase = true) &&
                    (lower.contains("fasel") || lower.contains("jwplayer") || lower.contains("config") || lower.contains("player"))
                ) {
                    try {
                        val reqBuilder = Request.Builder().url(url)
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", currentIframeUrl)
                            
                        try { cookieManager.getCookie(url)?.let { ck -> reqBuilder.header("Cookie", ck) } } catch (_: Exception) {}
                        
                        val response = httpClient.newCall(reqBuilder.build()).execute()
                        response.headers("Set-Cookie").forEach { try { cookieManager.setCookie(url, it) } catch (_: Exception) {} }
                        
                        val contentType = response.header("content-type")?.split(";")?.first() ?: "text/html"
                        return WebResourceResponse(contentType, "utf-8", response.body?.byteStream())
                    } catch (e: Exception) {
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.proceed() 
            }
        }
    }
}
