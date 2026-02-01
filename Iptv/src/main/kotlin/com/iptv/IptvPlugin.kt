package com.iptv

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class eishkPlugin : Plugin() {

    override fun load(context: Context) {

        // ✅ تصحيح اسم الكلاس
        registerMainAPI(VipTV())

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
