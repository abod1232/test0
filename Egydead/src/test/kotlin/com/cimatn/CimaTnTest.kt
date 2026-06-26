import org.junit.Test
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.util.Base64  // 🌟 مهم: نستخدم مكتبة جافا بدلاً من أندرويد لأن هذا كود اختبار
import java.util.regex.Pattern
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.net.URLEncoder

class CimaTnTest {

    private fun decodeHtml(doc: Document): Document {
        val rawHtml = doc.outerHtml()

        val keyMatcher = Pattern.compile("""var\s+_r\s*=\s*(\d+)""").matcher(rawHtml)
        if (!keyMatcher.find()) {
            return doc
        }
        val dynamicKey = keyMatcher.group(1).toLong()

        val dataMatcher = Pattern.compile("""['"]([A-Za-z0-9+/=~]{20,})['"]""").matcher(rawHtml)
        val extractedData = StringBuilder(100000)
        while (dataMatcher.find()) {
            extractedData.append(dataMatcher.group(1))
        }

        if (extractedData.isEmpty()) {
            return doc
        }

        val outputStream = ByteArrayOutputStream(extractedData.length / 4)
        val decoder = Base64.getDecoder()
        var successCount = 0

        val chunk = StringBuilder(64)
        val len = extractedData.length

        for (i in 0 until len) {
            val c = extractedData[i]

            if (c == '~') {
                if (chunk.isNotEmpty()) {
                    successCount += decodeAndWriteFast(chunk, decoder, dynamicKey, outputStream)
                    chunk.setLength(0)
                }
            }
            else if ((c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '+' || c == '/' || c == '=') {
                chunk.append(c)
            }
        }

        if (chunk.isNotEmpty()) {
            successCount += decodeAndWriteFast(chunk, decoder, dynamicKey, outputStream)
        }

        val decodedHtmlString = outputStream.toString("UTF-8")

        if (decodedHtmlString.isBlank()) {
            return doc
        }
        return Jsoup.parse(decodedHtmlString)
    }

    private fun decodeAndWriteFast(
        chunk: StringBuilder,
        decoder: Base64.Decoder,
        key: Long,
        out: ByteArrayOutputStream
    ): Int {
        val r = chunk.length % 4
        if (r > 0) {
            chunk.append(if (r == 2) "==" else if (r == 3) "=" else "")
        }

        try {
            val bytes = decoder.decode(chunk.toString())
            var num = 0L
            for (i in bytes.indices) {
                val b = bytes[i].toInt()
                if (b in 48..57) {
                    num = num * 10 + (b - 48)
                }
            }
            if (num > 0) {
                out.write((num - key).toInt())
                return 1
            }
        } catch (ignored: Exception) {
        }
        return 0
    }

    // ---------------------------------------------------------
    // دوال مساعدة (آمنة ضد الفشل)
    // ---------------------------------------------------------
    private fun extractGroup(regex: String, text: String, errorMsg: String): String {
        val matcher = Pattern.compile(regex).matcher(text)
        if (matcher.find()) {
            return matcher.group(1)
        }
        throw Exception(errorMsg)
    }

    private fun calculateHmacSha256(message: String, secret: String): String {
        val keySpec = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(keySpec)
        val bytes = mac.doFinal(message.toByteArray())
        return Base64.getEncoder().encodeToString(bytes)
    }

    // ---------------------------------------------------------
    // دالة الاختبار الرئيسية
    // ---------------------------------------------------------
    @Test
    fun runFullFreex2lineDecryptionTest() {
        println("\n============================================================")
        println("🚀 [START] بدء اختبار تخطي حماية CimaNow الكامل (V5 - Bulletproof)")
        println("============================================================")

        val cookieJar = object : CookieJar {
            private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        }

        val client = OkHttpClient.Builder().cookieJar(cookieJar).build()
        val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        val mainReferer = "https://rm.freex2line.online/"
        val startUrl = "https://rm.freex2line.online/loadon/?link=aHR0cHM6Ly9jaW1hbm93LmNjLyVkOSU4MSVkOSU4YSVkOSU4NCVkOSU4NS1vbmUtYmF0dGxlLWFmdGVyLWFub3RoZXItMjAyNS0lZDklODUlZDglYWElZDglYjElZDglYWMlZDklODUvd2F0Y2hpbmcv"

        try {
            // [1] إنشاء الجلسة
            println("[1/7] 🌐 جاري إنشاء الجلسة...")
            client.newCall(Request.Builder().url(startUrl).header("User-Agent", userAgent).build()).execute().close()

            // [2] جلب صفحة البيانات
            println("[2/7] 📄 جاري جلب صفحة البيانات...")
            val pageUrl = "https://rm.freex2line.online/2020/02/blog-post.html/"
            val pageRequest = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", userAgent)
                .header("Referer", mainReferer)
                .build()
            val html = client.newCall(pageRequest).execute().body!!.string()

            // [3] استخراج المعرفات (تجاوز الفخاخ)
            println("[3/7] 🔍 تحليل نظام الحماية الديناميكي (تجاوز الفخاخ)...")
            val ctxName = extractGroup("""window\.ptr_[a-zA-Z0-9_]+\s*=\s*'([^']+)'""", html, "Pointer (ptr_) not found")
            val mapData = extractGroup("""window\.map_[a-zA-Z0-9_]+\s*=\s*\{([^}]+)\}""", html, "Map (map_) not found")
            val ctxData = extractGroup("""window\['$ctxName'\]\s*=\s*\{([^}]+)\}""", html, "Context data not found")

            val chK = extractGroup("""ch:\s*'([^']+)'""", mapData, "Key 'ch' not found in map")
            val riK = extractGroup("""ri:\s*'([^']+)'""", mapData, "Key 'ri' not found in map")
            val keK = extractGroup("""ke:\s*'([^']+)'""", mapData, "Key 'ke' not found in map")
            val seK = extractGroup("""se:\s*'([^']+)'""", mapData, "Key 'se' not found in map")

            val ch = extractGroup("""'$chK':\s*'([^']+)'""", ctxData, "Value for 'ch' not found")
            val requestId = extractGroup("""'$riK':\s*'([^']+)'""", ctxData, "Value for request_id not found")
            val encryptedKeyB64 = extractGroup("""'$keK':\s*'([^']+)'""", ctxData, "Value for encrypted key not found")
            val sXorKey = extractGroup("""'$seK':\s*'([^']+)'""", ctxData, "Value for XOR key not found")

            // [4] فك تشفير المفتاح السري (XOR) وتوليد HMAC
            println("[4/7] 🔓 فك تشفير المفتاح وتوليد التوقيع الرقمي (HMAC)...")
            val encryptedBytes = Base64.getDecoder().decode(encryptedKeyB64)
            val secretKey = encryptedBytes.mapIndexed { index, byte ->
                (byte.toInt() xor sXorKey[index % sXorKey.length].code).toChar()
            }.joinToString("")

            println("   🔑 Secret Key: $secretKey")

            val fpBase64 = "TW96aWxsYS81Ll9f" // بصمة المتصفح المعتمدة من الخادم
            val messageToSign = requestId + ch + fpBase64
            val hmacTokenEncoded = URLEncoder.encode(calculateHmacSha256(messageToSign, secretKey), "UTF-8")

            // [5] الانتظار الإلزامي
            println("\n⏳ جاري الانتظار 11 ثانية لتخطي عداد السيرفر...")
            Thread.sleep(11000)

            // [6] جلب رابط صفحة المشاهدة
            println("\n[5/7] 🚀 إرسال الطلب النهائي للـ API...")
            val apiUrl = "https://rm.freex2line.online/2020/02/blog-post.html/get-link.php?request_id=$requestId&hmac_token=$hmacTokenEncoded&ch=$ch&fp=$fpBase64"
            val apiRequest = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", userAgent)
                .header("Referer", pageUrl)
                .header("X-Requested-With", "XMLHttpRequest") // مهم!
                .header("Accept", "*/*")
                .build()

            val watchPageUrl = client.newCall(apiRequest).execute().body!!.string().trim()

            if (!watchPageUrl.startsWith("http")) {
                throw Exception("السيرفر لم يرجع رابطاً صحيحاً: $watchPageUrl")
            }
            println("   ✅ تم بنجاح! رابط صفحة المشاهدة: $watchPageUrl")

            // [7] جلب الصفحة المشفرة واستدعاء دالة فك التشفير
            println("\n[6/7] 📄 جلب صفحة المشاهدة وفك تشفير الـ HTML...")
            val watchPageRequest = Request.Builder().url(watchPageUrl).header("User-Agent", userAgent).header("Referer", pageUrl).build()
            val encryptedHtmlData = client.newCall(watchPageRequest).execute().body!!.string()

            // ⚠️ تم إصلاح الخطأ هنا: تحويل النص إلى Document ثم فك تشفيره
            val doc = Jsoup.parse(encryptedHtmlData)
            val finalHtmlDoc = decodeHtml(doc)
            val finalHtml = finalHtmlDoc.outerHtml()

            println("\n================== [DECODED HTML CONTENT] ==================")
            // طباعة جزء صغير للتأكد من نجاح العملية (تجنب إغراق شاشة الكونسول)
            println(finalHtml.take(1500) + "\n...[TRUNCATED]...")
            println("============================================================")

            assert(finalHtml.contains("class=\"tabcontent\"") || finalHtml.contains("watch")) { "فشل فك التشفير: لم يتم العثور على محتوى السيرفرات." }
            println("\n🎉🎉🎉 [SUCCESS] Test Passed Successfully! 🎉🎉🎉")

        } catch (e: Exception) {
            println("\n💥 [FATAL ERROR] Test failed: ${e.message}")
            e.printStackTrace()
            assert(false)
        }
    }
}