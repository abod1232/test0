package com.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.*
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import java.util.Stack

class YoutubeSettingsBottomSheet : DialogFragment() {

    private lateinit var webContainer: FrameLayout
    private val webStack = Stack<WebView>()

    private val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    private val TARGET_HEADER_KEY = "x-requested-with"
    private val TARGET_HEADER_VALUE = "mark.via.gp"

    private val customHeaders = mapOf(
        TARGET_HEADER_KEY to TARGET_HEADER_VALUE
    )

    // 🔥 سكربت قوي (debug + ads + redirect)
    private val STEALTH_INJECTION_SCRIPT = """
        javascript:(function() {

            // منع debugger
            const _eval = window.eval;
            window.eval = function(code) {
                if (typeof code === 'string') code = code.replace(/debugger/g, '');
                return _eval(code);
            };

            const _Function = Function;
            Function = function(...args) {
                if (args.length) {
                    let last = args[args.length - 1];
                    if (typeof last === 'string') {
                        args[args.length - 1] = last.replace(/debugger/g, '');
                    }
                }
                return _Function.apply(this, args);
            };

            // منع redirect
            window.open = function() { return null; };
            history.pushState = function(){};
            history.replaceState = function(){};

            // حذف meta refresh
            document.querySelectorAll("meta[http-equiv='refresh']").forEach(e => e.remove());

            // إزالة رسائل الإعلانات
            setInterval(function() {
                document.querySelectorAll("*").forEach(el => {
                    if (el.innerText && el.innerText.includes("إيقاف منع الإعلانات")) {
                        el.remove();
                    }
                });

                document.body.style.overflow = "auto";
            }, 500);

        })();
    """.trimIndent()

    companion object {
        fun show(fm: FragmentManager) {
            YoutubeSettingsBottomSheet().show(fm, "browser_pro")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 10, 10, 10)
            setBackgroundColor(Color.DKGRAY)
        }

        val urlInput = EditText(ctx).apply {
            hint = "أدخل الرابط..."
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
        }

        val backBtn = Button(ctx).apply { text = "رجوع" }

        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8)
            max = 100
        }

        webContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val webView = createWebView(ctx, progressBar, urlInput)
        webStack.push(webView)
        webContainer.addView(webView)

        topBar.addView(urlInput)
        topBar.addView(backBtn)

        root.addView(topBar)
        root.addView(progressBar)
        root.addView(webContainer)

        urlInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                loadUrlSmart(webStack.peek(), v.text.toString())
                true
            } else false
        }

        backBtn.setOnClickListener { handleBack() }

        loadUrlSmart(webView, "https://rm.freex2line.online/2020/02/blog-post.html/")

        return root
    }

    private fun loadUrlSmart(webView: WebView, input: String) {
        val url = if (Patterns.WEB_URL.matcher(input).matches()) {
            if (input.startsWith("http")) input else "https://$input"
        } else {
            "https://www.google.com/search?q=${Uri.encode(input)}"
        }
        webView.loadUrl(url, customHeaders)
    }

    private fun handleBack() {
        if (webStack.size > 1) {
            val top = webStack.pop()
            webContainer.removeView(top)
            top.destroy()
        } else if (webStack.peek().canGoBack()) {
            webStack.peek().goBack()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, progressBar: ProgressBar, urlInput: EditText): WebView {

        val webView = WebView(context)

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.userAgentString = USER_AGENT

        webView.webViewClient = object : WebViewClient() {

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                view?.evaluateJavascript(STEALTH_INJECTION_SCRIPT, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
                view?.evaluateJavascript(STEALTH_INJECTION_SCRIPT, null)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                val allowedDomain = "rm.freex2line.online"

                // منع أي خروج من الموقع
                if (!url.contains(allowedDomain)) return true

                // منع روابط إعلانات داخلية
                val blocked = listOf("ads", "click", "redirect", "pop", "track", "go")
                if (blocked.any { url.contains(it) }) return true

                view?.loadUrl(url, customHeaders)
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null

                val blocked = listOf("doubleclick", "ads", "popads", "adservice")
                if (blocked.any { url.contains(it) }) {
                    return WebResourceResponse("text/plain", "utf-8", null)
                }

                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.webChromeClient = WebChromeClient()

        return webView
    }
}
