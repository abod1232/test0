package com.youtube

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager

class YoutubeSettingsBottomSheet : DialogFragment() {

    private lateinit var webView: WebView

    companion object {
        fun show(fm: FragmentManager) {
            YoutubeSettingsBottomSheet().show(fm, "browser_headers")
        }
    }

    private val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; M2012K11AG Build/TKQ1.221114.001) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.7727.55 Mobile Safari/537.36"

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "accept-language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7",
        "referer" to "https://www.google.com/",
        "x-requested-with" to "mark.via.gp",
        "upgrade-insecure-requests" to "1"
    )

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        val ctx = requireContext()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // 🔝 Top Bar
        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 10, 10, 10)
            setBackgroundColor(Color.parseColor("#222222"))
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val urlInput = EditText(ctx).apply {
            hint = "أدخل الرابط..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setSingleLine(true)
        }

        val openBtn = Button(ctx).apply {
            text = "فتح"
            setBackgroundColor(Color.parseColor("#007AFF"))
            setTextColor(Color.WHITE)
        }

        topBar.addView(urlInput)
        topBar.addView(openBtn)

        // 🌐 WebView
        webView = WebView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val containerView = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(Color.BLACK)
            addView(webView)
        }

        root.addView(topBar)
        root.addView(containerView)

        setupWebView()

        openBtn.setOnClickListener {
            val input = urlInput.text.toString().trim()
            if (input.isNotEmpty()) {
                val url = if (input.startsWith("http")) input else "https://$input"
                webView.loadUrl(url, headers)
            }
        }

        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {

        val s = webView.settings

        // 🔥 تفعيل كل إمكانيات JS
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.loadsImagesAutomatically = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // 🔥 تحسين الأداء
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // 🔥 UA
        s.userAgentString = USER_AGENT

        // 🔥 كوكيز
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        WebView.setWebContentsDebuggingEnabled(true)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                view?.loadUrl(request?.url.toString(), headers)
                return true
            }
        }

        webView.webChromeClient = WebChromeClient()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }
}
