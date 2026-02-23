package com.youtube

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.*
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class YoutubeSettingsBottomSheet : BottomSheetDialogFragment() {

    class PrefsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            val screen = preferenceManager.createPreferenceScreen(ctx)
            preferenceScreen = screen

            val openPref = Preference(ctx).apply {
                title = "فتح حلقة Jigokurak"
                summary = "فتح الصفحة مع مراقبة الشبكة"
                setOnPreferenceClickListener {
                    SnifferDialog().show(requireActivity().supportFragmentManager, "sniffer")
                    true
                }
            }

            screen.addPreference(openPref)
        }
    }

    class SnifferDialog : DialogFragment() {

        private lateinit var webView: WebView
        private lateinit var status: TextView

        private val captured = mutableListOf<Pair<String, String>>()

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {

            val ctx = requireContext()

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
            }

            status = TextView(ctx).apply {
                text = "جاري التحميل..."
                setPadding(20, 20, 20, 20)
            }

            webView = WebView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            }

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }

            webView.addJavascriptInterface(JSBridge(), "Android")

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    status.text = "تم التحميل — تم تفعيل المراقبة ✔"
                    injectSniffer()
                }
            }

            webView.loadUrl("https://anime3rb.com/episode/jigokuraku-2nd-season/2")

            val listBtn = Button(ctx).apply {
                text = "القائمة"
                setOnClickListener { showList() }
            }

            val closeBtn = Button(ctx).apply {
                text = "إغلاق"
                setOnClickListener { dismiss() }
            }

            val btnRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            btnRow.addView(listBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            btnRow.addView(closeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            root.addView(status)
            root.addView(btnRow)
            root.addView(webView)

            return root
        }

        inner class JSBridge {
            @JavascriptInterface
            fun onRequest(url: String, response: String) {
                requireActivity().runOnUiThread {
                    captured.add(url to response)
                }
            }
        }

        private fun injectSniffer() {

            val js = """
                (function() {

                    const oldFetch = window.fetch;
                    window.fetch = async function() {
                        const res = await oldFetch.apply(this, arguments);
                        try {
                            const clone = res.clone();
                            const text = await clone.text();
                            Android.onRequest(res.url, text);
                        } catch(e){}
                        return res;
                    };

                    const oldOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this._url = url;
                        return oldOpen.apply(this, arguments);
                    };

                    const oldSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.send = function() {
                        this.addEventListener('load', function() {
                            try {
                                Android.onRequest(this._url, this.responseText);
                            } catch(e){}
                        });
                        return oldSend.apply(this, arguments);
                    };

                })();
            """.trimIndent()

            webView.evaluateJavascript(js, null)
        }

        private fun showList() {

            if (captured.isEmpty()) {
                Toast.makeText(requireContext(), "لا توجد طلبات بعد", Toast.LENGTH_SHORT).show()
                return
            }

            val items = captured.mapIndexed { i, pair ->
                "${i + 1}. ${pair.first}"
            }.toTypedArray()

            AlertDialog.Builder(requireContext())
                .setTitle("طلبات الشبكة (${captured.size})")
                .setItems(items) { _, which ->
                    showResponse(captured[which].second)
                }
                .setNegativeButton("إغلاق", null)
                .show()
        }

        private fun showResponse(response: String) {

            val scroll = ScrollView(requireContext())
            val text = TextView(requireContext()).apply {
                setPadding(20, 20, 20, 20)
                textIsSelectable = true
                textSize = 12f
                this.text =
                    if (response.length > 10000)
                        response.substring(0, 10000) + "\n...\n(تم قص النص)"
                    else response
            }

            scroll.addView(text)

            AlertDialog.Builder(requireContext())
                .setTitle("Response")
                .setView(scroll)
                .setPositiveButton("إغلاق", null)
                .show()
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
        inflater: LayoutInflater,
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

