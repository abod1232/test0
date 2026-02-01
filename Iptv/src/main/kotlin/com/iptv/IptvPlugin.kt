package com.iptv

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IptvPlugin : Plugin() {

    override fun load(context: Context) {

        // تسجيل الـ Provider
        registerMainAPI(VipTV())

        // SharedPreferences (مسموح هنا)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        openSettings = { activityContext ->
            (activityContext as? AppCompatActivity)?.let { activity ->
                iptvSettings().show(
                    activity.supportFragmentManager,
                    "IptvSettings"
                )
            }
        }
    }
}
