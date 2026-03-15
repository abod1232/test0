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

    companion object {
        fun show(fm: FragmentManager) {
            YoutubeSettingsBottomSheet().show(fm, "anime_settings")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {

        val dialog = Dialog(requireContext())

        val layout = LinearLayout(requireContext())
        layout.orientation = LinearLayout.VERTICAL

        val button = Button(requireContext())
        button.text = "عرض الروابط"

        val webView = WebView(requireContext())

        layout.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        layout.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        dialog.setContentView(layout)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): android.webkit.WebResourceResponse? {

                val url = request?.url.toString()

                if (
                    url.contains(".mp4") ||
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

        webView.loadUrl("https://www.youtube.com")

        button.setOnClickListener {
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

                val itemLayout = LinearLayout(requireContext())
                itemLayout.orientation = LinearLayout.VERTICAL

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

                itemLayout.addView(text)
                itemLayout.addView(copy)

                layout.addView(itemLayout)
            }
        }

        dialog.setContentView(scroll)
        dialog.show()
    }
}
