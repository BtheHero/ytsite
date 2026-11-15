package com.framestack.android.data

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

class DownloaderImpl private constructor() : Downloader() {

    val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend: ByteArray? = request.dataToSend()

        val builder = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)

        if (url.contains("youtube.com") && !headers.keys.any { it.equals("Cookie", ignoreCase = true) }) {
            builder.addHeader("Cookie", YOUTUBE_CONSENT_COOKIE)
        }

        // Preserve every header supplied by NewPipeExtractor (including client-specific User-Agent)
        for ((headerName, headerValues) in headers) {
            builder.removeHeader(headerName)
            for (value in headerValues) {
                builder.addHeader(headerName, value)
            }
        }

        when (httpMethod) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            else -> {
                val body = (dataToSend ?: ByteArray(0)).toRequestBody(null)
                builder.method(httpMethod, body)
            }
        }

        client.newCall(builder.build()).execute().use { okResponse ->
            if (okResponse.code == 429) {
                throw ReCaptchaException("reCaptcha challenge requested", url)
            }
            val bodyString = okResponse.body?.string() ?: ""
            val latestUrl = okResponse.request.url.toString()
            return Response(
                okResponse.code,
                okResponse.message,
                okResponse.headers.toMultimap(),
                bodyString,
                latestUrl
            )
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"

        private const val YOUTUBE_CONSENT_COOKIE =
            "SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg"

        @Volatile
        private var instance: DownloaderImpl? = null

        fun getInstance(): DownloaderImpl {
            return instance ?: synchronized(this) {
                instance ?: DownloaderImpl().also { instance = it }
            }
        }
    }
}
