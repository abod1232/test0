package com.youtube

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class YoutubeSettingsBottomSheet : DialogFragment() {

    private lateinit var webViewContainer: FrameLayout
    private lateinit var mainWebView: WebView
    private lateinit var openBtn: Button // جعلناه متغير عام لكي نغير نصه أثناء التحميل
    
    // قائمة لتتبع النوافذ المنبثقة للإعلانات
    private val popupsList = mutableListOf<WebView>()

    // عميل OkHttp لمساعدة WebView في الطلبات المتقدمة
    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // مجموعة لتخزين روابط m3u8 المكتشفة
    private val foundM3u8 = linkedSetOf<String>()
    
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    companion object {
        fun show(fm: FragmentManager) {
            YoutubeSettingsBottomSheet().show(fm, "youtube_settings")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val controlsLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 10, 10, 10)
        }

        val urlInput = EditText(requireContext()).apply {
            hint = "أدخل الرابط هنا..."
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        openBtn = Button(requireContext()).apply { text = "فتح / استخراج" }
        val backBtn = Button(requireContext()).apply { text = "رجوع" }

        controlsLayout.addView(urlInput)
        controlsLayout.addView(openBtn)
        controlsLayout.addView(backBtn)

        webViewContainer = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f 
            )
        }

        mainWebView = WebView(requireContext())
        webViewContainer.addView(mainWebView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        root.addView(controlsLayout)
        root.addView(webViewContainer)

        dialog.setContentView(root)

        setupAdvancedWebView(mainWebView)

        openBtn.setOnClickListener {
            var url = urlInput.text.toString().replace("\\s+".toRegex(), "").trim()
            if (url.isNotEmpty()) {
                if (!url.startsWith("http")) url = "https://$url"
                
                // إخفاء الكيبورد فوراً بعد الضغط
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(urlInput.windowToken, 0)

                foundM3u8.clear()
                Log.d("YoutubeSheet", "Starting new extraction for: $url")

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

        return dialog
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupAdvancedWebView(webView: WebView) {
        val settings = webView.settings

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            allowContentAccess = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false 
            
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            
            userAgentString = USER_AGENT
        }

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
                        synchronized(foundM3u8) {
                            if (!foundM3u8.contains(url)) {
                                foundM3u8.add(url)
                                Log.d("YoutubeSheet", "🎉 FOUND M3U8: $url")
                                Handler(Looper.getMainLooper()).post {
                                    Toast.makeText(requireContext(), "تم العثور على رابط فيديو!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
                return super.onConsoleMessage(cm) 
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val newWebView = WebView(requireContext())
                setupAdvancedWebView(newWebView) 
                
                newWebView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                
                webViewContainer.addView(newWebView)
                popupsList.add(newWebView)

                val transport = resultMsg?.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()

                return true
            }

            override fun onCloseWindow(window: WebView?) {
                super.onCloseWindow(window)
                window?.let {
                    webViewContainer.removeView(it)
                    popupsList.remove(it)
                    it.destroy()
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            
            // 🔴 هذا هو الحل السحري لمشكلة الشاشة السوداء وعدم التحميل!
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed() // تجاهل أخطاء الحماية ومتابعة التحميل بالقوة
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // تغيير النص ليعرف المستخدم أن الصفحة قيد التحميل
                activity?.runOnUiThread {
                    openBtn.text = "جاري التحميل..."
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                
                // إعادة النص بعد انتهاء التحميل
                activity?.runOnUiThread {
                    openBtn.text = "فتح / استخراج"
                }
                
                val js = """
                (function() {
                    try {
                        if (!window.__NET_HOOKED__) {
                            window.__NET_HOOKED__ = true;
                            const _fetch = window.fetch;
                            if (_fetch) {
                                window.fetch = function() {
                                    return _fetch.apply(this, arguments).then(function(resp) {
                                        try {
                                            const u = resp && resp.url ? resp.url : '';
                                            if (u && u.indexOf('.m3u8') !== -1) {
                                                console.log('NET_M3U8::' + u);
                                            }
                                            resp.clone().text().then(function(t){
                                                var m = t && t.match(/https?:\/\/[^"'\\s]+\\.m3u8/);
                                                if (m) console.log('NET_M3U8::' + m[0]);
                                            }).catch(function(){});
                                        } catch(e){}
                                        return resp;
                                    });
                                };
                            }
                            const _open = XMLHttpRequest.prototype.open;
                            XMLHttpRequest.prototype.open = function(method, u) {
                                this.addEventListener('load', function() {
                                    try {
                                        if (typeof u === 'string' && u.indexOf('.m3u8') !== -1) {
                                            console.log('NET_M3U8::' + u);
                                        }
                                        var txt = this.responseText || '';
                                        var m = txt && txt.match(/https?:\/\/[^"'\\s]+\\.m3u8/);
                                        if (m) console.log('NET_M3U8::' + m[0]);
                                    } catch(e){}
                                });
                                return _open.apply(this, arguments);
                            };
                        }
                        
                        setTimeout(function(){
                            var sels = ['.jw-display-icon-container','.jw-icon-play','.jw-svg-icon-play','.jw-display','.jwplayer','#player','.player','video'];
                            for (var i=0;i<sels.length;i++){
                                try {
                                    var el = document.querySelector(sels[i]);
                                    if (el) { el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true})); }
                                } catch(e){}
                            }
                        }, 1000);
                        
                        if (typeof jwplayer === 'function') {
                            try {
                                var p = jwplayer();
                                if (p && typeof p.getPlaylist === 'function') {
                                    var pl = p.getPlaylist();
                                    if (pl && pl.length>0 && pl[0].sources) {
                                        pl[0].sources.forEach(function(s){
                                            if (s && s.file && s.file.indexOf('.m3u8') !== -1) {
                                                console.log('JW_M3U8::' + s.file);
                                            }
                                        });
                                    }
                                }
                            } catch(e){}
                        }
                    } catch(err){}
                })();
                """.trimIndent()
                
                try { view?.evaluateJavascript(js, null) } catch (_: Exception) {}
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                if (url.startsWith("intent://") || url.startsWith("market://") || url.startsWith("tg://")) {
                    return true 
                }
                return false 
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                val method = request.method
                val lowerUrl = url.lowercase()

                if (lowerUrl.startsWith("intent://") || lowerUrl.startsWith("market://") || lowerUrl.startsWith("tg://")) {
                    return WebResourceResponse("text/plain", "utf-8", null) 
                }

                if (method.equals("GET", ignoreCase = true) && 
                    lowerUrl.contains(".m3u8") && 
                    lowerUrl.substringBefore("?").endsWith(".m3u8")) 
                {
                    try {
                        synchronized(foundM3u8) {
                            if (!foundM3u8.contains(url)) {
                                foundM3u8.add(url)
                                Log.d("YoutubeSheet", "🕵️‍♂️ M3U8 intercepted via Network: $url")
                            }
                        }

                        val reqBuilder = Request.Builder().url(url)
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", view.url ?: "") 
                            
                        cookieManager.getCookie(url)?.let { ck -> reqBuilder.header("Cookie", ck) }
                        
                        val response = httpClient.newCall(reqBuilder.build()).execute()
                        if (!response.isSuccessful) return null
                        
                        response.headers("Set-Cookie").forEach { cookieManager.setCookie(url, it) }
                        
                        val contentType = response.header("content-type")?.split(";")?.first() ?: "application/vnd.apple.mpegurl"
                        return WebResourceResponse(contentType, "utf-8", response.body?.byteStream())
                    } catch (e: Exception) {
                        return null
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }
}
