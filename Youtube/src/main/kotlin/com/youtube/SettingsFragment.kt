package com.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import java.util.Stack

class YoutubeSettingsBottomSheet : DialogFragment() {

    private lateinit var webContainer: FrameLayout
    private val webStack = Stack<WebView>()

    // ✨ تم تحديث الـ User-Agent إلى متصفح ويندوز كما طلبت
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"

    // ✨ تم إنشاء خريطة تحتوي على كل الهيدرات المطلوبة
    private val customHeaders = mapOf(
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "accept-encoding" to "gzip, deflate, br, zstd",
        "accept-language" to "en-GB,en;q=0.9",
        "cache-control" to "max-age=0",
        "priority" to "u=0, i",
        "sec-ch-ua" to "\"Google Chrome\";v=\"147\", \"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"147\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Windows\"",
        "sec-fetch-dest" to "document",
        "sec-fetch-mode" to "navigate",
        "sec-fetch-site" to "same-origin",
        "sec-fetch-user" to "?1",
        "upgrade-insecure-requests" to "1",
        
    )

    // ✨ السكربت الشامل الخارق (مُحدّث ليحقن كل الهيدرات الجديدة)
    private val STEALTH_INJECTION_SCRIPT = """
        javascript:(function() {
            // ==========================================
            // 1. فرض الهيدرات الكاملة على كل الطلبات المخفية
            // ==========================================
            const headersToInject = {
                'accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7',
                'accept-language': 'en-GB,en;q=0.9',
                'cache-control': 'max-age=0',
                'priority': 'u=0, i',
                'sec-ch-ua': '"Google Chrome";v="147", "Not.A/Brand";v="8", "Chromium";v="147"',
                'sec-ch-ua-mobile': '?0',
                'sec-ch-ua-platform': '"Windows"',
                'sec-fetch-dest': 'document',
                'sec-fetch-mode': 'navigate',
                'sec-fetch-site': 'same-origin',
                'sec-fetch-user': '?1',
                'upgrade-insecure-requests': '1',
                'x-requested-with': 'mark.via.gp'
            };

            var origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function() {
                origOpen.apply(this, arguments);
                for (const key in headersToInject) {
                    this.setRequestHeader(key, headersToInject[key]);
                }
            };
            
            var origFetch = window.fetch;
            window.fetch = function(url, options) {
                let newOptions = options || {};
                newOptions.headers = newOptions.headers || {};
                for (const key in headersToInject) {
                    newOptions.headers[key] = headersToInject[key];
                }
                return origFetch.apply(this, [url, newOptions]);
            };

            // ==========================================
            // 2. كسر حماية الـ Debugger
            // ==========================================
            const _constructor = Function.prototype.constructor;
            Function.prototype.constructor = function(...args) {
                if (args.length > 0) {
                    let lastArg = args[args.length - 1];
                    if (typeof lastArg === 'string' && lastArg.includes('debugger')) {
                        args[args.length - 1] = lastArg.replace(/debugger\s*;/g, '');
                    }
                }
                return _constructor.apply(this, args);
            };
            Function.prototype.constructor.toString = function() { return "function Function() { [native code] }"; };

            const _eval = window.eval;
            window.eval = function(code) {
                if (typeof code === 'string' && code.includes('debugger')) {
                    code = code.replace(/debugger\s*;/g, '');
                }
                return _eval.apply(this, [code]);
            };
            window.eval.toString = function() { return "function eval() { [native code] }"; };
            
            // ==========================================
            // 3. قاتل رسالة "قم بإيقاف منع الإعلانات"
            // ==========================================
            setInterval(function() {
                const elements = document.querySelectorAll('*');
                for (let i = 0; i < elements.length; i++) {
                    const el = elements[i];
                    if (el.innerText && el.innerText.includes('إيقاف منع الإعلانات')) {
                        let overlay = el;
                        while (overlay.parentElement && overlay.parentElement !== document.body && overlay.parentElement !== document.documentElement) {
                            overlay = overlay.parentElement;
                        }
                        if(overlay) overlay.remove();
                    }
                }
                if (document.body && document.body.style.overflow === 'hidden') {
                    document.body.style.setProperty('overflow', 'auto', 'important');
                }
            }, 500); 

        })();
    """.trimIndent()

    companion object {
        fun show(fm: FragmentManager) {
            YoutubeSettingsBottomSheet().show(fm, "browser_pro")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val topBar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(10, 10, 10, 10); setBackgroundColor(Color.DKGRAY); gravity = Gravity.CENTER_VERTICAL }
        val urlInput = EditText(ctx).apply { hint = "أدخل الرابط..."; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f); isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_GO }
        val backBtn = Button(ctx).apply { text = "رجوع" }
        topBar.addView(urlInput); topBar.addView(backBtn)
        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8); max = 100; progress = 0; visibility = View.GONE }
        webContainer = FrameLayout(ctx).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        
        val mainWebView = createWebView(ctx, progressBar, urlInput)
        webStack.push(mainWebView)
        webContainer.addView(mainWebView)
        root.addView(topBar); root.addView(progressBar); root.addView(webContainer)

        urlInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { loadUrlSmart(webStack.peek(), v.text.toString()); true } else false
        }
        backBtn.setOnClickListener { handleBack() }
        
        loadUrlSmart(mainWebView, "https://cimanow.cc")
        return root
    }
    
    private fun loadUrlSmart(webView: WebView, input: String) {
        if (input.isBlank()) return
        val url = if (Patterns.WEB_URL.matcher(input).matches()) {
            if (input.startsWith("http://") || input.startsWith("https://")) input else "https://$input"
        } else { "https://www.google.com/search?q=${Uri.encode(input)}" }
        
        webView.loadUrl(url, customHeaders)
    }

    private fun handleBack() {
        if (webStack.size > 1) {
            val top = webStack.pop(); webContainer.removeView(top); top.destroy()
        } else if (webStack.peek().canGoBack()) {
            webStack.peek().goBack()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, progressBar: ProgressBar, urlInput: EditText): WebView {
        val webView = WebView(context)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val s = webView.settings
        
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        // تعيين الـ User-Agent عالمياً لكل الطلبات
        s.userAgentString = USER_AGENT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)
        s.allowFileAccess = true
        s.allowContentAccess = true
        
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                urlInput.setText(url)
                view?.evaluateJavascript(STEALTH_INJECTION_SCRIPT, null)
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                view?.evaluateJavascript(STEALTH_INJECTION_SCRIPT, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                
                if (url.startsWith("intent://") || url.startsWith("market://")) return true 
                
                val headersToApply = mutableMapOf<String, String>()
                // إضافة كل الهيدرات الجديدة
                headersToApply.putAll(customHeaders)
                
                // إضافة الـ Referer بشكل ديناميكي
                val currentUrl = view?.url
                if (!currentUrl.isNullOrEmpty()) {
                    headersToApply["Referer"] = currentUrl
                }

                view?.loadUrl(url, headersToApply)
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { progressBar.progress = newProgress }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                val newWebView = createWebView(context, progressBar, urlInput)
                webStack.push(newWebView)
                webContainer.addView(newWebView)
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()
                return true
            }
        }
        return webView
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
