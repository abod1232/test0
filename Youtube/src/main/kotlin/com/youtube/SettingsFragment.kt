package com.youtube

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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

        val topBar = LinearLayout(requireContext())
        topBar.orientation = LinearLayout.HORIZONTAL

        val backBtn = Button(requireContext())
        backBtn.text = "رجوع"

        val linksBtn = Button(requireContext())
        linksBtn.text = "عرض الروابط"

        topBar.addView(backBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,1f))

        topBar.addView(linksBtn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT,1f))

        webView = WebView(requireContext())

        root.addView(topBar)

        root.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                1200
            )
        )

        dialog.setContentView(root)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): android.webkit.WebResourceResponse? {

                val url = request?.url.toString()

                if (url.contains(".mp4") ||
                    url.contains(".m3u8") ||
                    url.contains("videoplayback")
                ) {

                    if (!links.contains(url)) {
                        links.add(url)
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.loadUrl("https://web3156x.faselhdx.bid/main")

        backBtn.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            }
        }

        linksBtn.setOnClickListener {
            showLinks()
        }

        return dialog
    }

    private fun showLinks() {

        val dialog = Dialog(requireContext())

        val scroll = ScrollView(requireContext())
        val layout = LinearLayout(requireContext())
        layout.orientation = LinearLayout.VERTICAL

        scroll.addView(layout)

        if (links.isEmpty()) {

            val text = TextView(requireContext())
            text.text = "لا توجد روابط"
            text.gravity = Gravity.CENTER

            layout.addView(text)

        } else {

            for (link in links) {

                val text = TextView(requireContext())
                text.text = link

                val copy = Button(requireContext())
                copy.text = "نسخ الرابط"

                copy.setOnClickListener {

                    val clipboard =
                        requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                    val clip = ClipData.newPlainText("video", link)

                    clipboard.setPrimaryClip(clip)

                    Toast.makeText(
                        requireContext(),
                        "تم النسخ",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                layout.addView(text)
                layout.addView(copy)
            }
        }

        dialog.setContentView(scroll)
        dialog.show()
    }
}
