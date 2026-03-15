package com.youtube

import android.annotation.SuppressLint
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
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    val links = mutableListOf<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL

        val button = Button(this)
        button.text = "عرض الروابط"

        val webView = WebView(this)

        layout.addView(button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        layout.addView(webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(layout)

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
    }

    fun showLinks() {

        val dialog = android.app.Dialog(this)

        val scroll = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL

        scroll.addView(layout)

        if (links.isEmpty()) {

            val text = TextView(this)
            text.text = "لا توجد روابط"
            text.gravity = Gravity.CENTER

            layout.addView(text)

        } else {

            for (link in links) {

                val itemLayout = LinearLayout(this)
                itemLayout.orientation = LinearLayout.VERTICAL

                val text = TextView(this)
                text.text = link

                val copy = Button(this)
                copy.text = "نسخ الرابط"

                copy.setOnClickListener {

                    val clipboard =
                        getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

                    val clip = ClipData.newPlainText("video", link)

                    clipboard.setPrimaryClip(clip)

                    Toast.makeText(
                        this,
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
