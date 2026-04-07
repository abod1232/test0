package com.youtube

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager

class YoutubeSettingsBottomSheet : DialogFragment() {

    private lateinit var webViewContainer: FrameLayout
    private lateinit var mainWebView: WebView
    private var isMonitoringEnabled = false // نبدأ بوضع التخطي (OFF) لضمان فتح أي موقع

    private var currentIframeUrl: String = ""
    private var extractedVideoUrl: String? = null
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    companion object {
        fun show(fm: FragmentManager) {
            YoutubeSettingsBottomSheet().show(fm, "video_player_dialog")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()

        
        // الحاوية الرئيxjdسية
        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // شريط الأدوات العلوي
        val buttonsLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 10, 10, 10)
            setBackgroundColor(Color.parseColor("#222222"))
            gravity = Gravity.CENTER_VERTICAL
        }

        val urlInput = EditText(ctx).apply {
            hint = "أدخل الرابط هنا..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setSingleLine(true)
        }

        val toggleModeBtn = Button(ctx).apply {
            text = "وضع التخطي: فعّال"
            setBackgroundColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            textSize = 10f
        }

        val playBtn = Button(ctx).apply {
            text = "فتح"
            setBackgroundColor(Color.parseColor("#007AFF"))
            setTextColor(Color.WHITE)
        }

        val copyBtn = Button(ctx).apply {
            text = "نسخ الرابط"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }

        buttonsLayout.addView(urlInput)
        buttonsLayout.addView(toggleModeBtn)
        buttonsLayout.addView(playBtn)
        buttonsLayout.addView(copyBtn)

        // حاوية الـ WebView مع التأكد من شغل المساحة
        webViewContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT, 1.0f
            )
            setBackgroundColor(Color.BLACK)
        }

        mainWebView = WebView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        
        webViewContainer.addView(mainWebView)
        rootLayout.addView(buttonsLayout)
        rootLayout.addView(webViewContainer)

        setupWebViewSettings(mainWebView, copyBtn)

        // أزرار التحكم
        toggleModeBtn.setOnClickListener {
            isMonitoringEnabled = !isMonitoringEnabled
            if (isMonitoringEnabled) {
                toggleModeBtn.text = "وضع الصيد: ON"
                toggleModeBtn.setBackgroundColor(Color.parseColor("#4CAF50"))
                Toast.makeText(ctx, "المراقبة تعمل (اقتناص الروابط)", Toast.LENGTH_SHORT).show()
            } else {
                toggleModeBtn.text = "وضع التخطي: فعّال"
                toggleModeBtn.setBackgroundColor(Color.DKGRAY)
                Toast.makeText(ctx, "وضع التخطي (كلاود فلير)", Toast.LENGTH_SHORT).show()
            }
        }

        playBtn.setOnClickListener {
            val input = urlInput.text.toString().trim()
            if (input.isNotEmpty()) {
                val url = if (input.startsWith("http")) input else "https://$input"
                currentIframeUrl = url
                extractedVideoUrl = null
                copyBtn.visibility = View.GONE
                
                // تنظيف الكوكيز والذاكرة قبل الفتح الجديد
                mainWebView.clearHistory()
                val headers = mapOf(
    "Connection" to "keep-alive",
    "sec-ch-ua" to "\"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\", \"Google Chrome\";v=\"146\"",
    "sec-ch-ua-mobile" to "?1",
    "sec-ch-ua-platform" to "\"Android\"",
    "Upgrade-Insecure-Requests" to "1",
    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Sec-Fetch-Site" to "none",
    "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Dest" to "document",
    "Accept-Encoding" to "gzip, deflate, br, zstd",
    "Accept-Language" to "ar-EG,ar;q=0.9"
)

mainWebView.loadUrl(url, headers)
            }
        }

        copyBtn.setOnClickListener {
            extractedVideoUrl?.let {
                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("VideoLink", it))
                Toast.makeText(ctx, "تم نسخ الرابط!", Toast.LENGTH_SHORT).show()
            }
        }

        return rootLayout
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebViewSettings(webView: WebView, copyBtn: Button) {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.userAgentString = USER_AGENT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        
        // تفعيل تسريع الأجهزة
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webViewClient = object : WebViewClient() {
            
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                
                // في وضع التخطي: لا تمنع أي تحويلات
                if (!isMonitoringEnabled) return false
                
                // في وضع المراقبة: امنع الإعلانات (أي رابط لا يحتوي على الرابط الأصلي)
                if (currentIframeUrl.isNotEmpty() && !url.contains(currentIframeUrl) && !url.contains("cloudflare")) {
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // لا نحقن أي جافا سكريبت إذا كان وضع التخطي فعالاً (عشان كلاود فلير)
                if (isMonitoringEnabled) {
                    val js = """
                        (function() {
                            const _open = XMLHttpRequest.prototype.open;
                            XMLHttpRequest.prototype.open = function(method, u) {
                                this.addEventListener('load', function() {
                                    if (u.includes('.m3u8') || u.includes('.mp4')) console.log('LOG_LINK::' + u);
                                });
                                return _open.apply(this, arguments);
                            };
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(js, null)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                Log.e("WebViewError", "Error loading: ${error?.description}")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                val msg = cm?.message() ?: ""
                if (isMonitoringEnabled && msg.contains("LOG_LINK::")) {
                    extractedVideoUrl = msg.substringAfter("::")
                    Handler(Looper.getMainLooper()).post { copyBtn.visibility = View.VISIBLE }
                }
                return true
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
