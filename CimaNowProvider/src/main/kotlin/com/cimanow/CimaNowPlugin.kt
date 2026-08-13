package com.cimanow
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.fragment.app.FragmentActivity

@CloudstreamPlugin
class CimaNow: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CimaNowProvider(context))
        openSettings = { activityContext ->
            (activityContext as? FragmentActivity)?.let { activity ->
                val settingsFragment = cimanowsetting()
                settingsFragment.show(activity.supportFragmentManager, "CinemanaSettings")
            }

        }
    }
}