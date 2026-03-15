package com.youtube

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager

class YoutubeSettingsBottomSheet : DialogFragment() {

    private lateinit var webViewContainer: FrameLayout
    private lateinit var mainWebView: WebView

    companion object {
        fun show(fm: FragmentManager) {
            YoutubeSettingsBottomSheet().show(fm, "anime_settings")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())

        // 1. الواجهة الرئيسية
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 2. شريط الإدخال والأزرار
        val controlsLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 10, 10, 10)
        }

        val urlInput = EditText(requireContext()).apply {
            hint = "أدخل الرابط هنا..."
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val openBtn = Button(requireContext()).apply { text = "فتح" }
        val backBtn = Button(requireContext()).apply { text = "رجوع" }

        controlsLayout.addView(urlInput)
        controlsLayout.addView(openBtn)
        controlsLayout.addView(backBtn)

        // 3. حاوية الـ WebView (مهمة جداً للتعامل مع النوافذ المنبثقة والإعلانات)
        webViewContainer = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT // أخذ باقي الشاشة بدلاً من رقم ثابت
            )
        }

        mainWebView = WebView(requireContext())
        webViewContainer.addView(mainWebView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        root.addView(controlsLayout)
        root.addView(webViewContainer)

        dialog.setContentView(root)

        // 4. إعداد الـ WebView
        setupWebView(mainWebView)

        // 5. الأوامر
        openBtn.setOnClickListener {
            var url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                if (!url.startsWith("http")) url = "https://$url"
                
                // 🔴 مهم جداً للمشغلات: تمرير الـ Referer
                val headers = mapOf("Referer" to url)
                mainWebView.loadUrl(url, headers)
            }
        }

        backBtn.setOnClickListener {
            if (mainWebView.canGoBack()) {
                mainWebView.goBack()
            }
        }

        return dialog
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        val settings = webView.settings

        // إعدادات الجافاسكربت والتخزين
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        // إعدادات الفيديو والتشغيل
        settings.mediaPlaybackRequiresUserGesture = false // السماح بالتشغيل التلقائي
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true

        // إعدادات النوافذ
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        
        // إعدادات العرض
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

        // 🔴 تفعيل تسريع الأجهزة (ضروري جداً لرندرة الفيديو HTML5)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            
            // 🔴 حل مشكلة تعليق المشغل بسبب النوافذ المنبثقة (الإعلانات)
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val newWebView = WebView(requireContext())
                setupWebView(newWebView) // نسخ نفس الإعدادات

                // يجب إضافة النافذة الجديدة للحاوية لكي يتم إنشاؤها فعلياً وتستكمل سكربتات المشغل عملها
                newWebView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                webViewContainer.addView(newWebView)

                val transport = resultMsg?.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()

                return true
            }

            // لدعم ملء الشاشة (Fullscreen) إذا تم الضغط عليه داخل المشغل
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                super.onShowCustomView(view, callback)
                view?.let { webViewContainer.addView(it) }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                
                // 🔴 فلترة الروابط الخبيثة التي تسبب كراش للويبفيو (مثل intent:// و market://)
                if (url.startsWith("intent://") || url.startsWith("market://") || url.startsWith("tg://")) {
                    return true // إيقاف التحميل
                }
                
                // السماح للويبفيو بالتعامل مع الروابط العادية (http/https) بنفسه (return false أفضل من view.loadUrl)
                return false 
            }
            
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // يمكن إضافة ProgressBar هنا مستقبلاً
            }
        }
    }
}
