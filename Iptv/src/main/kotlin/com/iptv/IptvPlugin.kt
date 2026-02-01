package com.iptv
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import kotlin.getValue
import com.iptv.VipTV
import com.iptv.iptvSettings
import androidx.preference.PreferenceManager
@CloudstreamPlugin
class eishkPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Viptv())
        val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(context) }

        openSettings = { activityContext ->
            (activityContext as? AppCompatActivity)?.let { activity ->
                // ببساطة قم بإنشاء وعرض شاشة الإعدادات
                iptvSettings().show(activity.supportFragmentManager, "ReplaymatchSettings")
            }
        }
    }
}
