package com.framestack.android.data

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class YouTubeRepository(private val context: Context) {

    val authManager = YouTubeAuthManager(context)

    private data class CachedFeed(val items: List<VideoItem>, val timestampMs: Long)
    private data class CachedStreams(val streams: ResolvedStreams, val timestampMs: Long)

    private data class InnertubeClient(
        val clientName: String,
        val clientVersion: String,
        val clientIdHeader: String,
        val userAgent: String,
        val deviceMake: String? = null,
        val deviceModel: String? = null,
        val osName: String? = null,
        val osVersion: String? = null,
        val androidSdkVersion: Int? = null,
        val needsSignatureTimestamp: Boolean = false,
        val sendCookies: Boolean = false
    )

    private val feedCache = ConcurrentHashMap<String, CachedFeed>()
    private val streamCache = ConcurrentHashMap<String, CachedStreams>()

    private val feedCacheTtlMs = 5L * 60L * 1000L
    private val streamCacheTtlMs = 20L * 60L * 1000L

    @Volatile
    private var cachedVisitorData: String? = null
    @Volatile
    private var visitorDataFetchedAtMs: Long = 0L

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val prefs = context.getSharedPreferences("framestack_prefs", Context.MODE_PRIVATE)

    fun clearStreamCache() {
        streamCache.clear()
        cachedVisitorData = null
        visitorDataFetchedAtMs = 0L
    }

    fun loadSources(): List<VideoSource> {
        val saved = prefs.getString("sources_json", null)
        if (!saved.isNullOrBlank()) {
            runCatching {
                return json.decodeFromString<SourcesFile>(saved).sources
            }
        }
        return loadDefaultAssetSources()
    }

    fun saveSources(sources: List<VideoSource>) {
        val encoded = json.encodeToString(SourcesFile(sources))
        prefs.edit().putString("sources_json", encoded).apply()
    }

    fun resetDefaultSources(): List<VideoSource> {
        prefs.edit().remove("sources_json").apply()
        return loadDefaultAssetSources()
    }

    private fun loadDefaultAssetSources(): List<VideoSource> {
        return runCatching {
            val raw = context.assets.open("sources.json").bufferedReader().use { it.readText() }
            json.decodeFromString<SourcesFile>(raw).sources
        }.getOrDefault(emptyList())
    }

    suspend fun resolveAndAddSource(
        rawInput: String,
        customTitle: String,
        category: String
    ): Result<VideoSource> = withContext(Dispatchers.IO) {
        runCatching {
            val input = rawInput.trim()
            require(input.isNotEmpty()) { "Enter a YouTube channel or playlist URL." }

            val normalizedUrl = when {
                input.startsWith("UC") && input.length >= 22 ->
                    "https://www.youtube.com/channel/$input"
                input.matches(Regex("^(PL|UU|OLAK|FL|LL|RD)[A-Za-z0-9_-]{10,}$")) ->
                    "https://www.youtube.com/playlist?list=$input"
                input.startsWith("@") ->
                    "https://www.youtube.com/$input"
                !input.startsWith("http") ->
                    "https://$input"
                else -> input
            }

            val listRegex = Regex("[?&]list=([A-Za-z0-9_-]+)")
            val listMatch = listRegex.find(normalizedUrl)

            if (listMatch != null) {
                val playlistId = listMatch.groupValues[1]
                val playlistUrl = "https://www.youtube.com/playlist?list=$playlistId"
                val extractedTitle = runCatching {
                    PlaylistInfo.getInfo(ServiceList.YouTube, playlistUrl).name
                }.getOrNull() ?: fetchAtomTitle("https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId")

                VideoSource(
                    id = "src-${System.currentTimeMillis()}",
                    type = "playlist",
                    title = customTitle.ifBlank { extractedTitle?.ifBlank { null } ?: "YouTube Playlist" },
                    category = category.ifBlank { "Curated" },
                    playlistId = playlistId,
                    url = playlistUrl
                )
            } else {
                val directChannelMatch = Regex("/channel/(UC[A-Za-z0-9_-]+)").find(normalizedUrl)
                var channelId = directChannelMatch?.groupValues?.getOrNull(1)
                var channelTitle: String? = null

                if (channelId == null) {
                    val npChannel = runCatching {
                        ChannelInfo.getInfo(ServiceList.YouTube, normalizedUrl)
                    }.getOrNull()
                    if (npChannel != null && npChannel.id.startsWith("UC")) {
                        channelId = npChannel.id
                        channelTitle = npChannel.name
                    }
                }

                if (channelId == null) {
                    val scraped = resolveChannelFromHtml(normalizedUrl)
                    channelId = scraped.first
                    if (channelTitle.isNullOrBlank()) channelTitle = scraped.second
                }

                require(!channelId.isNullOrBlank()) {
                    "Could not resolve channel ID from URL. Try pasting the channel UC... or playlist PL... link."
                }

                if (channelTitle.isNullOrBlank()) {
                    channelTitle = fetchAtomTitle("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")
                }

                val uploadsPlaylistId = if (channelId.startsWith("UC")) {
                    "UU${channelId.removePrefix("UC")}"
                } else {
                    null
                }

                VideoSource(
                    id = "src-${System.currentTimeMillis()}",
                    type = "channel",
                    title = customTitle.ifBlank { channelTitle?.ifBlank { null } ?: "YouTube Channel" },
                    category = category.ifBlank { "Curated" },
                    channelId = channelId,
                    playlistId = uploadsPlaylistId,
                    url = "https://www.youtube.com/channel/$channelId"
                )
            }
        }
    }

    suspend fun fetchSourceVideos(
        source: VideoSource,
        forceRefresh: Boolean = false,
        onFastPreview: ((List<VideoItem>) -> Unit)? = null
    ): Result<List<VideoItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val now = System.currentTimeMillis()
                if (!forceRefresh) {
                    val cached = feedCache[source.id]
                    if (cached != null && now - cached.timestampMs < feedCacheTtlMs && cached.items.isNotEmpty()) {
                        return@runCatching cached.items
                    }
                }

                val channelId = source.channelId
                    ?: source.playlistId?.takeIf { it.startsWith("UU") }?.let { "UC${it.drop(2)}" }

                val playlistId = source.playlistId
                    ?: channelId?.takeIf { it.startsWith("UC") }?.let { "UU${it.drop(2)}" }

                val rssUrl = when {
                    source.type == "playlist" && !source.playlistId.isNullOrBlank() && !source.playlistId.startsWith("UU") ->
                        "https://www.youtube.com/feeds/videos.xml?playlist_id=${source.playlistId}"
                    !channelId.isNullOrBlank() ->
                        "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
                    !playlistId.isNullOrBlank() ->
                        "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId"
                    else -> null
                }

                val items = coroutineScope {
                    val rssDeferred = async {
                        if (rssUrl != null) fetchAtomVideos(rssUrl, source.title) else emptyList()
                    }
                    val npDeferred = async {
                        if (!playlistId.isNullOrBlank()) {
                            val targetUrl = "https://www.youtube.com/playlist?list=$playlistId"
                            runCatching {
                                PlaylistInfo.getInfo(ServiceList.YouTube, targetUrl)
                                    .relatedItems
                                    .filterIsInstance<StreamInfoItem>()
                                    .mapNotNull { item ->
                                        val videoUrl = item.url ?: return@mapNotNull null
                                        val videoId = extractVideoId(videoUrl) ?: return@mapNotNull null
                                        val thumb = item.thumbnails
                                            .maxByOrNull { it.width }
                                            ?.url
                                            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                                        VideoItem(
                                            videoId = videoId,
                                            title = item.name ?: "Untitled Video",
                                            author = item.uploaderName ?: source.title,
                                            durationSeconds = item.duration,
                                            thumbnailUrl = thumb,
                                            videoUrl = videoUrl
                                        )
                                    }
                            }.getOrNull().orEmpty()
                        } else {
                            emptyList()
                        }
                    }

                    // Emit fast Atom XML preview immediately if PlaylistInfo is still resolving
                    val rssItems = rssDeferred.await()
                    if (rssItems.isNotEmpty() && !npDeferred.isCompleted) {
                        onFastPreview?.invoke(rssItems)
                    }

                    val npItems = npDeferred.await()
                    when {
                        npItems.isNotEmpty() -> npItems
                        rssItems.isNotEmpty() -> rssItems
                        else -> emptyList()
                    }
                }

                if (items.isNotEmpty()) {
                    feedCache[source.id] = CachedFeed(items, System.currentTimeMillis())
                }
                items
            }
        }

    suspend fun resolveVideoStreams(
        video: VideoItem,
        forceRefresh: Boolean = false
    ): Result<ResolvedStreams> =
        withContext(Dispatchers.IO) {
            runCatching {
                val now = System.currentTimeMillis()
                if (!forceRefresh) {
                    val cached = streamCache[video.videoId]
                    if (cached != null && now - cached.timestampMs < streamCacheTtlMs) {
                        return@runCatching cached.streams
                    }
                }

                val resolved = coroutineScope {
                    val sponsorDeferred = async { fetchSponsorSegments(video.videoId) }
                    val signedIn = authManager.isSignedIn()
                    val visitorData = getVisitorData()

                    var lastError: Throwable? = null
                    var sawLoginRequired = false

                    fun noteError(err: Throwable) {
                        lastError = err
                        if (isLoginRequiredError(err)) {
                            sawLoginRequired = true
                        }
                    }

                    val attempts: List<() -> ResolvedStreams?> = if (signedIn) {
                        listOf(
                            { resolveViaInnertube(video, CLIENT_TVHTML5_AUTH, visitorData, emptyList()) },
                            { resolveViaInnertube(video, CLIENT_WEB_AUTH, visitorData, emptyList()) },
                            { resolveViaNewPipe(video) },
                            { resolveViaInnertube(video, CLIENT_ANDROID_VR, visitorData, emptyList()) },
                            { resolveViaInnertube(video, CLIENT_IOS, visitorData, emptyList()) },
                            { resolveViaInnertube(video, CLIENT_ANDROID, visitorData, emptyList()) }
                        )
                    } else {
                        listOf(
                            { resolveViaInnertube(video, CLIENT_ANDROID_VR, visitorData, emptyList()) },
                            { resolveViaNewPipe(video) },
                            { resolveViaInnertube(video, CLIENT_IOS, visitorData, emptyList()) },
                            { resolveViaInnertube(video, CLIENT_ANDROID, visitorData, emptyList()) }
                        )
                    }

                    var baseResult: ResolvedStreams? = null
                    for (attempt in attempts) {
                        try {
                            val candidate = attempt()
                            if (candidate != null && candidate.qualities.isNotEmpty()) {
                                baseResult = candidate
                                break
                            }
                        } catch (t: Throwable) {
                            noteError(t)
                        }
                    }

                    if (baseResult == null) {
                        if (sawLoginRequired) {
                            val msg = if (signedIn) {
                                "Your YouTube session may have expired or this video requires re-verification. Please sign in again."
                            } else {
                                "YouTube requested sign-in verification on this network (Sign in to confirm you're not a bot)."
                            }
                            throw LoginRequiredException(msg, lastError)
                        }
                        throw (lastError ?: IllegalStateException("No playable streams found for this video."))
                    }

                    baseResult.copy(sponsorSegments = sponsorDeferred.await())
                }

                streamCache[video.videoId] = CachedStreams(resolved, System.currentTimeMillis())
                resolved
            }
        }

    suspend fun prefetchVideoStreams(video: VideoItem) {
        val cached = streamCache[video.videoId]
        if (cached != null && System.currentTimeMillis() - cached.timestampMs < streamCacheTtlMs) {
            return
        }
        resolveVideoStreams(video)
    }

    private fun resolveViaNewPipe(video: VideoItem): ResolvedStreams {
        val info = StreamInfo.getInfo(ServiceList.YouTube, video.videoUrl)

        val bestAudio = info.audioStreams
            .filter { it.isUrl && !it.content.isNullOrBlank() }
            .maxByOrNull { it.averageBitrate }
            ?.content

        val adaptiveOptions = if (!bestAudio.isNullOrBlank()) {
            info.videoOnlyStreams
                .filter { it.isUrl && !it.content.isNullOrBlank() && it.height > 0 }
                .groupBy { it.height }
                .mapNotNull { (height, streams) ->
                    val picked = streams.firstOrNull {
                        it.format?.name?.contains("MPEG_4", ignoreCase = true) == true
                    } ?: streams.firstOrNull()
                    picked?.let {
                        QualityOption(
                            label = "${height}p",
                            height = height,
                            videoUrl = it.content,
                            audioUrl = bestAudio,
                            isMuxed = false
                        )
                    }
                }
        } else {
            emptyList()
        }

        val muxedOptions = info.videoStreams
            .filter { it.isUrl && !it.content.isNullOrBlank() && it.height > 0 }
            .groupBy { it.height }
            .mapNotNull { (height, streams) ->
                streams.firstOrNull()?.let {
                    QualityOption(
                        label = "${height}p",
                        height = height,
                        videoUrl = it.content,
                        audioUrl = null,
                        isMuxed = true
                    )
                }
            }

        val combinedQualities = (adaptiveOptions + muxedOptions)
            .distinctBy { it.height }
            .sortedByDescending { it.height }

        require(combinedQualities.isNotEmpty()) {
            "No playable video streams returned by NewPipeExtractor."
        }

        return ResolvedStreams(
            title = info.name ?: video.title,
            author = info.uploaderName ?: video.author,
            description = info.description?.content() ?: "",
            durationSeconds = info.duration,
            qualities = combinedQualities,
            sponsorSegments = emptyList()
        )
    }

    private fun resolveViaInnertube(
        video: VideoItem,
        client: InnertubeClient,
        visitorData: String?,
        sponsorSegments: List<SponsorSegment>
    ): ResolvedStreams {
        val clientJson = JSONObject().apply {
            put("clientName", client.clientName)
            put("clientVersion", client.clientVersion)
            put("hl", "en")
            put("gl", "US")
            client.deviceMake?.let { put("deviceMake", it) }
            client.deviceModel?.let { put("deviceModel", it) }
            client.osName?.let { put("osName", it) }
            client.osVersion?.let { put("osVersion", it) }
            client.androidSdkVersion?.let { put("androidSdkVersion", it) }
            if (!visitorData.isNullOrBlank()) {
                put("visitorData", visitorData)
            }
        }

        val contextJson = JSONObject().apply {
            put("client", clientJson)
        }

        val payloadJson = JSONObject().apply {
            put("videoId", video.videoId)
            put("context", contextJson)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            if (client.needsSignatureTimestamp) {
                val sts = runCatching {
                    YoutubeJavaScriptPlayerManager.getSignatureTimestamp(video.videoId)
                }.getOrNull()
                if (sts != null && sts > 0) {
                    put(
                        "playbackContext",
                        JSONObject().put(
                            "contentPlaybackContext",
                            JSONObject().put("signatureTimestamp", sts)
                        )
                    )
                }
            }
        }

        val reqBuilder = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("User-Agent", client.userAgent)
            .header("X-YouTube-Client-Name", client.clientIdHeader)
            .header("X-YouTube-Client-Version", client.clientVersion)

        if (client.sendCookies && authManager.isSignedIn()) {
            authManager.buildAuthHeaders().forEach { (k, v) ->
                reqBuilder.header(k, v)
            }
        }

        val body = payloadJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        reqBuilder.post(body)

        val responseBody = DownloaderImpl.getInstance().rawClient.newCall(reqBuilder.build()).execute().use { res ->
            if (!res.isSuccessful) {
                throw IllegalStateException("${client.clientName} HTTP ${res.code}")
            }
            res.body?.string() ?: throw IllegalStateException("${client.clientName} empty body")
        }

        val root = JSONObject(responseBody)
        val playability = root.optJSONObject("playabilityStatus")
        val status = playability?.optString("status", "") ?: ""
        val reason = playability?.optString("reason", "") ?: ""

        if (status != "OK") {
            if (status == "LOGIN_REQUIRED" ||
                reason.contains("not a bot", ignoreCase = true) ||
                reason.contains("Sign in", ignoreCase = true) ||
                reason.contains("confirm your age", ignoreCase = true)
            ) {
                throw LoginRequiredException("${client.clientName} $status: $reason")
            }
            throw IllegalStateException("${client.clientName} $status: $reason")
        }

        val streamingData = root.optJSONObject("streamingData")
            ?: throw IllegalStateException("${client.clientName} missing streamingData")

        val videoDetails = root.optJSONObject("videoDetails")
        val title = videoDetails?.optString("title")?.takeIf { it.isNotBlank() } ?: video.title
        val author = videoDetails?.optString("author")?.takeIf { it.isNotBlank() } ?: video.author
        val description = videoDetails?.optString("shortDescription") ?: ""
        val durationSeconds = videoDetails?.optString("lengthSeconds")?.toLongOrNull() ?: video.durationSeconds

        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
        val muxedFormats = streamingData.optJSONArray("formats") ?: JSONArray()

        val audioStreams = mutableListOf<Pair<Int, String>>()
        val videoOnlyByHeight = LinkedHashMap<Int, MutableList<Pair<String, String>>>()

        for (i in 0 until adaptiveFormats.length()) {
            val fmt = adaptiveFormats.optJSONObject(i) ?: continue
            val mime = fmt.optString("mimeType", "")
            val resolvedUrl = resolveFormatUrl(video.videoId, fmt, client.needsSignatureTimestamp) ?: continue

            if (mime.startsWith("audio/", ignoreCase = true)) {
                val bitrate = fmt.optInt("averageBitrate", fmt.optInt("bitrate", 0))
                audioStreams.add(bitrate to resolvedUrl)
            } else if (mime.startsWith("video/", ignoreCase = true)) {
                val height = fmt.optInt("height", 0)
                if (height > 0) {
                    videoOnlyByHeight.getOrPut(height) { mutableListOf() }.add(mime to resolvedUrl)
                }
            }
        }

        val bestAudioUrl = audioStreams.maxByOrNull { it.first }?.second

        val adaptiveQualities = if (!bestAudioUrl.isNullOrBlank()) {
            videoOnlyByHeight.mapNotNull { (height, candidates) ->
                val picked = candidates.firstOrNull { (mime, _) ->
                    mime.contains("mp4", ignoreCase = true) && mime.contains("avc1", ignoreCase = true)
                } ?: candidates.firstOrNull { (mime, _) ->
                    mime.contains("mp4", ignoreCase = true)
                } ?: candidates.firstOrNull()

                picked?.let { (_, streamUrl) ->
                    QualityOption(
                        label = "${height}p",
                        height = height,
                        videoUrl = streamUrl,
                        audioUrl = bestAudioUrl,
                        isMuxed = false,
                        userAgent = client.userAgent
                    )
                }
            }
        } else {
            emptyList()
        }

        val muxedQualities = (0 until muxedFormats.length()).mapNotNull { i ->
            val fmt = muxedFormats.optJSONObject(i) ?: return@mapNotNull null
            val height = fmt.optInt("height", 0)
            if (height <= 0) return@mapNotNull null
            val resolvedUrl = resolveFormatUrl(video.videoId, fmt, client.needsSignatureTimestamp)
                ?: return@mapNotNull null
            QualityOption(
                label = "${height}p",
                height = height,
                videoUrl = resolvedUrl,
                audioUrl = null,
                isMuxed = true,
                userAgent = client.userAgent
            )
        }

        var combinedQualities = (adaptiveQualities + muxedQualities)
            .distinctBy { it.height }
            .sortedByDescending { it.height }

        if (combinedQualities.isEmpty()) {
            val hlsManifestUrl = streamingData.optString("hlsManifestUrl", "")
            if (hlsManifestUrl.isNotBlank()) {
                combinedQualities = listOf(
                    QualityOption(
                        label = "Auto (HLS)",
                        height = 1080,
                        videoUrl = hlsManifestUrl,
                        audioUrl = null,
                        isMuxed = true,
                        userAgent = client.userAgent,
                        isHls = true
                    )
                )
            }
        }

        require(combinedQualities.isNotEmpty()) {
            "${client.clientName} returned no playable stream URLs."
        }

        return ResolvedStreams(
            title = title,
            author = author,
            description = description,
            durationSeconds = durationSeconds,
            qualities = combinedQualities,
            sponsorSegments = sponsorSegments
        )
    }

    private fun resolveFormatUrl(
        videoId: String,
        fmt: JSONObject,
        deobfuscateThrottling: Boolean
    ): String? {
        val directUrl = fmt.optString("url", "")
        if (directUrl.isNotBlank()) {
            return if (deobfuscateThrottling) {
                runCatching {
                    YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, directUrl)
                }.getOrDefault(directUrl)
            } else {
                directUrl
            }
        }

        val cipherStr = fmt.optString("signatureCipher", fmt.optString("cipher", ""))
        if (cipherStr.isBlank()) return null

        val params = parseQueryParams(cipherStr)
        val rawUrl = params["url"] ?: return null
        val obfuscatedSig = params["s"]
        val sigParamName = params["sp"] ?: "sig"

        val signedUrl = if (!obfuscatedSig.isNullOrBlank()) {
            val decryptedSig = runCatching {
                YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, obfuscatedSig)
            }.getOrNull() ?: return null
            val separator = if (rawUrl.contains("?")) "&" else "?"
            "$rawUrl$separator$sigParamName=${URLEncoder.encode(decryptedSig, "UTF-8")}"
        } else {
            rawUrl
        }

        return if (deobfuscateThrottling) {
            runCatching {
                YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, signedUrl)
            }.getOrDefault(signedUrl)
        } else {
            signedUrl
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        query.split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[key] = value
            }
        }
        return map
    }

    private fun getVisitorData(): String? {
        val now = System.currentTimeMillis()
        val existing = cachedVisitorData
        if (!existing.isNullOrBlank() && now - visitorDataFetchedAtMs < 30L * 60L * 1000L) {
            return existing
        }

        val fetched = runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/sw.js_data")
                .header("User-Agent", DownloaderImpl.USER_AGENT)
                .get()
                .build()
            DownloaderImpl.getInstance().rawClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return@runCatching null
                val body = res.body?.string() ?: return@runCatching null
                Regex("(Cg[A-Za-z0-9_%-]{15,})").find(body)?.groupValues?.getOrNull(1)
            }
        }.getOrNull()

        if (!fetched.isNullOrBlank()) {
            cachedVisitorData = fetched
            visitorDataFetchedAtMs = now
        }
        return fetched ?: existing
    }

    private fun isLoginRequiredError(err: Throwable): Boolean {
        var cur: Throwable? = err
        while (cur != null) {
            if (cur is LoginRequiredException ||
                cur is SignInConfirmNotBotException ||
                cur is AgeRestrictedContentException
            ) {
                return true
            }
            val msg = cur.message.orEmpty()
            if (msg.contains("LOGIN_REQUIRED", ignoreCase = true) ||
                msg.contains("not a bot", ignoreCase = true) ||
                msg.contains("Sign in to confirm", ignoreCase = true)
            ) {
                return true
            }
            cur = cur.cause
        }
        return false
    }

    private fun resolveChannelFromHtml(url: String): Pair<String?, String?> {
        return runCatching {
            val req = Request.Builder()
                .url(url)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                )
                .header("Cookie", "CONSENT=PENDING+987; SOCS=CAESEwgDEgk0ODE3Nzk3MjQaAmVuIAEaBgiA_LyaBg")
                .get()
                .build()
            DownloaderImpl.getInstance().client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return Pair(null, null)
                val html = res.body?.string() ?: return Pair(null, null)
                val cid = listOf(
                    Regex("\"externalId\":\"(UC[A-Za-z0-9_-]+)\""),
                    Regex("<meta\\s+itemprop=\"identifier\"\\s+content=\"(UC[A-Za-z0-9_-]+)\"", RegexOption.IGNORE_CASE),
                    Regex("\"channelId\":\"(UC[A-Za-z0-9_-]+)\""),
                    Regex("channel_id=(UC[A-Za-z0-9_-]+)")
                ).firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
                val title = Regex("<meta\\s+property=\"og:title\"\\s+content=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.getOrNull(1)
                Pair(cid, title)
            }
        }.getOrDefault(Pair(null, null))
    }

    private fun fetchAtomTitle(rssUrl: String): String? {
        return runCatching {
            val req = Request.Builder().url(rssUrl).get().build()
            DownloaderImpl.getInstance().client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val xml = res.body?.string() ?: return null
                Regex("<title>([^<]+)</title>").find(xml)?.groupValues?.getOrNull(1)
            }
        }.getOrNull()
    }

    private fun fetchAtomVideos(rssUrl: String, fallbackAuthor: String): List<VideoItem> {
        return runCatching {
            val req = Request.Builder().url(rssUrl).get().build()
            val xml = DownloaderImpl.getInstance().client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                res.body?.string() ?: return emptyList()
            }

            val items = mutableListOf<VideoItem>()
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))

            var inEntry = false
            var currentTag = ""
            var videoId = ""
            var title = ""
            var author = ""
            var thumbnail = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name ?: ""
                        if (currentTag == "entry") {
                            inEntry = true
                            videoId = ""
                            title = ""
                            author = ""
                            thumbnail = ""
                        } else if (inEntry && currentTag == "media:thumbnail") {
                            thumbnail = parser.getAttributeValue(null, "url") ?: thumbnail
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text ?: ""
                        if (inEntry) {
                            when (currentTag) {
                                "yt:videoId" -> videoId += text
                                "title" -> title += text
                                "name" -> author += text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "entry" && inEntry) {
                            val cleanId = videoId.trim()
                            if (cleanId.isNotEmpty()) {
                                items.add(
                                    VideoItem(
                                        videoId = cleanId,
                                        title = title.trim().ifEmpty { "Untitled Video" },
                                        author = author.trim().ifEmpty { fallbackAuthor },
                                        durationSeconds = 0L,
                                        thumbnailUrl = thumbnail.ifEmpty { "https://i.ytimg.com/vi/$cleanId/hqdefault.jpg" },
                                        videoUrl = "https://www.youtube.com/watch?v=$cleanId"
                                    )
                                )
                            }
                            inEntry = false
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
            items
        }.getOrDefault(emptyList())
    }

    private fun fetchSponsorSegments(videoId: String): List<SponsorSegment> {
        return runCatching {
            val categories = URLEncoder.encode("[\"sponsor\",\"selfpromo\",\"interaction\"]", "UTF-8")
            val url =
                "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=$categories"
            val req = Request.Builder().url(url).get().build()
            DownloaderImpl.getInstance().client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return emptyList()
                val body = res.body?.string() ?: return emptyList()
                json.decodeFromString<List<SponsorSegment>>(body)
                    .filter { it.segment.size == 2 }
            }
        }.getOrDefault(emptyList())
    }

    private fun extractVideoId(url: String): String? {
        val match = Regex("(?:v=|youtu\\.be/|shorts/|live/)([A-Za-z0-9_-]{11})").find(url)
        return match?.groupValues?.getOrNull(1)
    }

    companion object {
        private val CLIENT_ANDROID_VR = InnertubeClient(
            clientName = "ANDROID_VR",
            clientVersion = "1.61.48",
            clientIdHeader = "28",
            userAgent = "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            osName = "Android",
            osVersion = "12L",
            androidSdkVersion = 32,
            needsSignatureTimestamp = false,
            sendCookies = false
        )

        private val CLIENT_IOS = InnertubeClient(
            clientName = "IOS",
            clientVersion = "20.10.4",
            clientIdHeader = "5",
            userAgent = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            osName = "iPhone",
            osVersion = "18.3.2.22D82",
            needsSignatureTimestamp = false,
            sendCookies = false
        )

        private val CLIENT_ANDROID = InnertubeClient(
            clientName = "ANDROID",
            clientVersion = "20.10.38",
            clientIdHeader = "3",
            userAgent = "com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US) gzip",
            osName = "Android",
            osVersion = "14",
            androidSdkVersion = 34,
            needsSignatureTimestamp = false,
            sendCookies = false
        )

        private val CLIENT_TVHTML5_AUTH = InnertubeClient(
            clientName = "TVHTML5",
            clientVersion = "7.20250312.16.00",
            clientIdHeader = "7",
            userAgent = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
            needsSignatureTimestamp = true,
            sendCookies = true
        )

        private val CLIENT_WEB_AUTH = InnertubeClient(
            clientName = "WEB",
            clientVersion = "2.20250312.04.00",
            clientIdHeader = "1",
            userAgent = DownloaderImpl.USER_AGENT,
            needsSignatureTimestamp = true,
            sendCookies = true
        )
    }
}
