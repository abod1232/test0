import okhttp3.OkHttpClient
import okhttp3.Request as OkRequest
import okhttp3.Response as OkResponse
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response

object CS3Downloader : Downloader() {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    override fun execute(request: Request): Response {

        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .header("User-Agent", "com.google.android.youtube/19.29.35")
            .header("X-Youtube-Client-Name", "3")
            .header("X-Youtube-Client-Version", "19.29.35")
            .header("Accept-Encoding", "gzip")

        for ((k, v) in request.headers()) {
            if (v.isNotEmpty()) builder.header(k, v.first())
        }

        val okResp = client.newCall(builder.build()).execute()
        val body = okResp.body?.string() ?: ""

        return Response(
            okResp.code,
            okResp.message,
            okResp.headers.toMultimap(),
            body,
            okResp.request.url.toString()
        )
    }
}

