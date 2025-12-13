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
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import android.webkit.WebResourceRequest
class Anime3rbSettingsDialog(
    private val sharedPref: SharedPreferences
) : DialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val fragmentContainer = FragmentContainerView(requireContext())
        fragmentContainer.id = View.generateViewId()
        fragmentContainer.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        return fragmentContainer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment(sharedPref))
            .commit()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog?.window?.setBackgroundDrawableResource(android.R.color.white)
    }

    class PrefsFragment(
        private val sharedPref: SharedPreferences
    ) : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            preferenceManager.preferenceDataStore = null
            val screen = preferenceManager.createPreferenceScreen(ctx)
            preferenceScreen = screen

            val category = PreferenceCategory(ctx)
            category.title = "إعدادات الحماية (Cloudflare)"
            screen.addPreference(category)

            val solvePref = Preference(ctx).apply {
                title = "حل حماية Cloudflare / تسجيل الدخول"
                summary = "اضغط لفتح الموقع وحل الكابتشا يدوياً."
                setOnPreferenceClickListener {
                    val fm = parentFragmentManager
                    val webDialog = WebViewCaptureDialog(sharedPref) { success ->
                        if (success) {
                            Toast.makeText(ctx, "تم حفظ الكوكيز بنجاح!", Toast.LENGTH_SHORT).show()
                        }
                    }
                    webDialog.setStyle(STYLE_NORMAL, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
                    webDialog.show(fm, "webview_fullscreen")
                    true
                }
            }
            category.addPreference(solvePref)

            val clearPref = Preference(ctx).apply {
                title = "حذف الكوكيز المحفوظة"
                summary = "اضغط لحذف البيانات."
                setOnPreferenceClickListener {
                    sharedPref.edit()
                        .remove("anime3rb_cookie")
                        .remove("anime3rb_ua")
                        .apply()
                    CookieManager.getInstance().removeAllCookies(null)
                    Toast.makeText(ctx, "تم حذف البيانات", Toast.LENGTH_SHORT).show()
                    true
                }
            }
            category.addPreference(clearPref)

            val statusPref = Preference(ctx).apply {
                title = "الحالة"
                val cookie = sharedPref.getString("anime3rb_cookie", "")
                summary = if (!cookie.isNullOrEmpty()) "الكوكيز محفوظة ✅" else "لا توجد كوكيز محفوظة ❌"
                isEnabled = false
            }
            category.addPreference(statusPref)

            val closePref = Preference(ctx).apply {
                title = "إغلاق الإعدادات"
                setOnPreferenceClickListener {
                    (parentFragment as? DialogFragment)?.dismiss()
                    true
                }
            }
            screen.addPreference(closePref)
        }
    }

    class WebViewCaptureDialog(
        private val sharedPref: SharedPreferences,
        private val onFinish: (Boolean) -> Unit
    ) : DialogFragment() {
        private lateinit var webView: WebView
        private val userAgent = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val ctx = requireContext()
            val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = ViewGroup.LayoutParams(-1, -1); setBackgroundColor(Color.WHITE) }
            val toolbar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(20, 20, 20, 20); setBackgroundColor(Color.parseColor("#EEEEEE")); gravity = Gravity.CENTER_VERTICAL }
            val closeBtn = Button(ctx).apply { text = "إغلاق"; setOnClickListener { dismiss() } }
            val titleView = TextView(ctx).apply { text = "Anime3rb Login"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.BLACK); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            val saveBtn = Button(ctx).apply { text = "حفظ الكوكيز"; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#FF0000")); setOnClickListener { captureAndClose() } }
            toolbar.addView(closeBtn); toolbar.addView(titleView); toolbar.addView(saveBtn)
            val webContainer = FrameLayout(ctx).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
            webView = WebView(ctx).apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }
            val settings = webView.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = userAgent
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            webView.webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false }
            webView.loadUrl("https://www.anime3rb.com")
            webContainer.addView(webView); root.addView(toolbar); root.addView(webContainer)
            return root
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val dialog = super.onCreateDialog(savedInstanceState)
            dialog.setOnKeyListener { _, keyCode, event -> if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && ::webView.isInitialized && webView.canGoBack()) { webView.goBack(); true } else false }
            return dialog
        }

        private fun captureAndClose() {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) CookieManager.getInstance().flush()
                val url = webView.url ?: "https://www.anime3rb.com"
                val cookieStr = CookieManager.getInstance().getCookie(url) ?: ""
                if (cookieStr.contains("cf_clearance") || cookieStr.contains("laravel_session") || cookieStr.contains("XSRF-TOKEN")) {
                    val editor = sharedPref.edit()
                    editor.putString("anime3rb_cookie", cookieStr)
                    editor.putString("anime3rb_ua", userAgent)
                    editor.apply()
                    onFinish(true)
                    dismiss()
                } else {
                    Toast.makeText(context, "لم يتم العثور على كوكيز الحماية بعد.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        fun show(fm: FragmentManager, sp: SharedPreferences) = Anime3rbSettingsDialog(sp).show(fm, "anime3rb_settings")
    }
}