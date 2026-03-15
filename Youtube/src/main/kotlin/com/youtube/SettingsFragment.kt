package com.youtube

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
        fun show(fm: FragmentManager) {
            YoutubeSettingsBottomSheet().show(fm, "anime_settings")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {

        val dialog = Dialog(requireContext())

        val root = LinearLayout(requireContext())
        root.orientation = LinearLayout.VERTICAL

        // ===== Top controls =====

        val urlInput = EditText(requireContext())
        urlInput.hint = "ادخل رابط الموقع"

        val openBtn = Button(requireContext())
        openBtn.text = "فتح الرابط"

        val backBtn = Button(requireContext())
        backBtn.text = "رجوع"

        val linksBtn = Button(requireContext())
        linksBtn.text = "عرض الروابط"

        val controls = LinearLayout(requireContext())
        controls.orientation = LinearLayout.VERTICAL

        controls.addView(urlInput)
        controls.addView(openBtn)
        controls.addView(backBtn)
        controls.addView(linksBtn)

        // ===== WebView =====

        webView = WebView(requireContext())

        root.addView(controls)

        root.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1400
            )
        )

        dialog.setContentView(root)

        setupWebView()

        // ===== فتح الرابط =====

        openBtn.setOnClickListener {

            var url = urlInput.text.toString()

            if (!url.startsWith("http")) {
                url = "https://$url"
            }

            webView.loadUrl(url)
        }

        // ===== زر الرجوع =====

        backBtn.setOnClickListener {

            if (webView.canGoBack()) {
                webView.goBack()
            }
        }

        // ===== عرض الروابط =====

        linksBtn.setOnClickListener {
            showLinks()
        }

        return dialog
    }

    // ===== إعداد WebView بصلاحيات كاملة =====

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
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {}

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {

                view?.loadUrl(request?.url.toString())
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

                }

                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    // ===== عرض الروابط =====

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
