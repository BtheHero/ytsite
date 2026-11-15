package com.framestack.android.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.framestack.android.data.DownloaderImpl
import com.framestack.android.data.QualityOption
import com.framestack.android.data.SponsorSegment
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun NativeVideoPlayer(
    quality: QualityOption?,
    qualities: List<QualityOption>,
    sponsorSegments: List<SponsorSegment>,
    sponsorBlockEnabled: Boolean,
    playbackSpeed: Float,
    shuffle: Boolean,
    loopQueue: Boolean,
    loadingStream: Boolean,
    streamError: String?,
    skipBannerText: String?,
    onSelectQuality: (QualityOption) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleLoop: () -> Unit,
    onToggleSponsorBlock: () -> Unit,
    onSkipBanner: (String) -> Unit,
    onClearSkipBanner: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit
) {
    val context = LocalContext.current
    val dataSourceFactory = remember {
        OkHttpDataSource.Factory(DownloaderImpl.getInstance().client)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
    }

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var currentPosMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var showQualityMenu by remember { mutableStateOf(false) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var lastSkippedUuid by remember { mutableStateOf<String?>(null) }

    // Update immersive landscape system bars when fullscreen changes
    DisposableEffect(isFullscreen) {
        val activity = context as? Activity
        if (activity != null) {
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            if (isFullscreen) {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {}
    }

    // Listen for player state & video end
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    onNext()
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Load adaptive MergingMediaSource or muxed stream when quality changes
    LaunchedEffect(quality?.videoUrl, quality?.audioUrl) {
        val q = quality ?: return@LaunchedEffect
        val resumePos = if (exoPlayer.currentPosition > 1000L && exoPlayer.mediaItemCount > 0) {
            exoPlayer.currentPosition
        } else {
            0L
        }
        lastSkippedUuid = null

        val mediaSource = if (!q.isMuxed && !q.audioUrl.isNullOrBlank()) {
            val videoSrc = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(q.videoUrl))
            val audioSrc = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(q.audioUrl))
            MergingMediaSource(videoSrc, audioSrc)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(q.videoUrl))
        }

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        if (resumePos > 0L) exoPlayer.seekTo(resumePos)
        exoPlayer.playWhenReady = true
    }

    LaunchedEffect(playbackSpeed) {
        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
    }

    // Poll position & enforce SponsorBlock auto-skip
    LaunchedEffect(exoPlayer, sponsorSegments, sponsorBlockEnabled) {
        while (true) {
            currentPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.coerceAtLeast(0L)

            if (sponsorBlockEnabled && isPlaying && sponsorSegments.isNotEmpty()) {
                val sec = currentPosMs / 1000.0
                val hit = sponsorSegments.firstOrNull { seg ->
                    seg.segment.size == 2 && sec >= seg.segment[0] && sec < seg.segment[1] - 0.6
                }
                if (hit != null && hit.UUID != lastSkippedUuid) {
                    lastSkippedUuid = hit.UUID
                    val targetMs = (hit.segment[1] * 1000.0).toLong()
                    exoPlayer.seekTo(targetMs)
                    val label = when (hit.category) {
                        "selfpromo" -> "Skipped self-promotion"
                        "interaction" -> "Skipped interaction reminder"
                        else -> "Skipped sponsor segment"
                    }
                    onSkipBanner(label)
                }
            }
            delay(250L)
        }
    }

    LaunchedEffect(skipBannerText) {
        if (skipBannerText != null) {
            delay(2400L)
            onClearSkipBanner()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isFullscreen) Modifier.fillMaxHeight() else Modifier)
            .background(Color(0xFF09090B))
    ) {
        // Native Video Viewport (Zero WebView / Zero YouTube Overlay)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (isFullscreen) Modifier.weight(1f) else Modifier.aspectRatio(16f / 9f))
                .clip(if (isFullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(12.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        player = exoPlayer
                    }
                },
                update = { view ->
                    if (view.player !== exoPlayer) {
                        view.player = exoPlayer
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                    }
            )

            if (loadingStream) {
                CircularProgressIndicator(color = Color(0xFFF59E0B))
            }

            if (!streamError.isNullOrBlank()) {
                Text(
                    text = streamError,
                    color = Color(0xFFFCA5A5),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .background(Color(0xCC09090B), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }

            if (!skipBannerText.isNullOrBlank()) {
                Text(
                    text = skipBannerText,
                    color = Color(0xFF09090B),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(Color(0xFF34D399), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        // Custom Native Transport Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF111114))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // Scrubber + SponsorBlock timeline markers
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = formatMillis(currentPosMs),
                    color = Color(0xFFA1A1AA),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(44.dp)
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (sponsorBlockEnabled && durationMs > 0L && sponsorSegments.isNotEmpty()) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                        ) {
                            val durSec = durationMs / 1000f
                            for (seg in sponsorSegments) {
                                if (seg.segment.size == 2) {
                                    val startFrac = (seg.segment[0].toFloat() / durSec).coerceIn(0f, 1f)
                                    val endFrac = (seg.segment[1].toFloat() / durSec).coerceIn(0f, 1f)
                                    drawRect(
                                        color = Color(0xFF34D399),
                                        topLeft = Offset(size.width * startFrac, 0f),
                                        size = Size((size.width * (endFrac - startFrac)).coerceAtLeast(4f), size.height)
                                    )
                                }
                            }
                        }
                    }

                    Slider(
                        value = if (durationMs > 0L) currentPosMs.toFloat() else 0f,
                        onValueChange = { exoPlayer.seekTo(it.toLong()) },
                        valueRange = 0f..(if (durationMs > 0L) durationMs.toFloat() else 1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFF59E0B),
                            activeTrackColor = Color(0xFFF59E0B),
                            inactiveTrackColor = Color(0x5527272A)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Text(
                    text = formatMillis(durationMs),
                    color = Color(0xFFA1A1AA),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(44.dp)
                )
            }

            // Action Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPrevious) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = Color(0xFFE4E4E7))
                    }
                    IconButton(
                        onClick = { exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)) }
                    ) {
                        Icon(Icons.Rounded.Replay10, contentDescription = "Rewind 10s", tint = Color(0xFFE4E4E7))
                    }
                    FilledIconButton(
                        onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color(0xFFF59E0B),
                            contentColor = Color(0xFF09090B)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play"
                        )
                    }
                    IconButton(
                        onClick = { exoPlayer.seekTo(exoPlayer.currentPosition + 10_000L) }
                    ) {
                        Icon(Icons.Rounded.Forward10, contentDescription = "Forward 10s", tint = Color(0xFFE4E4E7))
                    }
                    IconButton(onClick = onNext) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Color(0xFFE4E4E7))
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Quality Picker
                    Box {
                        TextButton(onClick = { showQualityMenu = true }) {
                            Text(
                                text = quality?.label ?: "Auto",
                                color = Color(0xFFF59E0B),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        DropdownMenu(
                            expanded = showQualityMenu,
                            onDismissRequest = { showQualityMenu = false }
                        ) {
                            qualities.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text("${option.label}${if (option.isMuxed) " (Muxed)" else ""}")
                                    },
                                    onClick = {
                                        showQualityMenu = false
                                        onSelectQuality(option)
                                    }
                                )
                            }
                        }
                    }

                    // Speed Picker
                    Box {
                        TextButton(onClick = { showSpeedMenu = true }) {
                            Text(
                                text = "${playbackSpeed}x",
                                color = Color(0xFFE4E4E7),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        DropdownMenu(
                            expanded = showSpeedMenu,
                            onDismissRequest = { showSpeedMenu = false }
                        ) {
                            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                DropdownMenuItem(
                                    text = { Text("${spd}x") },
                                    onClick = {
                                        showSpeedMenu = false
                                        onSetSpeed(spd)
                                    }
                                )
                            }
                        }
                    }

                    IconButton(onClick = onToggleSponsorBlock) {
                        Icon(
                            Icons.Rounded.ContentCut,
                            contentDescription = "SponsorBlock",
                            tint = if (sponsorBlockEnabled) Color(0xFF34D399) else Color(0xFF71717A)
                        )
                    }

                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            Icons.Rounded.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (shuffle) Color(0xFFF59E0B) else Color(0xFF71717A)
                        )
                    }

                    IconButton(onClick = onToggleLoop) {
                        Icon(
                            Icons.Rounded.Repeat,
                            contentDescription = "Loop",
                            tint = if (loopQueue) Color(0xFFF59E0B) else Color(0xFF71717A)
                        )
                    }

                    IconButton(onClick = onToggleFullscreen) {
                        Icon(
                            imageVector = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                            contentDescription = "Fullscreen",
                            tint = Color(0xFFE4E4E7)
                        )
                    }
                }
            }
        }
    }
}

fun formatMillis(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
