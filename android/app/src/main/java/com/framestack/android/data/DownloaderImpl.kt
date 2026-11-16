package com.framestack.android.data

import android.content.Context
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

class DownloaderImpl private constructor() : Downloader() {

    private val dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 24
    }

    val rawClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .readTimeout(20, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .build()

    val client: OkHttpClient = rawClient

    @Volatile
    private var authManager: YouTubeAuthManager? = null

    fun attachContext(context: Context) {
        if (authManager == null) {
            authManager = YouTubeAuthManager(context.applicationContext)
        }
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend: ByteArray? = request.dataToSend()

        val builder = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)

        val isYouTubeHost = url.contains("youtube.com") || url.contains("youtubei.googleapis.com")
        if (isYouTubeHost) {
            val auth = authManager
            if (auth != null && auth.isSignedIn()) {
                val authHeaders = auth.buildAuthHeaders(httpClient = rawClient)
                authHeaders["Cookie"]?.let { cookie ->
                    if (!headers.keys.any { it.equals("Cookie", ignoreCase = true) }) {
                        builder.addHeader("Cookie", cookie)
                    }
                }
                authHeaders["Authorization"]?.let { authHeader ->
                    if (!headers.keys.any { it.equals("Authorization", ignoreCase = true) }) {
                        builder.addHeader("Authorization", authHeader)
                        if (authHeader.startsWith("SAPISIDHASH")) {
                            builder.addHeader("X-Origin", "https://www.youtube.com")
                            builder.addHeader("Origin", "https://www.youtube.com")
                        }
                        builder.addHeader("X-Goog-AuthUser", "0")
                    }
                }
            } else if (!headers.keys.any { it.equals("Cookie", ignoreCase = true) }) {
                builder.addHeader("Cookie", YOUTUBE_CONSENT_COOKIE)
            }
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

        fun init(context: Context) {
            getInstance().attachContext(context)
        }

        fun getInstance(): DownloaderImpl {
            return instance ?: synchronized(this) {
                instance ?: DownloaderImpl().also { instance = it }
            }
        }
    }
}
