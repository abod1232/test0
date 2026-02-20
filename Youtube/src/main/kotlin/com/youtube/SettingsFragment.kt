package com.youtube

import android.annotation.SuppressLint
import android.os.*
import android.view.*
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

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
        private val handler = Handler(Looper.getMainLooper())
        private var autoClickRunnable: Runnable? = null

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {

            val ctx = requireContext()

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            statusView = TextView(ctx).apply {
                text = "جاري تحميل الموقع..."
                setPadding(16, 16, 16, 16)
                setBackgroundColor(0xFFEEEEEE.toInt())
            }

            webView = WebView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                isFocusable = true
                isFocusableInTouchMode = true
                requestFocus()
                setOnTouchListener { v, _ ->
                    v.performClick()
                    false
                }
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

            // أزرار: فحص + إغلاق
            val closeBtn = Button(ctx).apply {
                text = "إغلاق"
                setOnClickListener { dismiss() }
            }

            val inspectBtn = Button(ctx).apply {
                text = "فحص كامل الصفحة"
                setOnClickListener {
                    inspectAllPartsAndShowList()
                }
            }

            // WebViewClient: عند نهاية التحميل نحدّث الحالة
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == null || view == null) return
                    statusView.text = "تم التحميل: $url"
                }
            }

            webView.loadUrl("https://anime3rb.com")

            // ترتيب العرض
            root.addView(statusView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))

            val btnRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            btnRow.addView(inspectBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            btnRow.addView(closeBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            root.addView(btnRow)
            root.addView(webView)

            return root
        }

        /**
         * فحص كل عناصر الصفحة المرئية (حتى عناصر ليست فقط روابط/أزرار)
         * يعرض قائمة للمستخدم، وعند الاختيار يرسم نقطة ويجري نقرة فعلية.
         */
        private fun inspectAllPartsAndShowList() {
            val js = """
(function(){
 let w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
 let n;
 while(n=w.nextNode()){
   if(n.nodeValue.includes("إنسان")){
     let r=n.parentElement.getBoundingClientRect();
     return {x:r.right+40,y:r.top+r.height/2};
   }
 }
 return null;
})();
""".trimIndent()

            try {
                webView.evaluateJavascript(js) { raw ->
                    try {
                        if (raw == null || raw == "null") {
                            Toast.makeText(requireContext(), "لم يتم العثور على عناصر قابلة للفحص", Toast.LENGTH_SHORT).show()
                            return@evaluateJavascript
                        }

                        // raw يمكن أن يكون سلسلة JSON مُهربة — نجرب إصلاحها بأبسط طريقة
                        val unescaped = try {
                            // بعض الأجهزة تعيد النص مغلفًا بعلامات اقتباس مزدوجة
                            if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2) {
                                // نزيل الاقتباس الخارجي ونفك escape للـjson string
                                JSONArray("[$raw]").getString(0)
                            } else raw
                        } catch (e: Exception) {
                            raw
                        }

                        val obj = JSONObject(unescaped)
                        val dpr = obj.optDouble("dpr", 1.0)
                        val arr = obj.optJSONArray("elements") ?: JSONArray()

                        if (arr.length() == 0) {
                            Toast.makeText(requireContext(), "لم يتم العثور على عناصر قابلة للفحص", Toast.LENGTH_LONG).show()
                            return@evaluateJavascript
                        }

                        val items = mutableListOf<String>()
                        val coordsCss = mutableListOf<Pair<Float, Float>>() // CSS pixels center
                        val coordsPx = mutableListOf<Pair<Float, Float>>()  // device pixels center

                        for (i in 0 until arr.length()) {
                            val it = arr.getJSONObject(i)
                            val tag = it.optString("tag")
                            val text = it.optString("text")
                            val path = it.optString("path")
                            val left = it.optDouble("left", 0.0)
                            val top = it.optDouble("top", 0.0)
                            val width = it.optDouble("width", 0.0)
                            val height = it.optDouble("height", 0.0)

                            val centerCssX = (left + width / 2.0).toFloat()
                            val centerCssY = (top + height / 2.0).toFloat()
                            val centerPxX = (centerCssX * dpr).toFloat()
                            val centerPxY = (centerCssY * dpr).toFloat()

                            coordsCss.add(Pair(centerCssX, centerCssY))
                            coordsPx.add(Pair(centerPxX, centerPxY))

                            val shortText = if (text.isNotBlank()) " — \"${text.take(60)}\"" else ""
                            val leftInt = Math.round(left).toInt()
                            val topInt = Math.round(top).toInt()
                            val widthInt = Math.round(width).toInt()
                            val heightInt = Math.round(height).toInt()

                            val desc = StringBuilder()
                                .append("${i + 1}. <$tag>$shortText")
                                .append("\npath: ${path.take(80)}")
                                .append("\nrect: $leftInt x $topInt  ${widthInt}×${heightInt}")
                                .toString()

                            items.add(desc)
                        }

                        // عرض القائمة بطريقة آمنة
                        handler.post {
                            try {
                                val builder = AlertDialog.Builder(requireContext())
                                    .setTitle("عناصر الصفحة (${items.size}) — اختر عنصرًا لرؤيته والنقر عليه")
                                    .setItems(items.toTypedArray()) { _, which ->
                                        try {
                                            val (cssX, cssY) = coordsCss[which]
                                            val (pxX, pxY) = coordsPx[which]

                                            // 1) رسم نقطة داخل الصفحة عند CSS إحداثيات
                                            val cxStr = cssX.toDouble().toString()
                                            val cyStr = cssY.toDouble().toString()

                                            val drawJs = ("(function(cx, cy) {"
                                                    + "try{"
                                                    + " var dot = document.createElement('div');"
                                                    + " dot.style.position = 'fixed';"
                                                    + " dot.style.left = (cx - 10) + 'px';"
                                                    + " dot.style.top = (cy - 10) + 'px';"
                                                    + " dot.style.width = '20px';"
                                                    + " dot.style.height = '20px';"
                                                    + " dot.style.background = 'rgba(255,0,0,0.9)';"
                                                    + " dot.style.borderRadius = '50%';"
                                                    + " dot.style.zIndex = '2147483647';"
                                                    + " dot.style.pointerEvents = 'none';"
                                                    + " dot.style.boxShadow = '0 0 12px rgba(255,0,0,0.8)';"
                                                    + " document.body.appendChild(dot);"
                                                    + " setTimeout(function(){ dot.remove(); }, 5000);"
                                                    + " return true;"
                                                    + "}catch(e){return false;}"
                                                    + "})(" + cxStr + "," + cyStr + ");")

                                            try {
                                                webView.evaluateJavascript(drawJs, null)
                                            } catch (_: Throwable) { /* لا نريد تحطم هنا */ }

                                            // 2) تنفيذ نقرة فعلية بواسطة MotionEvent في device pixels
                                            performMotionTap(webView, pxX, pxY)

                                            Toast.makeText(requireContext(), "تم رسم النقطة والنقر على العنصر ${which + 1}", Toast.LENGTH_SHORT).show()
                                        } catch (t: Throwable) {
                                            Toast.makeText(requireContext(), "خطأ أثناء النقر على العنصر", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                    .setNegativeButton("إلغاء", null)

                                builder.show()
                            } catch (e: Throwable) {
                                Toast.makeText(requireContext(), "خطأ أثناء عرض القائمة", Toast.LENGTH_LONG).show()
                            }
                        }

                    } catch (t: Throwable) {
                        Toast.makeText(requireContext(), "خطأ أثناء فحص الصفحة", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Throwable) {
                Toast.makeText(requireContext(), "فشل تنفيذ فحص الصفحة", Toast.LENGTH_LONG).show()
            }
        }

        // تنفيذ نقرة فعلية (DOWN -> MOVE -> UP) على إحداثيات device pixels داخل WebView
        private fun performMotionTap(view: WebView, x: Float, y: Float) {
            val downTime = SystemClock.uptimeMillis()

            val down = MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                x,
                y,
                0
            )

            val move = MotionEvent.obtain(
                downTime,
                downTime + 40,
                MotionEvent.ACTION_MOVE,
                x + 1f,
                y + 1f,
                0
            )

            val up = MotionEvent.obtain(
                downTime,
                downTime + 110,
                MotionEvent.ACTION_UP,
                x + 1f,
                y + 1f,
                0
            )

            view.post {
                try {
                    view.dispatchTouchEvent(down)
                    view.dispatchTouchEvent(move)
                    view.dispatchTouchEvent(up)
                } catch (_: Throwable) {
                } finally {
                    down.recycle()
                    move.recycle()
                    up.recycle()
                }
            }
        }

        override fun onDestroyView() {
            super.onDestroyView()
            autoClickRunnable?.let { handler.removeCallbacks(it) }
            autoClickRunnable = null
        }

        override fun onStart() {
            super.onStart()
            dialog?.window?.apply {
                clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
                setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
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