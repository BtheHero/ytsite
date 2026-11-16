package com.framestack.android.data

import kotlinx.serialization.Serializable

@Serializable
data class SourcesFile(
    val sources: List<VideoSource> = emptyList()
)

@Serializable
data class VideoSource(
    val id: String,
    val type: String, // "channel" or "playlist"
    val title: String,
    val category: String = "Curated",
    val channelId: String? = null,
    val playlistId: String? = null,
    val url: String
)

@Serializable
data class VideoItem(
    val videoId: String,
    val title: String,
    val author: String,
    val durationSeconds: Long,
    val thumbnailUrl: String,
    val videoUrl: String
)

data class QualityOption(
    val label: String,
    val height: Int,
    val videoUrl: String,
    val audioUrl: String?, // null when muxed stream includes audio
    val isMuxed: Boolean,
    val userAgent: String? = null,
    val isHls: Boolean = false
)

data class ResolvedStreams(
    val title: String,
    val author: String,
    val description: String,
    val durationSeconds: Long,
    val qualities: List<QualityOption>,
    val sponsorSegments: List<SponsorSegment>
)

@Serializable
data class SponsorSegment(
    val UUID: String = "",
    val category: String = "sponsor",
    val segment: List<Double> = emptyList()
)

class LoginRequiredException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
