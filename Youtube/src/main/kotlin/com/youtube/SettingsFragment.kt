package com.youtube

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class YoutubeSettingsBottomSheet : BottomSheetDialogFragment() {

    class PrefsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            val screen = preferenceManager.createPreferenceScreen(ctx)
            preferenceScreen = screen

            val openSitePref = Preference(ctx).apply {
                title = "فتح موقع Anime3rb"
                summary = "اضغط لحل حماية Cloudflare"

                setOnPreferenceClickListener {
                    CloudflareWebViewDialog()
                        .show(parentFragmentManager, "anime_cf")
                    true
                }
            }

            screen.addPreference(openSitePref)
        }
    }

    class CloudflareWebViewDialog : DialogFragment() {

        private lateinit var webView: WebView
        private lateinit var statusView: TextView

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreateView(
            inflater: android.view.LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {

            val ctx = requireContext()

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(-1, -1)
            }

            statusView = TextView(ctx).apply {
                text = "جاري تحميل الموقع..."
                setPadding(16, 16, 16, 16)
                setBackgroundColor(0xFFEEEEEE.toInt())
            }

            webView = WebView(ctx)

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == null) return

                    when {
                        url.contains("challenge") || url.contains("cloudflare") ->
                            statusView.text = "⚠ تم اكتشاف تحقق Cloudflare — أكمله يدويًا"

                        url.contains("anime3rb.com") ->
                            statusView.text = "✅ الموقع مفتوح — تم تجاوز Cloudflare"
                    }
                }
            }

            webView.loadUrl("https://anime3rb.com")

            val closeBtn = Button(ctx).apply {
                text = "إغلاق"
                setOnClickListener { dismiss() }
            }

            root.addView(statusView)
            root.addView(closeBtn, ViewGroup.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
            root.addView(webView, ViewGroup.LayoutParams(-1, 0, 1f))

            return root
        }

        override fun onStart() {
            super.onStart()
            dialog?.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    override fun onCreateView(
        inflater: android.view.LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val containerView = FragmentContainerView(requireContext())
        containerView.id = View.generateViewId()
        return containerView
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment())
            .commit()
    }

    companion object {
        fun show(fm: FragmentManager) {
            YoutubeSettingsBottomSheet().show(fm, "anime_settings")
        }
    }
}
