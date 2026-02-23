package com.youtube

import android.annotation.SuppressLint
import android.os.*
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.*
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream

class YoutubeSettingsBottomSheet : BottomSheetDialogFragment() {

    class PrefsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {

            val ctx = requireContext()
            val screen = preferenceManager.createPreferenceScreen(ctx)
            preferenceScreen = screen

            val openSitePref = Preference(ctx).apply {
                title = "فتح حلقة Jigokuraku"
                summary = "فتح الموقع مع مراقبة الطلبات"

                setOnPreferenceClickListener {
                    CloudflareWebViewDialog()
                        .show(requireActivity().supportFragmentManager, "anime_cf")
                    true
                }
            }

            screen.addPreference(openSitePref)
        }
    }

    class CloudflareWebViewDialog : DialogFragment() {

        private lateinit var webView: WebView
        private lateinit var statusView: TextView

        private val capturedRequests = mutableListOf<Pair<String, String>>()
        private val client = OkHttpClient()

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

            statusView = TextView(ctx).apply {
                text = "جاري التحميل والمراقبة..."
                setPadding(16, 16, 16, 16)
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
                userAgentString =
                    "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                setAcceptThirdPartyCookies(webView, true)
            }

            val listBtn = Button(ctx).apply {
                text = "القائمة"
                setOnClickListener { showRequestsList() }
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

            webView.webViewClient = object : WebViewClient() {

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest
                ): WebResourceResponse? {

                    val url = request.url.toString()

                    return try {
                        val req = Request.Builder()
                            .url(url)
                            .header("User-Agent", webView.settings.userAgentString)
                            .build()

                        val resp = client.newCall(req).execute()
                        val body = resp.body?.string() ?: ""

                        capturedRequests.add(url to body)

                        WebResourceResponse(
                            resp.header("content-type", "text/plain"),
                            "utf-8",
                            ByteArrayInputStream(body.toByteArray())
                        )

                    } catch (e: Exception) {
                        null
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    statusView.text = "تم التحميل والمراقبة ✔"
                }
            }

            webView.loadUrl(
                "https://anime3rb.com/episode/jigokuraku-2nd-season/2"
            )

            root.addView(statusView)
            root.addView(btnRow)
            root.addView(webView)

            return root
        }

        private fun showRequestsList() {

            if (capturedRequests.isEmpty()) {
                Toast.makeText(requireContext(), "لا توجد طلبات بعد", Toast.LENGTH_SHORT).show()
                return
            }

            val urls = capturedRequests.mapIndexed { i, it ->
                "${i + 1}. ${it.first}"
            }.toTypedArray()

            AlertDialog.Builder(requireContext())
                .setTitle("طلبات الشبكة")
                .setItems(urls) { _, index ->

                    val response = capturedRequests[index].second

                    AlertDialog.Builder(requireContext())
                        .setTitle("Response")
                        .setMessage(
                            if (response.length > 5000)
                                response.substring(0, 5000) + "\n...\n(تم القص)"
                            else response
                        )
                        .setPositiveButton("إغلاق", null)
                        .show()
                }
                .setNegativeButton("إغلاق", null)
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
