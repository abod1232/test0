package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.schemaStripRegex
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

open class YoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    override fun getExtractorUrl(id: String): String {
        return "$mainUrl/watch?v=$id"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanedUrl = url.replace(schemaStripRegex, "")
        
        try {
            // 1. إعداد واستخراج البيانات باستخدام NewPipe
            val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanedUrl)
            val extractor = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
            extractor.fetchPage()

            // 2. تجهيز قائمة الصوتيات (Audio Tracks)
            // نقوم بإنشاء قائمة بملفات الصوت ليتم إرفاقها مع الفيديو
            val audioStreams = extractor.audioStreams.orEmpty()
            val audioTracks = audioStreams.map { stream ->
                val lang = stream.audioTrackId?.substringBefore(".") ?: "Unknown"
                val bitrate = stream.bitrate?.div(1000) ?: 0
                val format = stream.format?.mimeType?.substringAfter("/") ?: "audio"
                
                newAudioFile(
                    url = stream.content,
                    label = "$lang ($format ${bitrate}kbps)" // اسم المسار الصوتي الذي سيظهر في المشغل
                )
            }

            // 3. معالجة الفيديو وربط الصوت به
            val videoStreams = extractor.videoOnlyStreams.orEmpty()
            
            // نستخدم distinctBy لمنع تكرار نفس الجودة
            val uniqueVideoStreams = videoStreams.distinctBy { it.height }

            uniqueVideoStreams.forEach { video ->
                val streamUrl = video.content ?: return@forEach
                val height = video.height ?: 0
                val codec = normalizeCodec(video.format?.mimeType)

                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "$name $height $codec", // مثال: YouTube 1080p mp4
                        url = streamUrl
                    ) {
                        this.quality = height
                        // ********************************************
                        // هنا يتم دمج الصوت والفيديو بدون سيرفر داخلي
                        // ********************************************
                        this.audioTracks = audioTracks 
                    }
                )
            }

            // 4. معالجة الروابط القديمة (المدمجة مسبقاً إن وجدت - Legacy)
            extractor.videoStreams.orEmpty().forEach { video ->
                val streamUrl = video.content ?: return@forEach
                callback(
                    newExtractorLink(
                        source = this.name,
                        name = "$name ${video.height} (Muxed)",
                        url = streamUrl
                    ) {
                        this.quality = video.height ?: 0
                    }
                )
            }

            // 5. معالجة الترجمات
            extractor.subtitlesDefault.orEmpty().filterNotNull().forEach { sub ->
                val lang = sub.locale?.displayName ?: sub.locale?.language ?: "Unknown"
                val subUrl = sub.content ?: sub.getUrl()
                if (subUrl != null) {
                    subtitleCallback(
                        newSubtitleFile(
                            lang = lang,
                            url = subUrl
                        )
                    )
                }
            }

        } catch (e: Exception) {
            logError(e)
        }
    }

    // دالة مساعدة لتحسين اسم الكوديك للعرض
    private fun normalizeCodec(mimeType: String?): String {
        if (mimeType == null) return ""
        return when {
            mimeType.contains("webm") -> "[WebM]"
            mimeType.contains("mp4") -> "[MP4]"
            else -> ""
        }
    }
}
