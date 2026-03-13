package com.youtube

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

class YoutubeSettingsBottomSheet : DialogFragment() {

    class PrefsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            val screen = preferenceManager.createPreferenceScreen(ctx)
            preferenceScreen = screen

            val category = PreferenceCategory(ctx).apply { title = "أدوات الفحص" }
            screen.addPreference(category)

            val solvePref = Preference(ctx).apply {
                title = "تشغيل مستخرج الكوكيز (النافذة المخفية)"
                summary = "جلب cf_clearance مع دعم إعادة التوجيه (Redirects)."
                setOnPreferenceClickListener {
                    AlgorithmTestDialog().show(parentFragmentManager, "algorithm_test_dialog")
                    true
                }
            }
            category.addPreference(solvePref)

            val closePref = Preference(ctx).apply {
                title = "إغلاق"
                setOnPreferenceClickListener {
                    (parentFragment as? DialogFragment)?.dismiss()
                    true
                }
            }
            screen.addPreference(closePref)
        }
    }

    class AlgorithmTestDialog : DialogFragment() {
        private lateinit var logTextView: TextView
        private lateinit var logScrollView: ScrollView
        private lateinit var closeBtn: Button
        private lateinit var copyBtn: Button

        private var extractedCookies: String = ""
        // يمكنك اختبار الرابط الذي يعيد التوجيه هنا للتأكد
        private val targetUrl = "https://faselhdx.life" 

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val ctx = requireContext()

            val rootLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.WHITE)
            }

            val buttonsLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 16, 16, 16)
                setBackgroundColor(Color.parseColor("#222222"))
                gravity = Gravity.CENTER_VERTICAL
            }

            closeBtn = Button(ctx).apply { text = "إغلاق"; setTextColor(Color.RED); setBackgroundColor(Color.TRANSPARENT) }
            copyBtn = Button(ctx).apply { text = "عرض الكوكيز"; isEnabled = false; setBackgroundColor(Color.parseColor("#007AFF")); setTextColor(Color.WHITE) }

            buttonsLayout.addView(closeBtn)
            buttonsLayout.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
            buttonsLayout.addView(copyBtn)

            logScrollView = ScrollView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.BLACK)
            }
            logTextView = TextView(ctx).apply {
                setTextColor(Color.GREEN)
                setPadding(16, 16, 16, 16)
                textSize = 12f
                text = "=> بدء التشغيل...\n"
            }
            logScrollView.addView(logTextView)

            rootLayout.addView(buttonsLayout)
            rootLayout.addView(logScrollView)

            closeBtn.setOnClickListener { dismiss() }
            copyBtn.setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("الكوكيز المستخرج")
                    .setMessage(extractedCookies)
                    .setPositiveButton("موافق", null)
                    .show()
            }

            return rootLayout
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            startCookieExtraction()
        }

        private fun logUINonSuspend(msg: String) {
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                logTextView.append("$msg\n")
                logScrollView.post { logScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

        private fun startCookieExtraction() {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val result = fetchCookiesWithTrustedWebView(targetUrl)
                
                if (!result.isNullOrEmpty()) {
                    extractedCookies = result
                    logUINonSuspend("\n==============================")
                    logUINonSuspend("✅ تم الانتهاء بنجاح! تم التقاط الكوكيز.")
                    logUINonSuspend("==============================\n")
                    activity?.runOnUiThread { copyBtn.isEnabled = true }
                } else {
                    logUINonSuspend("\n==============================")
                    logUINonSuspend("❌ فشلت العملية: انتهى الوقت أو حدث خطأ.")
                    logUINonSuspend("==============================\n")
                }
            }
        }

        @SuppressLint("SetTextI18n", "SetJavaScriptEnabled")
        private suspend fun fetchCookiesWithTrustedWebView(
            url: String,
            timeoutMs: Long = 60000L
        ): String? = suspendCancellableCoroutine { cont ->

            logUINonSuspend("Function started. Target URL: $url Timeout: $timeoutMs")

            Handler(Looper.getMainLooper()).post {

                val activity = activity
                if (activity == null || activity.isFinishing) {
                    logUINonSuspend("Activity null or finishing")
                    if (cont.isActive) cont.resume(null)
                    return@post
                }

                val dialog = Dialog(activity)
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
                dialog.setCancelable(false)
                dialog.window?.addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
                dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dialog.window?.setDimAmount(0f)

                val params = WindowManager.LayoutParams()
                params.copyFrom(dialog.window?.attributes)
                params.width = 600
                params.height = 600
                params.gravity = Gravity.CENTER
                params.x = 0
                params.y = 0
                dialog.window?.attributes = params

                val webView = WebView(activity)
                dialog.setContentView(webView, ViewGroup.LayoutParams(600, 600))

                try {
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        userAgentString = DEFAULT_USER_AGENT
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        blockNetworkImage = false
                        loadsImagesAutomatically = true
                        javaScriptCanOpenWindowsAutomatically = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                    }
                } catch (e: Exception) {
                    logUINonSuspend("Error applying WebView settings: ${e.message}")
                }

                val cookieManager = CookieManager.getInstance()
                try {
                    cookieManager.setAcceptCookie(true)
                    cookieManager.setAcceptThirdPartyCookies(webView, true)
                } catch (e: Exception) {
                    logUINonSuspend("CookieManager error: ${e.message}")
                }

                var finished = false

                fun finish(result: String?) {
                    if (finished) return
                    finished = true
                    try { cookieManager.flush() } catch (e: Exception) {}
                    try { if (dialog.isShowing) dialog.dismiss() } catch (e: Exception) {}
                    try { webView.stopLoading(); webView.destroy() } catch (e: Exception) {}
                    try { if (cont.isActive) cont.resume(result) } catch (e: Exception) {}
                }

                cont.invokeOnCancellation { finish(null) }

                val startTime = System.currentTimeMillis()
                val handler = Handler(Looper.getMainLooper())

                val cookieChecker = object : Runnable {
                    override fun run() {
                        if (finished) return

                        // 🔴 هنا التعديل السحري: نأخذ الرابط الفعلي من المتصفح في حال حدوث إعادة توجيه (Redirect)
                        val currentActiveUrl = webView.url ?: url

                        val currentCookies = try {
                            cookieManager.getCookie(currentActiveUrl)
                        } catch (e: Exception) {
                            ""
                        } ?: ""

                        logUINonSuspend("Checking URL: $currentActiveUrl")
                        logUINonSuspend("Current cookies length: ${currentCookies.length}")

                        if (currentCookies.contains("cf_clearance")) {
                            logUINonSuspend("🎯 Silent Cloudflare Bypass Successful on redirected URL!")
                            handler.postDelayed({ finish(currentCookies) }, 2500)
                            return
                        }

                        val elapsed = System.currentTimeMillis() - startTime
                        if (elapsed > timeoutMs) {
                            logUINonSuspend("Timeout reached")
                            finish(null)
                            return
                        }

                        handler.postDelayed(this, 1000)
                    }
                }

                handler.postDelayed(cookieChecker, 1000)

                webView.webViewClient = object : WebViewClient() {}

                try {
                    dialog.show()
                    webView.loadUrl(url)
                    logUINonSuspend("URL loading started: $url")
                } catch (e: Exception) {
                    logUINonSuspend("Error showing dialog or loading URL: ${e.message}")
                    finish(null)
                    return@post
                }
            }
        }

        override fun onStart() {
            super.onStart()
            dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
            val width = (resources.displayMetrics.widthPixels * 0.95).toInt()
            val height = (resources.displayMetrics.heightPixels * 0.95).toInt()
            dialog?.window?.setLayout(width, height)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val fragmentContainer = FragmentContainerView(requireContext())
        fragmentContainer.id = View.generateViewId()
        fragmentContainer.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        return fragmentContainer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        childFragmentManager.beginTransaction().replace(view.id, PrefsFragment()).commit()
    }

    companion object {
        fun show(fm: FragmentManager) {
            YoutubeSettingsBottomSheet().show(fm, "anime_settings")
        }
    }
}
