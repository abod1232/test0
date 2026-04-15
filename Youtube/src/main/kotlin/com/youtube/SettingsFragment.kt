package com.youtube

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
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

    private lateinit var mainWebView: WebView
    private lateinit var webContainer: FrameLayout

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
    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

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

        val openBtn = Button(ctx).apply {
            text = "فتح"
        }

        val backBtn = Button(ctx).apply {
            text = "رجوع"
        }

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
        webContainer.addView(mainWebView)

        root.addView(topBar)
        root.addView(webContainer)

        openBtn.setOnClickListener {
            val url = urlInput.text.toString()
            mainWebView.loadUrl(url, headers)
        }

        backBtn.setOnClickListener {
            if (mainWebView.canGoBack()) {
                mainWebView.goBack()
            }
        }

        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {

        val webView = WebView(requireContext())

        val s = webView.settings

        // 🔥 تفعيل JS بالكامل
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.loadsImagesAutomatically = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // 🔥 Desktop behavior
        s.useWideViewPort = true
        s.loadWithOverviewMode = true

        // 🔥 UA
        s.userAgentString = USER_AGENT

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {

                val url = request?.url.toString()

                // 🔥 معالجة intent://
                if (url.startsWith("intent://")) {
                    try {
                        val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                        startActivity(intent)
                    } catch (e: Exception) {
                        // تجاهل إذا فشل
                    }
                    return true
                }

                // 🔥 فتح بالخلف بدون التأثير
                val bgWebView = createWebView()
                bgWebView.loadUrl(url, headers)

                webContainer.addView(bgWebView)
                bgWebView.visibility = View.GONE // 👈 مخفي (خلفي)

                return true
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

                webContainer.addView(newWebView)

                val transport = resultMsg?.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()

                return true
            }
        }

        return webView
    }
}
