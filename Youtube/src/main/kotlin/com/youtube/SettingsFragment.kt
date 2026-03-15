package com.youtube

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager

class YoutubeSettingsBottomSheet : DialogFragment() {

    private lateinit var webViewContainer: FrameLayout
    private lateinit var mainWebView: WebView
    private val popupsList = mutableListOf<WebView>()

    companion object {
        fun show(fm: FragmentManager) {
            YoutubeSettingsBottomSheet().show(fm, "video_player_dialog")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()

        // 1. الحاوية الرئيسية
        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#121212"))
        }

        // 2. شريط الأدوات العلوي
        val buttonsLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#222222"))
            gravity = Gravity.CENTER_VERTICAL
        }

        val closeBtn = Button(ctx).apply { 
            text = "إغلاق"
            setTextColor(Color.RED)
            setBackgroundColor(Color.TRANSPARENT) 
        }

        val urlInput = EditText(ctx).apply {
            hint = "أدخل رابط المشغل..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(20, 0, 20, 0)
        }

        val playBtn = Button(ctx).apply { 
            text = "تشغيل"
            setBackgroundColor(Color.parseColor("#007AFF"))
            setTextColor(Color.WHITE) 
        }

        buttonsLayout.addView(closeBtn)
        buttonsLayout.addView(urlInput)
        buttonsLayout.addView(playBtn)

        // 3. حاوية الفيديو
        webViewContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.BLACK)
        }

        mainWebView = WebView(ctx)
        webViewContainer.addView(mainWebView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        rootLayout.addView(buttonsLayout)
        rootLayout.addView(webViewContainer)

        // إعداد الويبفيو بصلاحيات قصوى
        setupVideoWebView(mainWebView)

        // 4. برمجة الأزرار
        closeBtn.setOnClickListener { 
            if (popupsList.isNotEmpty()) {
                val lastPopup = popupsList.removeLast()
                webViewContainer.removeView(lastPopup)
                lastPopup.destroy()
            } else {
                dismiss() 
            }
        }

        playBtn.setOnClickListener {
            var url = urlInput.text.toString().replace("\\s+".toRegex(), "").trim()
            if (url.isNotEmpty()) {
                if (!url.startsWith("http")) url = "https://$url"
                
                // إخفاء الكيبورد
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(urlInput.windowToken, 0)

                // تمرير الـ Referer والـ Origin
                val headers = mutableMapOf(
                    "Referer" to url,
                    "Origin" to url.substringBeforeLast("/") 
                )
                mainWebView.loadUrl(url, headers)
            }
        }

        return rootLayout
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val width = (resources.displayMetrics.widthPixels * 0.95).toInt()
        val height = (resources.displayMetrics.heightPixels * 0.95).toInt()
        dialog?.window?.setLayout(width, height)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupVideoWebView(webView: WebView) {
        val settings = webView.settings

        // 1. صلاحيات الجافاسكربت والتخزين (تم حذف setAppCacheEnabled للخطأ)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        
        // 2. صلاحيات التشغيل
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true

        // 3. صلاحيات النوافذ والـ iframes
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        
        // 4. صلاحيات العرض والبروتوكولات
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // 5. انتحال متصفح كروم حقيقي على جهاز كمبيوتر
        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        // 6. تسريع الهاردوير
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        // 7. إعدادات الكوكيز
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // التعامل مع الإعلانات وملء الشاشة
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val newWebView = WebView(requireContext())
                setupVideoWebView(newWebView) 

                newWebView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                
                webViewContainer.addView(newWebView)
                popupsList.add(newWebView)

                val transport = resultMsg?.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()

                return true
            }

            override fun onCloseWindow(window: WebView?) {
                super.onCloseWindow(window)
                window?.let {
                    webViewContainer.removeView(it)
                    popupsList.remove(it)
                    it.destroy()
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                
                if (url.startsWith("intent://") || url.startsWith("market://") || url.startsWith("tg://") || url.startsWith("viber://") || url.startsWith("whatsapp://")) {
                    return true 
                }
                
                return false
            }

            // 8. تجاوز أخطاء SSL
            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.proceed() 
            }
        }
    }
}
