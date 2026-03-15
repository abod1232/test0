package com.youtube

import android.annotation.SuppressLint
import android.app.Dialog
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager

class YoutubeSettingsBottomSheet : DialogFragment() {

    private lateinit var webView: WebView

    companion object {
        fun show(fm: FragmentManager) {
            YoutubeSettingsBottomSheet().show(fm, "anime_settings")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {

        val dialog = Dialog(requireContext())

        val root = LinearLayout(requireContext())
        root.orientation = LinearLayout.VERTICAL

        val urlInput = EditText(requireContext())
        urlInput.hint = "ادخل الرابط"

        val openBtn = Button(requireContext())
        openBtn.text = "فتح"

        val backBtn = Button(requireContext())
        backBtn.text = "رجوع"

        webView = WebView(requireContext())

        root.addView(urlInput)
        root.addView(openBtn)
        root.addView(backBtn)

        root.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1500
            )
        )

        dialog.setContentView(root)

        setupWebView()

        openBtn.setOnClickListener {

            var url = urlInput.text.toString()

            if (!url.startsWith("http")) {
                url = "https://$url"
            }

            webView.loadUrl(url)
        }

        backBtn.setOnClickListener {

            if (webView.canGoBack()) {
                webView.goBack()
            }
        }

        return dialog
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {

        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        settings.allowFileAccess = true
        settings.allowContentAccess = true

        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true

        settings.javaScriptCanOpenWindowsAutomatically = true

        settings.mediaPlaybackRequiresUserGesture = false

        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true

        settings.builtInZoomControls = true
        settings.displayZoomControls = false

        settings.setSupportMultipleWindows(true)

        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        settings.cacheMode = WebSettings.LOAD_DEFAULT

        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

        val cookieManager = CookieManager.getInstance()

        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {

                val newWebView = WebView(requireContext())

                setupChildWebView(newWebView)

                val transport = resultMsg?.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()

                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {

                view?.loadUrl(request?.url.toString())
                return true
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupChildWebView(child: WebView) {

        val settings = child.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportMultipleWindows(true)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        child.webViewClient = webView.webViewClient
        child.webChromeClient = webView.webChromeClient
    }
}
