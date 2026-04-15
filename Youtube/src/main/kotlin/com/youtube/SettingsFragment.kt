package com.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Patterns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import java.util.Stack

class YoutubeSettingsBottomSheet : DialogFragment() {

    private lateinit var webContainer: FrameLayout
    private val webStack = Stack<WebView>()

    // ✨ تم تحديثه ليكون مشابهاً لكروم على أجهزة حديثة
    private val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    companion object {
        fun show(fm: FragmentManager) {
            YoutubeSettingsBottomSheet().show(fm, "browser_pro")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()

        // --- الواجهة الكاملة برمجياً ---
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val topBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 10, 10, 10)
            setBackgroundColor(Color.DKGRAY)
            gravity = Gravity.CENTER_VERTICAL
        }

        val urlInput = EditText(ctx).apply {
            hint = "أدخل الرابط..."
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_GO
        }
        
        val backBtn = Button(ctx).apply { text = "رجوع" }

        topBar.addView(urlInput)
        topBar.addView(backBtn)
        
        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8)
            max = 100
            progress = 0
            visibility = View.GONE
        }

        webContainer = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val mainWebView = createWebView(ctx, progressBar, urlInput)
        webStack.push(mainWebView)
        webContainer.addView(mainWebView)

        root.addView(topBar)
        root.addView(progressBar)
        root.addView(webContainer)

        urlInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                val input = v.text.toString()
                loadUrlSmart(webStack.peek(), input)
                true
            } else {
                false
            }
        }

        backBtn.setOnClickListener { handleBack() }
        
        // تحميل صفحة جوجل عند البداية
        loadUrlSmart(mainWebView, "https://google.com")

        return root
    }
    
    private fun loadUrlSmart(webView: WebView, input: String) {
        if (input.isBlank()) return
        
        val url = if (Patterns.WEB_URL.matcher(input).matches()) {
            if (input.startsWith("http://") || input.startsWith("https://")) {
                input
            } else {
                "https://$input"
            }
        } else {
            "https://www.google.com/search?q=${Uri.encode(input)}"
        }
        webView.loadUrl(url)
    }

    private fun handleBack() {
        if (webStack.size > 1) {
            val top = webStack.pop()
            webContainer.removeView(top)
            top.destroy()
        } else {
            val current = webStack.peek()
            if (current.canGoBack()) {
                current.goBack()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(context: Context, progressBar: ProgressBar, urlInput: EditText): WebView {
        val webView = WebView(context)
        
        // ✨ تفعيل تسريع العتاد (Hardware Acceleration) لتحسين الأداء بشكل كبير
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val s = webView.settings
        
        // --- إعدادات أساسية لـ JavaScript والتخزين ---
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportMultipleWindows(true)

        // --- إعدادات التوافق والمحتوى (هذه هي الأهم لحل مشكلتك) ---
        s.userAgentString = USER_AGENT // استخدام وكيل مستخدم حديث
        s.mediaPlaybackRequiresUserGesture = false // للسماح بتشغيل الفيديو تلقائياً
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW // السماح بتحميل محتوى http داخل صفحة https
        s.allowFileAccess = true // السماح بالوصول للملفات
        s.allowContentAccess = true
        s.loadsImagesAutomatically = true
        
        // --- إعدادات الـ Viewport لجعل المواقع تظهر بشكل صحيح على الموبايل ---
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        
        // --- إعدادات الكاش لتحسين سرعة التحميل ---
        s.setAppCacheEnabled(true)
        s.setAppCachePath(context.cacheDir.path)
        s.cacheMode = WebSettings.LOAD_DEFAULT

        // --- التعامل مع الكوكيز ---
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                urlInput.setText(url)
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = View.GONE
            }
            
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // يمكنك عرض صفحة خطأ مخصصة هنا
                super.onReceivedError(view, request, error)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
            }
            
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                val newWebView = createWebView(context, progressBar, urlInput) // إنشاء WebView جديد للنوافذ المنبثقة
                
                webStack.push(newWebView)
                webContainer.addView(newWebView)
                
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()
                
                return true
            }
        }
        
        return webView
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
