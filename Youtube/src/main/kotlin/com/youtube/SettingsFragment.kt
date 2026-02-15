package com.youtube

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
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
import androidx.preference.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class YoutubeSettingsBottomSheet(
    private val sharedPref: SharedPreferences
) : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    class PrefsFragment(
        private val sharedPref: SharedPreferences
    ) : PreferenceFragmentCompat() {

        // المفاتيح
        private val KEY_VISITOR = "VISITOR_INFO1_LIVE"
        private val KEY_PAGES = "channel_pages_limit"
        private val KEY_PLAYLIST_TAG = "playlist_search_tag"
        private val KEY_LANGUAGE = "youtube_language"
        private val KEY_PLAYER_TYPE = "youtube_player_type" // المفتاح الجديد لنوع المشغل

        // مراجع للإعدادات لتحديث نصوصها
        private lateinit var authCategory: PreferenceCategory
        private lateinit var customCategory: PreferenceCategory
        private lateinit var capturePref: Preference
        private lateinit var loginStatusPref: Preference
        private lateinit var visitorPref: EditTextPreference
        private lateinit var clearPref: Preference
        private lateinit var pagesPref: SeekBarPreference
        private lateinit var playlistTagPref: EditTextPreference
        private lateinit var languagePref: ListPreference
        private lateinit var playerTypePref: ListPreference // المرجع الجديد

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            preferenceManager.preferenceDataStore = null
            val screen = preferenceManager.createPreferenceScreen(ctx)
            preferenceScreen = screen

            // ==========================================
            // 1. إعداد اللغة
            // ==========================================
            languagePref = ListPreference(ctx).apply {
                key = KEY_LANGUAGE
                title = "Language / اللغة"
                entryValues = arrayOf("ar", "en")
                entries = arrayOf("العربية (Arabic)", "English")
                if (value == null) value = "ar"
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                setOnPreferenceChangeListener { _, newValue ->
                    updateTexts(newValue as String)
                    true
                }
            }
            screen.addPreference(languagePref)

            // ==========================================
            // 2. الفئات والإعدادات
            // ==========================================

            // --- فئة الحساب ---
            authCategory = PreferenceCategory(ctx)
            screen.addPreference(authCategory)

            capturePref = Preference(ctx).apply {
                key = "capture_webview_btn"
                setOnPreferenceClickListener {
                    val fm = parentFragmentManager
                    val webDialog = WebViewCaptureDialog(sharedPref) { success ->
                        if (success) {
                            val visitorVal = sharedPref.getString(KEY_VISITOR, "")
                            visitorPref.text = visitorVal
                            Toast.makeText(ctx, getStr("saved_msg"), Toast.LENGTH_SHORT).show()
                            updateTexts(languagePref.value ?: "ar")
                        }
                    }
                    webDialog.setStyle(DialogFragment.STYLE_NORMAL, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
                    webDialog.show(fm, "webview_fullscreen")
                    true
                }
            }
            authCategory.addPreference(capturePref)

            loginStatusPref = Preference(ctx).apply {
                isEnabled = false
            }
            authCategory.addPreference(loginStatusPref)

            visitorPref = EditTextPreference(ctx).apply {
                key = KEY_VISITOR
                setOnBindEditTextListener { it.setText(sharedPref.getString(KEY_VISITOR, "")) }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    val value = pref.text
                    if (value.isNullOrBlank()) getStr("not_found") else value
                }
            }
            visitorPref.text = sharedPref.getString(KEY_VISITOR, "")
            visitorPref.setOnPreferenceChangeListener { _, newVal ->
                sharedPref.edit().putString(KEY_VISITOR, newVal as String).apply()
                true
            }
            authCategory.addPreference(visitorPref)

            clearPref = Preference(ctx).apply {
                setOnPreferenceClickListener {
                    sharedPref.edit()
                        .remove(KEY_VISITOR).remove("SID").remove("HSID")
                        .remove("SSID").remove("APISID").remove("SAPISID")
                        .apply()
                    Toast.makeText(ctx, getStr("cleared_msg"), Toast.LENGTH_SHORT).show()
                    visitorPref.text = ""
                    updateTexts(languagePref.value ?: "ar")
                    true
                }
            }
            authCategory.addPreference(clearPref)

            // --- فئة التخصيص ---
            customCategory = PreferenceCategory(ctx)
            screen.addPreference(customCategory)

            // >> إضافة خيار نوع المشغل الجديد <<
            playerTypePref = ListPreference(ctx).apply {
                key = KEY_PLAYER_TYPE
                entryValues = arrayOf("advanced", "classic")
                // القيم الافتراضية، سيتم تحديث النصوص في updateTexts
                entries = arrayOf("Advanced", "Classic")
                if (value == null) value = "advanced" // الافتراضي هو المتقدم

                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()

                setOnPreferenceChangeListener { _, newValue ->
                    // حفظ القيمة وتحديث النصوص إذا لزم الأمر
                    true
                }
            }
            customCategory.addPreference(playerTypePref)

            pagesPref = SeekBarPreference(ctx).apply {
                key = KEY_PAGES
                min = 1
                max = 50
                setDefaultValue(6)
                showSeekBarValue = true
                setOnPreferenceChangeListener { _, newVal ->
                    sharedPref.edit().putInt(KEY_PAGES, (newVal as Int)).apply()
                    true
                }
            }
            pagesPref.value = sharedPref.getInt(KEY_PAGES, 6)
            customCategory.addPreference(pagesPref)

            playlistTagPref = EditTextPreference(ctx).apply {
                key = KEY_PLAYLIST_TAG
                setOnBindEditTextListener { it.setText(sharedPref.getString(KEY_PLAYLIST_TAG, "{p}")) }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                    val value = pref.text
                    val currentTxt = if (languagePref.value == "en") "Current: " else "الحالي: "
                    if (value.isNullOrBlank()) "{p}" else "$currentTxt$value"
                }
                setOnPreferenceChangeListener { _, newVal ->
                    val v = (newVal as String).trim().ifEmpty { "{p}" }
                    sharedPref.edit().putString(KEY_PLAYLIST_TAG, v).apply()
                    true
                }
            }
            if (!sharedPref.contains(KEY_PLAYLIST_TAG)) sharedPref.edit().putString(KEY_PLAYLIST_TAG, "{p}").apply()
            playlistTagPref.text = sharedPref.getString(KEY_PLAYLIST_TAG, "{p}")
            customCategory.addPreference(playlistTagPref)

            // تطبيق النصوص الأولية
            updateTexts(languagePref.value ?: "ar")
        }

        // ==========================================
        // دالة الترجمة وتحديث النصوص
        // ==========================================
        private fun updateTexts(lang: String) {
            val isEn = lang == "en"

            // 1. Language Title
            languagePref.title = if (isEn) "Language" else "اللغة"

            // 2. Categories
            authCategory.title = if (isEn) "Account & Cookies" else "إعدادات الحساب والكوكيز"
            customCategory.title = if (isEn) "Browsing & Search" else "تخصيص التصفح والبحث"

            // 3. Login Button
            capturePref.title = if (isEn) "Login / Update Cookies" else "تسجيل الدخول / تحديث الكوكيز"
            capturePref.summary = if (isEn) "Sign in with Google to capture session cookies (SID, HSID...)"
            else "سجّل دخولك بحساب جوجل ليتم التقاط كوكيز الجلسة (SID, HSID...)"

            // 4. Status
            val hasSid = sharedPref.getString("SID", null) != null
            loginStatusPref.title = if (isEn) "Account Status" else "حالة الحساب"
            loginStatusPref.summary = if (hasSid) {
                if (isEn) "Logged In (Cookies Active)" else "تم تسجيل الدخول (الكوكيز موجودة)"
            } else {
                if (isEn) "Not Logged In (Incognito)" else "غير مسجل (وضع التصفح الخفي)"
            }

            // 5. Visitor ID
            visitorPref.title = if (isEn) "Visitor ID" else "معرفك للصفحة الرئيسية"
            visitorPref.dialogTitle = if (isEn) "Edit Manually" else "تعديل يدوياً"

            // 6. Logout
            clearPref.title = if (isEn) "Logout (Clear Cookies)" else "تسجيل الخروج (حذف الكوكيز)"

            // 7. Player Type (جديد)
            playerTypePref.title = if (isEn) "Player Engine" else "محرك التشغيل"
            if (isEn) {
                playerTypePref.entries = arrayOf(
                    "Advanced (1080p/4K + Auto-Translate)",
                    "Classic (HLS - Lite)"
                )
            } else {
                playerTypePref.entries = arrayOf(
                    "متقدم (1080p/4K + ترجمة تلقائية)",
                    "كلاسيكي (HLS - خفيف)"
                )
            }

            // 8. Pages
            pagesPref.title = if (isEn) "Channel Pages Limit" else "عدد صفحات تحميل القناة"
            pagesPref.summary = if (isEn) "Pages fetched when opening a channel (approx. 30 videos/page)"
            else "عدد الصفحات التي يتم جلبها عند فتح قناة (كل صفحة ≈ 30 فيديو)"

            // 9. Playlist Tag
            playlistTagPref.title = if (isEn) "Playlist Search Tag" else "وسم بحث القوائم"
            playlistTagPref.dialogTitle = if (isEn) "Enter tag (e.g. {p} or playlist:)" else "اكتب الوسم الذي تريده (مثال: {p})"
        }

        // دالة مساعدة لجلب رسائل التوست
        private fun getStr(key: String): String {
            val isEn = (findPreference<ListPreference>(KEY_LANGUAGE)?.value ?: "ar") == "en"
            return when (key) {
                "saved_msg" -> if (isEn) "Login data saved successfully" else "تم حفظ بيانات تسجيل الدخول بنجاح"
                "cleared_msg" -> if (isEn) "Data cleared" else "تم حذف البيانات"
                "not_found" -> if (isEn) "Not Found" else "غير موجود"
                else -> ""
            }
        }
    }

    // ==========================================
    // Dialog Class (ويب فيو)
    // ==========================================
    class WebViewCaptureDialog(
        private val sharedPref: SharedPreferences,
        private val onFinish: (Boolean) -> Unit
    ) : DialogFragment() {
        private lateinit var webView: WebView
        private val targetCookies = listOf("VISITOR_INFO1_LIVE", "SID", "HSID", "SSID", "APISID", "SAPISID")

        @SuppressLint("SetJavaScriptEnabled")
        override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val ctx = requireContext()
            val lang = sharedPref.getString("youtube_language", "ar") ?: "ar"
            val isEn = lang == "en"

            val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = ViewGroup.LayoutParams(-1, -1); setBackgroundColor(Color.WHITE) }
            val toolbar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(20, 20, 20, 20); setBackgroundColor(Color.parseColor("#EEEEEE")); gravity = Gravity.CENTER_VERTICAL }

            val closeBtn = Button(ctx).apply {
                text = if (isEn) "Close" else "إغلاق"
                setOnClickListener { dismiss() }
            }

            val titleView = TextView(ctx).apply {
                text = if (isEn) "YouTube Login" else "تسجيل دخول يوتيوب"
                textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.BLACK); layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }

            val saveBtn = Button(ctx).apply {
                text = if (isEn) "Save" else "حفظ"
                setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#FF0000")); setOnClickListener { captureAndClose(isEn) }
            }

            toolbar.addView(closeBtn); toolbar.addView(titleView); toolbar.addView(saveBtn)
            val webContainer = FrameLayout(ctx).apply { layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
            webView = WebView(ctx).apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }
            val settings = webView.settings; settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            webView.webViewClient = object : WebViewClient() { override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false }
            webView.loadUrl("https://www.youtube.com")
            webContainer.addView(webView); root.addView(toolbar); root.addView(webContainer)
            return root
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val dialog = super.onCreateDialog(savedInstanceState)
            dialog.setOnKeyListener { _, keyCode, event -> if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && ::webView.isInitialized && webView.canGoBack()) { webView.goBack(); true } else false }
            return dialog
        }

        private fun captureAndClose(isEn: Boolean) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) CookieManager.getInstance().flush()
                val cookieStr = CookieManager.getInstance().getCookie(webView.url ?: "https://www.youtube.com") ?: ""
                val editor = sharedPref.edit()
                var found = 0
                cookieStr.split(";").forEach {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2 && targetCookies.contains(parts[0].trim())) { editor.putString(parts[0].trim(), parts[1].trim()); found++ }
                }
                editor.apply()
                if (found > 0) { onFinish(true); dismiss() }
                else Toast.makeText(context, if (isEn) "No data found!" else "لم يتم العثور على بيانات", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { Toast.makeText(context, "Error", Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?) : View {
        val fragmentContainer = FragmentContainerView(requireContext())
        fragmentContainer.id = View.generateViewId()
        fragmentContainer.layoutParams = ViewGroup.LayoutParams(-1, -1)
        return fragmentContainer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction().replace(view.id, PrefsFragment(sharedPref)).commit()
    }
    companion object { fun show(fm: FragmentManager, sp: SharedPreferences) = YoutubeSettingsBottomSheet(sp).show(fm, "yt_settings") }
}