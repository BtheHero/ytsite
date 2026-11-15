package com.framestack.android.data

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLEncoder

class YouTubeRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val prefs = context.getSharedPreferences("framestack_prefs", Context.MODE_PRIVATE)

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

    suspend fun fetchSourceVideos(source: VideoSource): Result<List<VideoItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val channelId = source.channelId
                    ?: source.playlistId?.takeIf { it.startsWith("UU") }?.let { "UC${it.drop(2)}" }

                val playlistId = source.playlistId
                    ?: channelId?.takeIf { it.startsWith("UC") }?.let { "UU${it.drop(2)}" }

                // 1. Try NewPipeExtractor PlaylistInfo first (supports deep playlists + durations)
                if (!playlistId.isNullOrBlank()) {
                    val targetUrl = "https://www.youtube.com/playlist?list=$playlistId"
                    val npItems = runCatching {
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
                    }.getOrNull()

                    if (!npItems.isNullOrEmpty()) {
                        return@runCatching npItems
                    }
                }

                // 2. Reliable YouTube Atom XML feed fallback (works even when UU playlists or Innertube fail)
                val rssUrl = when {
                    source.type == "playlist" && !source.playlistId.isNullOrBlank() && !source.playlistId.startsWith("UU") ->
                        "https://www.youtube.com/feeds/videos.xml?playlist_id=${source.playlistId}"
                    !channelId.isNullOrBlank() ->
                        "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
                    !playlistId.isNullOrBlank() ->
                        "https://www.youtube.com/feeds/videos.xml?playlist_id=$playlistId"
                    else -> null
                }

                if (rssUrl != null) {
                    val rssItems = fetchAtomVideos(rssUrl, source.title)
                    if (rssItems.isNotEmpty()) {
                        return@runCatching rssItems
                    }
                }

                emptyList()
            }
        }

    suspend fun resolveVideoStreams(video: VideoItem): Result<ResolvedStreams> =
        withContext(Dispatchers.IO) {
            runCatching {
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

                val segments = fetchSponsorSegments(video.videoId)

                ResolvedStreams(
                    title = info.name ?: video.title,
                    author = info.uploaderName ?: video.author,
                    description = info.description?.content ?: "",
                    durationSeconds = info.duration,
                    qualities = combinedQualities,
                    sponsorSegments = segments
                )
            }
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
}
