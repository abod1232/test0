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

    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-A536B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36"

    private val baseCustomHeaders = mapOf(
        "x-requested-with" to "mark.via.gp"
    )

    // ✨ هذا هو السكربت السحري الذي يكسر حماية التوقف (Anti-Debugger)
    private val ANTI_DEBUGGER_SCRIPT = """
        javascript:(function() {
            // 1. تعطيل استدعاء الـ debugger عبر الدالة constructor
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

            // 2. تعطيل استدعاء الـ debugger عبر eval
            const _eval = window.eval;
            window.eval = function(code) {
                if (typeof code === 'string' && code.includes('debugger')) {
                    code = code.replace(/debugger\s*;/g, '');
                }
                return _eval.apply(this, [code]);
            };

            // 3. تعطيل استدعاء الـ debugger عبر setInterval
            const _setInterval = window.setInterval;
            window.setInterval = function(fn, time, ...args) {
                if (typeof fn === 'string' && fn.includes('debugger')) {
                    fn = fn.replace(/debugger\s*;/g, '');
                }
                return _setInterval(fn, time, ...args);
            };
            
            // 4. إخفاء رسائل الـ Console التي يرسلونها للتمويه
            console.clear = function() {};
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
        loadUrlSmart(mainWebView, "https://google.com")
        return root
    }
    
    private fun loadUrlSmart(webView: WebView, input: String) {
        if (input.isBlank()) return
        val url = if (Patterns.WEB_URL.matcher(input).matches()) {
            if (input.startsWith("http://") || input.startsWith("https://")) input else "https://$input"
        } else { "https://www.google.com/search?q=${Uri.encode(input)}" }
        webView.loadUrl(url, baseCustomHeaders)
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
                
                // ✨ حقن السكربت بمجرد بدء تحميل الصفحة لتعطيل فخ التوقف
                view?.evaluateJavascript(ANTI_DEBUGGER_SCRIPT, null)
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                
                // ✨ حقن السكربت مرة أخرى للتأكد من تعطيله في حال تم تغييره بعد التحميل
                view?.evaluateJavascript(ANTI_DEBUGGER_SCRIPT, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("intent://") || url.startsWith("market://")) return true
                
                val dynamicHeaders = mutableMapOf<String, String>()
                dynamicHeaders.putAll(baseCustomHeaders)
                
                val currentUrl = view?.url
                if (!currentUrl.isNullOrEmpty()) {
                    dynamicHeaders["Referer"] = currentUrl
                }

                view?.loadUrl(url, dynamicHeaders)
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
