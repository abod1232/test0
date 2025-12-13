package com.anim3rb

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.anime3rb.Anime3rb
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Anime3rbPlugin: Plugin() {
    override fun load(context: Context) {
        // إنشاء SharedPreference خاص بالإضافة
        val sharedPref = context.getSharedPreferences("Anime3rb_Prefs", Context.MODE_PRIVATE)
            registerMainAPI(Anime3rb())

        // تعريف زر الإعدادات
        openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            Anime3rbSettingsDialog.show(activity.supportFragmentManager, sharedPref)
        }
    }
}