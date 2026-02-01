package com.anim3rb

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager

/**
 * Anime3rbSettingsDialog
 * - لا يعتمد على androidx.preference
 * - لديه أزرار: فتح WebView (ملء الشاشة), حذف الكوكيز, حالة الحفظ, إغلاق
 *
 * استدعاء العرض:
 * Anime3rbSettingsDialog.show(fragmentManager, sharedPreferences)
 */
class Anime3rbSettingsDialog(
    private val sharedPref: SharedPreferences
) : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // سنبني واجهة بسيطة برمجياً لتجنب أي اعتماد خارجي
        val ctx = requireContext()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(ctx).apply {
            text = "إعدادات Anime3rb"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)

        val openWebviewBtn = Button(ctx).apply {
            text = "حل حماية Cloudflare / تسجيل الدخول"
            setOnClickListener {
                // افتح الـ WebView كـ DialogFragment مستقل
                val webDialog = WebViewCaptureDialog(sharedPref) { success ->
                    if (success) {
                        Toast.makeText(ctx, "تم حفظ الكوكيز بنجاح!", Toast.LENGTH_SHORT).show()
                        // لو حاب تحدث واجهة الحالة: يمكنك إعادة إنشاء الـ Dialog أو تحديث أي عرض
                    } else {
                        Toast.makeText(ctx, "لم يتم حفظ الكوكيز.", Toast.LENGTH_SHORT).show()
                    }
                }
                // عرض ملء الشاشة
                webDialog.setStyle(STYLE_NORMAL, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
                webDialog.show(parentFragmentManager, "webview_fullscreen")
            }
        }
        root.addView(openWebviewBtn)

        val clearBtn = Button(ctx).apply {
            text = "حذف الكوكيز المحفوظة"
            setOnClickListener {
                sharedPref.edit()
                    .remove("anime3rb_cookie")
                    .remove("anime3rb_ua")
                    .apply()
                // إزالة ملفات الكوكيز من WebView
                val cm = CookieManager.getInstance()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    cm.removeAllCookies(null)
                    cm.flush()
                } else {
                    @Suppress("DEPRECATION")
                    cm.removeAllCookie()
                }
                Toast.makeText(ctx, "تم حذف البيانات.", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(clearBtn)

        val statusView = TextView(ctx).apply {
            val cookie = sharedPref.getString("anime3rb_cookie", "")
            text = if (!cookie.isNullOrEmpty()) "الحالة: الكوكيز محفوظة ✅" else "الحالة: لا توجد كوكيز محفوظة ❌"
            setPadding(0, 16, 0, 16)
        }
        root.addView(statusView)

        val closeBtn = Button(ctx).apply {
            text = "إغلاق"
            setOnClickListener { dismiss() }
        }
        root.addView(closeBtn)

        // زر تحديث العرض (اختياري) — لو أردت تحديث الحالة بعد الإغلاق/الحفظ
        val refreshBtn = Button(ctx).apply {
            text = "تحديث الحالة"
            setOnClickListener {
                val cookie = sharedPref.getString("anime3rb_cookie", "")
                statusView.text = if (!cookie.isNullOrEmpty()) "الحالة: الكوكيز محفوظة ✅" else "الحالة: لا توجد كوكيز محفوظة ❌"
            }
        }
        root.addView(refreshBtn)

        return root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    /**
     * WebViewCaptureDialog
     * - شاشة WebView كاملة لتسجيل الدخول وحفظ الكوكيز في SharedPreferences
     * - onFinish(true) إذا تم العثور على كوكيز حماية
     */
    class WebViewCaptureDialog(
        private val sharedPref: SharedPreferences,
        private val onFinish: (Boolean) -> Unit
    ) : DialogFragment() {

        private lateinit var webView: WebView
        private val userAgent = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val ctx = requireContext()

            // Root vertical
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.WHITE)
            }

            // Toolbar بسيط
            val toolbar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(20, 20, 20, 20)
                setBackgroundColor(Color.parseColor("#F5F5F5"))
                gravity = Gravity.CENTER_VERTICAL
            }

            val closeBtn = Button(ctx).apply {
                text = "إغلاق"
                setOnClickListener { dismiss() }
            }
            toolbar.addView(closeBtn)

            val titleView = TextView(ctx).apply {
                text = "Anime3rb Login"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.BLACK)
                val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = lp
            }
            toolbar.addView(titleView)

            val saveBtn = Button(ctx).apply {
                text = "حفظ الكوكيز"
                setOnClickListener { captureAndClose() }
            }
            toolbar.addView(saveBtn)

            // Container لـ WebView
            val webContainer = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            webView = WebView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val settings: WebSettings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = userAgent
            settings.cacheMode = WebSettings.LOAD_DEFAULT

            // السماح بالكوكيز
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            try {
                cm.setAcceptThirdPartyCookies(webView, true)
            } catch (t: Throwable) {
                // بعض البيئات لا تدعم هذه الدالة
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
            }

            webView.loadUrl("https://www.anime3rb.com") // أو أي رابط تريده

            webContainer.addView(webView)
            root.addView(toolbar)
            root.addView(webContainer)

            return root
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val dialog = super.onCreateDialog(savedInstanceState)
            // التعامل مع زر العودة داخل WebView
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK &&
                    event.action == KeyEvent.ACTION_UP &&
                    ::webView.isInitialized &&
                    webView.canGoBack()
                ) {
                    webView.goBack()
                    true
                } else false
            }
            return dialog
        }

        private fun captureAndClose() {
            try {
                val cm = CookieManager.getInstance()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) cm.flush()

                val url = webView.url ?: "https://www.anime3rb.com"
                val cookieStr = cm.getCookie(url) ?: ""

                // تحقق من وجود أي من كوكيز الحماية الشهيرة
                if (cookieStr.contains("cf_clearance") || cookieStr.contains("laravel_session") || cookieStr.contains("XSRF-TOKEN")) {
                    sharedPref.edit()
                        .putString("anime3rb_cookie", cookieStr)
                        .putString("anime3rb_ua", webView.settings.userAgentString ?: "")
                        .apply()
                    onFinish(true)
                    dismiss()
                } else {
                    Toast.makeText(context, "لم يتم العثور على كوكيز الحماية بعد.", Toast.LENGTH_LONG).show()
                    onFinish(false)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
                onFinish(false)
            }
        }
    }

    companion object {
        fun show(fm: FragmentManager, sp: SharedPreferences) {
            Anime3rbSettingsDialog(sp).show(fm, "anime3rb_settings")
        }
    }
}
