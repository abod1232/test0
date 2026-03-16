package com.youtube

import android.annotation.SuppressLint
import android.app.AlertDialog
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
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import okhttp3.OkHttpClient
import okhttp3.Request

class YoutubeSettingsBottomSheet : DialogFragment() {

    private lateinit var webViewContainer: FrameLayout
    private lateinit var mainWebView: WebView
    
    // --- متغير الحالة القصوى: true = صيد روابط، false = متصفح طبيعي لتخطي الحماية ---
    private var isMonitoringEnabled = true 

    private val popupsList = mutableListOf<WebView>()
    private val httpClient = OkHttpClient.Builder().build()
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
        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        val buttonsLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#222222"))
            gravity = Gravity.CENTER_VERTICAL
        }

        val closeBtn = Button(ctx).apply { text = "✕"; setTextColor(Color.WHITE); setBackgroundColor(Color.RED) }
        val urlInput = EditText(ctx).apply {
            hint = "الرابط هنا..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        // زر التبديل بين وضع "الصيد" ووضع "التخطي"
        val toggleModeBtn = Button(ctx).apply {
            text = "وضع الصيد: ON"
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setTextColor(Color.WHITE)
            textSize = 10f
        }

        val playBtn = Button(ctx).apply { text = "فتح"; setBackgroundColor(Color.BLUE); setTextColor(Color.WHITE) }
        val copyBtn = Button(ctx).apply { text = "نسخ"; setBackgroundColor(Color.MAGENTA); setTextColor(Color.WHITE); visibility = View.GONE }

        buttonsLayout.addView(closeBtn)
        buttonsLayout.addView(urlInput)
        buttonsLayout.addView(toggleModeBtn)
        buttonsLayout.addView(playBtn)
        buttonsLayout.addView(copyBtn)

        webViewContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.BLACK)
        }

        mainWebView = WebView(ctx)
        webViewContainer.addView(mainWebView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        rootLayout.addView(buttonsLayout)
        rootLayout.addView(webViewContainer)

        setupFaselWebView(mainWebView, copyBtn, isMainPlayer = true)

        toggleModeBtn.setOnClickListener {
            isMonitoringEnabled = !isMonitoringEnabled
            if (isMonitoringEnabled) {
                toggleModeBtn.text = "وضع الصيد: ON"
                toggleModeBtn.setBackgroundColor(Color.parseColor("#4CAF50"))
                Toast.makeText(ctx, "تم تفعيل وضع صيد الروابط", Toast.LENGTH_SHORT).show()
            } else {
                toggleModeBtn.text = "وضع التخطي: OFF"
                toggleModeBtn.setBackgroundColor(Color.parseColor("#FF5722"))
                Toast.makeText(ctx, "وضع التصفح الطبيعي (لتخطي كلاود فلير)", Toast.LENGTH_LONG).show()
            }
            // إعادة تحميل الصفحة لتطبيق الوضع الجديد بدون تدخل
            mainWebView.reload()
        }

        playBtn.setOnClickListener {
            var url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                if (!url.startsWith("http")) url = "https://$url"
                currentIframeUrl = url
                extractedVideoUrl = null
                copyBtn.visibility = View.GONE
                mainWebView.loadUrl(url)
            }
        }

        closeBtn.setOnClickListener { dismiss() }
        copyBtn.setOnClickListener {
            extractedVideoUrl?.let {
                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("URL", it))
                Toast.makeText(ctx, "تم النسخ!", Toast.LENGTH_SHORT).show()
            }
        }

        return rootLayout
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupFaselWebView(webView: WebView, copyBtn: Button, isMainPlayer: Boolean) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.userAgentString = USER_AGENT
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                if (!isMonitoringEnabled) return true // تجاهل الكونسول في وضع التخطي
                val msg = cm?.message() ?: ""
                if (msg.contains("NET_M3U8::")) {
                    extractedVideoUrl = msg.substringAfter("::")
                    Handler(Looper.getMainLooper()).post { copyBtn.visibility = View.VISIBLE }
                }
                return true
            }

            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                if (!isMonitoringEnabled) return false // لا تفتح نوافذ إعلانية في وضع التخطي
                val newWebView = WebView(requireContext())
                setupFaselWebView(newWebView, copyBtn, false)
                newWebView.layoutParams = FrameLayout.LayoutParams(1, 1)
                webViewContainer.addView(newWebView)
                popupsList.add(newWebView)
                (resultMsg?.obj as? WebView.WebViewTransport)?.webView = newWebView
                resultMsg?.sendToTarget()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // لا نحقن أي كود إذا كانت المراقبة مطفأة (هذا هو سر تخطي كلاود فلير)
                if (isMainPlayer && isMonitoringEnabled) {
                    val js = """
                    (function() {
                        const _open = XMLHttpRequest.prototype.open;
                        XMLHttpRequest.prototype.open = function(method, u) {
                            this.addEventListener('load', function() {
                                if (u.includes('.m3u8')) console.log('NET_M3U8::' + u);
                            });
                            return _open.apply(this, arguments);
                        };
                        setInterval(() => {
                           document.querySelectorAll('.jw-display-icon-container, .vjs-big-play-button').forEach(b => b.click());
                        }, 2000);
                    })();
                    """.trimIndent()
                    view?.evaluateJavascript(js, null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                if (!isMonitoringEnabled) return false // اسمح بكافة التحويلات في وضع التخطي
                
                if (isMainPlayer && !url.contains(currentIframeUrl) && !url.contains("google")) return true
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url.toString()
                
                // أهم خطوة: إذا كانت المراقبة مطفأة، أعد null فوراً
                // هذا يجعل المتصفح يرسل الطلب بنفس الـ TLS و Headers الأصلية التي يتوقعها Cloudflare
                if (!isMonitoringEnabled) return null 

                // منطق الصيد (فقط عندما يكون مفعل)
                if (url.contains(".m3u8") || url.contains(".mp4")) {
                    extractedVideoUrl = url
                    Handler(Looper.getMainLooper()).post { copyBtn.visibility = View.VISIBLE }
                }
                
                return super.shouldInterceptRequest(view, request)
            }
        }
    }
}
