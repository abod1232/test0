package com.youtube

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
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
                1200
            )
        )

        dialog.setContentView(root)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.loadsImagesAutomatically = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setSupportMultipleWindows(false)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {

                val url = request?.url.toString()

                if (url.startsWith("http") || url.startsWith("https")) {
                    view?.loadUrl(url)
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                    }
                }

                return true
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {

                val url = request?.url.toString()

                if (
                    url.contains(".mp4") ||
                    url.contains(".m3u8") ||
                    url.contains(".mkv")
                ) {
                    if (!links.contains(url)) {
                        links.add(url)
                    }
                }

                return super.shouldInterceptRequest(view, request)
            }
        }

        webView.loadUrl("https://web3156x.faselhdx.bid/video_player?player_token=WTd1RVBsMFFXNzJQRk5vbU42ajFpdEwzZVFXWUxsankrM2dPVWpiR2g2RHN6MTVhbWgyVThyaFpnTTNENUwyMmpxdXh3cDg3azJIWWwzRStIOUdFMzcxS0VsQjVJVFNDUEJYRzFHb2U1VTB2ZDQybGIvZDVnekp3VFM0WS9qWmQ0RHhEWjNuaEJUVjNPUkJqQlFIQ1picEoyYXVqc0VEdDRjVjhvbXhuTUl5S1lSMW9rQVhYd2FRT1ZNWG5RMDJ1aGQwLzNkR205REJINEljWDdxUCs1VjJhUDBhWHVPczBYQzZpR0hwak9GQT06OsqKVDgdpX2BXtzDT%2BbKyJA%3D")

        backBtn.setOnClickListener {

            if (webView.copyBackForwardList().currentIndex > 0) {
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

    private fun showLinks() {

        val dialog = Dialog(requireContext())

        val scroll = ScrollView(requireContext())
        val layout = LinearLayout(requireContext())
        layout.orientation = LinearLayout.VERTICAL

        scroll.addView(layout)

        if (links.isEmpty()) {

            val text = TextView(requireContext())
            text.text = "لا توجد روابط فيديو"
            text.gravity = Gravity.CENTER

            layout.addView(text)

        } else {

            for (link in links) {

                val text = TextView(requireContext())
                text.text = link

                val copyBtn = Button(requireContext())
                copyBtn.text = "نسخ الرابط"

                copyBtn.setOnClickListener {

                    val clipboard = requireContext()
                        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

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
