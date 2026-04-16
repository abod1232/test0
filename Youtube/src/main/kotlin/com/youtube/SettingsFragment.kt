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

    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    private val TARGET_HEADER_KEY = "x-requested-with"
    private val TARGET_HEADER_VALUE = "mark.via.gp"

    private val customHeaders = mapOf(
        TARGET_HEADER_KEY to TARGET_HEADER_VALUE
    )

    // ✨ السكربت الشامل الخارق: (1) الهيدرات (2) كسر الـ Debugger (3) قاتل مانع الإعلانات
    private val STEALTH_INJECTION_SCRIPT = """
javascript:(function() {

    // ==========================================
    // 1. حقن الهيدر في كل الطلبات
    // ==========================================
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function() {
        origOpen.apply(this, arguments);
        try { this.setRequestHeader('$TARGET_HEADER_KEY', '$TARGET_HEADER_VALUE'); } catch(e){}
    };

    var origFetch = window.fetch;
    window.fetch = function() {
        var args = arguments;
        if (!args[1]) args[1] = {};
        if (!args[1].headers) args[1].headers = {};
        args[1].headers['$TARGET_HEADER_KEY'] = '$TARGET_HEADER_VALUE';
        return origFetch.apply(this, args);
    };

    // ==========================================
    // 2. تعطيل debugger بالكامل 🔥
    // ==========================================

    // قتل debugger المباشر
    try {
        Object.defineProperty(window, 'debugger', {
            get: () => function(){},
            set: () => {}
        });
    } catch(e){}

    // تعطيل eval
    const _eval = window.eval;
    window.eval = function(code) {
        if (typeof code === 'string') {
            code = code.replace(/debugger\s*;/gi, '');
        }
        return _eval(code);
    };

    // تعطيل Function constructor
    const _Function = Function;
    Function = function(...args) {
        if (args.length) {
            let last = args[args.length - 1];
            if (typeof last === 'string') {
                last = last.replace(/debugger\s*;/gi, '');
                args[args.length - 1] = last;
            }
        }
        return _Function.apply(this, args);
    };

    // تعطيل setTimeout
    const _setTimeout = window.setTimeout;
    window.setTimeout = function(fn, t) {
        if (typeof fn === 'string') {
            fn = fn.replace(/debugger\s*;/gi, '');
        }
        return _setTimeout(fn, t);
    };

    // تعطيل setInterval
    const _setInterval = window.setInterval;
    window.setInterval = function(fn, t) {
        if (typeof fn === 'string') {
            fn = fn.replace(/debugger\s*;/gi, '');
        }
        return _setInterval(fn, t);
    };

    // ==========================================
    // 3. كسر DevTools detection 🧠
    // ==========================================

    console.clear = function(){};
    console.log = console.log.bind(console);
    console.warn = function(){};
    console.error = function(){};

    // منع كشف DevTools عبر timing
    const fakeNow = () => Date.now();
    performance.now = fakeNow;

    // منع كشف debugger عبر الفرق الزمني
    setInterval(() => {
        const start = Date.now();
        debugger;
        const end = Date.now();
        if (end - start > 100) {
            // تم اكتشاف debugger → تجاهله
        }
    }, 1000);

    // ==========================================
    // 4. Anti Anti-Adblock 💀
    // ==========================================

    setInterval(function() {

        const elements = document.querySelectorAll('*');

        for (let el of elements) {
            if (!el.innerText) continue;

            if (
                el.innerText.includes('إيقاف منع الإعلانات') ||
                el.innerText.toLowerCase().includes('adblock') ||
                el.innerText.toLowerCase().includes('disable adblock')
            ) {
                let parent = el;
                while (parent.parentElement &&
                       parent.parentElement !== document.body &&
                       parent.parentElement !== document.documentElement) {
                    parent = parent.parentElement;
                }
                if (parent) parent.remove();
            }
        }

        // إعادة التمرير
        if (document.body) {
            document.body.style.setProperty('overflow', 'auto', 'important');
        }
        if (document.documentElement) {
            document.documentElement.style.setProperty('overflow', 'auto', 'important');
        }

        // خداع الموقع
        window.adblock = false;
        window.isAdBlockActive = false;
        window.canRunAds = true;

    }, 500);

    // ==========================================
    // 5. قتل loops الخطيرة (anti freeze)
    // ==========================================

    const _requestAnimationFrame = window.requestAnimationFrame;
    window.requestAnimationFrame = function(cb) {
        return _requestAnimationFrame(function() {
            try { cb(); } catch(e){}
        });
    };

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
                headersToApply.putAll(customHeaders)
                
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
