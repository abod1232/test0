package com.youtube

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager

class YoutubeSettingsBottomSheet : DialogFragment() {

    private lateinit var webViewContainer: FrameLayout
    private lateinit var mainWebView: WebView
    
    // 🔴 قائمة لتتبع النوافذ المنبثقة (الإعلانات) لإغلاقها عند الضغط على رجوع
    private val popupsList = mutableListOf<WebView>()

    companion object {
        fun show(fm: FragmentManager) {
            YoutubeSettingsBottomSheet().show(fm, "anime_settings")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext())

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

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

        // 🔴 الحل السحري لمشكلة الشاشة السوداء وعدم التحميل (استخدام weight)
        webViewContainer = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0, // الارتفاع 0
                1f // نجعله يأخذ باقي مساحة الشاشة بالكامل
            )
        }

        mainWebView = WebView(requireContext())
        webViewContainer.addView(mainWebView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        root.addView(controlsLayout)
        root.addView(webViewContainer)

        dialog.setContentView(root)

        setupWebView(mainWebView)

        openBtn.setOnClickListener {
            // تنظيف الرابط من المسافات المخفية
            var url = urlInput.text.toString().replace("\\s+".toRegex(), "").trim()
            if (url.isNotEmpty()) {
                if (!url.startsWith("http")) url = "https://$url"
                
                // إخفاء الكيبورد فوراً بعد الضغط
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(urlInput.windowToken, 0)

                val headers = mapOf("Referer" to url)
                mainWebView.loadUrl(url, headers)
            }
        }

        backBtn.setOnClickListener {
            // 🔴 حل مشكلة الإعلانات: إذا كان هناك إعلان مفتوح، نغلقه أولاً
            if (popupsList.isNotEmpty()) {
                val lastPopup = popupsList.removeLast()
                webViewContainer.removeView(lastPopup)
                lastPopup.destroy()
            } 
            // إذا لم يكن هناك إعلانات، نعود للصفحة السابقة في المتصفح الرئيسي
            else if (mainWebView.canGoBack()) {
                mainWebView.goBack()
            }
        }

        return dialog
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        settings.mediaPlaybackRequiresUserGesture = false // تشغيل الفيديو تلقائياً
        settings.allowFileAccess = true
        settings.allowContentAccess = true

        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        // تسريع الهاردوير لرسم مشغلات الفيديو (يمنع الشاشة السوداء)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

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
                setupWebView(newWebView) 

                newWebView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                
                // إضافة الإعلان/النافذة الجديدة للشاشة
                webViewContainer.addView(newWebView)
                // حفظها في القائمة لكي نتمكن من إغلاقها بزر الرجوع
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
                // حظر الروابط التي تفتح تطبيقات خارجية وتسبب كراش
                if (url.startsWith("intent://") || url.startsWith("market://") || url.startsWith("tg://")) {
                    return true 
                }
                return false // جعل الويبفيو يحمل الرابط بنفسه
            }
        }
    }
}
