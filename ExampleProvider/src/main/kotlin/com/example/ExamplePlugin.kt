package com.akwam.provider

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@Plugin
class AkwamPlugin : CloudstreamPlugin() {
    override fun load() {
        registerMainAPI(Akwam())
    }
}
