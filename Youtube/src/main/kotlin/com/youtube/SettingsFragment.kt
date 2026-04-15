package com.youtube

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import java.util.Stack

class YoutubeSettingsBottomSheet : DialogFragment() {

    private lateinit var webContainer: FrameLayout
    private lateinit var mainWebView: WebView
    private val webStack = Stack<WebView>()

    companion object {
        fun show(fm: FragmentManager) {
            YoutubeSettingsBottomSheet().show(fm, "browser_pro")
        }
    }

    private val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; M2012K11AG) AppleWebKit/537.36 Chrome/147.0.7727.55 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "x-requested-with" to "mark.via.gp"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): android.view.View {

        val ctx = requireContext()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 10, 10, 10)
            setBackgroundColor(Color.DKGRAY)
            gravity = Gravity.CENTER_VERTICAL
        }

        val urlInput = EditText(ctx).apply {
            hint = "أدخل الرابط..."
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val openBtn = Button(ctx).apply { text = "فتح" }
        val backBtn = Button(ctx).apply { text = "رجوع" }

        topBar.addView(urlInput)
        topBar.addView(openBtn)
        topBar.addView(backBtn)

        webContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        mainWebView = createWebView()
        webStack.push(mainWebView)
        webContainer.addView(mainWebView)

        root.addView(topBar)
        root.addView(webContainer)

        openBtn.setOnClickListener {
            val url = urlInput.text.toString()
            if (url.isNotEmpty()) {
                mainWebView.loadUrl(url, headers)
            }
        }

        backBtn.setOnClickListener {
            handleBack()
        }

        return root
    }

    private fun handleBack() {
        if (webStack.size > 1) {
            val top = webStack.pop()
            webContainer.removeView(top)
            top.destroy()
        } else {
            val current = webStack.peek()
            if (current.canGoBack()) {
                current.goBack()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {

        val webView = WebView(requireContext())

        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.loadsImagesAutomatically = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        s.userAgentString = USER_AGENT

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {

                val url = request?.url.toString()

                // 🔥 منع intent
                if (url.startsWith("intent://")) return true

                // 🔥 منع market
                if (url.startsWith("market://")) return true

                view?.loadUrl(url, headers)
                return true
            }

            // 🔥 منع إعادة تشغيل intent عبر JS
            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(
                    """
                    (function() {
                        const oldOpen = window.open;
                        window.open = function(url) {
                            if (url && url.startsWith('intent://')) {
                                return null;
                            }
                            return oldOpen.apply(this, arguments);
                        };
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {

                val newWebView = createWebView()

                webStack.push(newWebView)
                webContainer.addView(newWebView)

                val transport = resultMsg?.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()

                return true
            }
        }

        return webView
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
}
