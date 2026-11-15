package com.framestack.android.data

import android.content.Context
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
                val info = PlaylistInfo.getInfo(ServiceList.YouTube, playlistUrl)
                VideoSource(
                    id = "src-${System.currentTimeMillis()}",
                    type = "playlist",
                    title = customTitle.ifBlank { info.name.ifBlank { "YouTube Playlist" } },
                    category = category.ifBlank { "Curated" },
                    playlistId = playlistId,
                    url = playlistUrl
                )
            } else {
                val channelInfo = ChannelInfo.getInfo(ServiceList.YouTube, normalizedUrl)
                val channelId = channelInfo.id
                val uploadsPlaylistId = if (channelId.startsWith("UC")) {
                    "UU${channelId.removePrefix("UC")}"
                } else {
                    null
                }
                VideoSource(
                    id = "src-${System.currentTimeMillis()}",
                    type = "channel",
                    title = customTitle.ifBlank { channelInfo.name.ifBlank { "YouTube Channel" } },
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
                val playlistId = source.playlistId
                    ?: source.channelId?.takeIf { it.startsWith("UC") }?.let { "UU${it.drop(2)}" }

                val targetUrl = if (!playlistId.isNullOrBlank()) {
                    "https://www.youtube.com/playlist?list=$playlistId"
                } else {
                    val info = ChannelInfo.getInfo(ServiceList.YouTube, source.url)
                    val cid = info.id
                    "https://www.youtube.com/playlist?list=UU${cid.removePrefix("UC")}"
                }

                val playlistInfo = PlaylistInfo.getInfo(ServiceList.YouTube, targetUrl)
                playlistInfo.relatedItems
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
        val match = Regex("(?:v=|youtu\\.be/|shorts/)([A-Za-z0-9_-]{11})").find(url)
        return match?.groupValues?.getOrNull(1)
    }
}
