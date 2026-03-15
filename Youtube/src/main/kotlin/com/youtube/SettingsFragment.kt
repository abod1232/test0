package com.youtube

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager

class YoutubeSettingsBottomSheet : DialogFragment() {

    private val links = mutableListOf<String>()
    private lateinit var webView: WebView

    companion object {

        fun show(fm: FragmentManager, url: String) {

            val sheet = YoutubeSettingsBottomSheet()

            val args = Bundle()
            args.putString("url", url)

            sheet.arguments = args

            sheet.show(fm, "anime_settings")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {

        val dialog = Dialog(requireContext())

        val root = LinearLayout(requireContext())
        root.orientation = LinearLayout.VERTICAL

        val topBar = LinearLayout(requireContext())
        topBar.orientation = LinearLayout.HORIZONTAL

        val backBtn = Button(requireContext())
        backBtn.text = "رجوع"

        val linksBtn = Button(requireContext())
        linksBtn.text = "عرض الروابط"

        topBar.addView(
            backBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        topBar.addView(
            linksBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        webView = WebView(requireContext())

        root.addView(topBar)

        root.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1400
            )
        )

        dialog.setContentView(root)

        setupWebView()

        val url = arguments?.getString("url") ?: "https://google.com"

        webView.loadUrl(url)

        backBtn.setOnClickListener {

            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                dismiss()
            }
        }

        linksBtn.setOnClickListener {
            showLinks()
        }

        return dialog
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {

        val settings = webView.settings

        settings.apply {

            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            allowFileAccess = true
            allowContentAccess = true

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

            userAgentString =
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
        }

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

                val url = request?.url.toString()

                if (url.startsWith("http")) {
                    view?.loadUrl(url)
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {}
                }

                return true
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {

                val url = request?.url.toString()

                if (
                    url.contains(".m3u8") ||
                    url.contains(".mp4") ||
                    url.contains(".mkv")
                ) {

                    if (!links.contains(url)) {
                        links.add(url)
                    }

                    android.util.Log.d("VIDEO_LINK", url)
                }

                return super.shouldInterceptRequest(view, request)
            }
        }
    }

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

    private fun showLinks() {

        val dialog = Dialog(requireContext())

        val scroll = ScrollView(requireContext())
        val layout = LinearLayout(requireContext())
        layout.orientation = LinearLayout.VERTICAL

        scroll.addView(layout)

        if (links.isEmpty()) {

            val text = TextView(requireContext())
            text.text = "لا توجد روابط فيديو"

            layout.addView(text)

        } else {

            for (link in links) {

                val text = TextView(requireContext())
                text.text = link

                val copyBtn = Button(requireContext())
                copyBtn.text = "نسخ الرابط"

                copyBtn.setOnClickListener {

                    val clipboard =
                        requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                    val clip = ClipData.newPlainText("video", link)

                    clipboard.setPrimaryClip(clip)

                    Toast.makeText(
                        requireContext(),
                        "تم نسخ الرابط",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                layout.addView(text)
                layout.addView(copyBtn)
            }
        }

        dialog.setContentView(scroll)
        dialog.show()
    }
}
