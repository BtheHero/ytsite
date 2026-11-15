package com.framestack.android.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.framestack.android.data.DownloaderImpl
import com.framestack.android.data.QualityOption
import com.framestack.android.data.SponsorSegment
import kotlinx.coroutines.delay

@kotlin.OptIn(ExperimentalMaterial3Api::class)
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
    isLoginRequired: Boolean = false,
    skipBannerText: String?,
    onSelectQuality: (QualityOption) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onToggleShuffle: () -> Unit,
    onToggleLoop: () -> Unit,
    onToggleSponsorBlock: () -> Unit,
    onSkipBanner: (String) -> Unit,
    onClearSkipBanner: () -> Unit,
    onRetry: () -> Unit,
    onOpenSignIn: () -> Unit = {},
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit
) {
    val context = LocalContext.current

    val exoPlayer = remember {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 800, 1_500)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                playWhenReady = true
            }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var currentPosMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosMs by remember { mutableLongStateOf(0L) }

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableLongStateOf(0L) }
    var seekFeedbackText by remember { mutableStateOf<String?>(null) }
    var viewportWidthPx by remember { mutableIntStateOf(0) }

    var showQualitySheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var lastSkippedUuid by remember { mutableStateOf<String?>(null) }

    fun bumpControls() {
        controlsVisible = true
        interactionTick++
    }

    if (isFullscreen) {
        BackHandler { onToggleFullscreen() }
    }

    // Immersive landscape fullscreen
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

    var playbackErrorText by remember(quality?.videoUrl) { mutableStateOf<String?>(null) }

    // Listen for player state & completion
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) {
                    onNext()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackErrorText = "Playback failed (${error.errorCodeName}). Tap Retry to refresh stream URL."
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Prepare adaptive MergingMediaSource, HLS, or muxed stream with matching client User-Agent
    LaunchedEffect(quality?.videoUrl, quality?.audioUrl, quality?.userAgent, quality?.isHls) {
        val q = quality ?: return@LaunchedEffect
        playbackErrorText = null
        val resumePos = if (exoPlayer.currentPosition > 1000L && exoPlayer.mediaItemCount > 0) {
            exoPlayer.currentPosition
        } else {
            0L
        }
        lastSkippedUuid = null

        val dataSourceFactory = OkHttpDataSource.Factory(DownloaderImpl.getInstance().rawClient)
            .setUserAgent(q.userAgent ?: DownloaderImpl.USER_AGENT)

        val mediaSource = when {
            q.isHls -> {
                HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(q.videoUrl))
            }
            !q.isMuxed && !q.audioUrl.isNullOrBlank() -> {
                val videoSrc = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(q.videoUrl))
                val audioSrc = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(q.audioUrl))
                MergingMediaSource(videoSrc, audioSrc)
            }
            else -> {
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(q.videoUrl))
            }
        }

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        if (resumePos > 0L) exoPlayer.seekTo(resumePos)
        exoPlayer.playWhenReady = true
        bumpControls()
    }

    LaunchedEffect(playbackSpeed) {
        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
    }

    // Poll position smoothly & enforce SponsorBlock auto-skip
    LaunchedEffect(exoPlayer, sponsorSegments, sponsorBlockEnabled) {
        while (true) {
            if (!isScrubbing) {
                currentPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            }
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
            delay(200L)
        }
    }

    // Auto-hide controls while playing
    LaunchedEffect(controlsVisible, interactionTick, isPlaying, isScrubbing, showQualitySheet, showSpeedSheet, streamError) {
        if (controlsVisible && isPlaying && !isScrubbing && !showQualitySheet && !showSpeedSheet && streamError == null) {
            delay(3400L)
            controlsVisible = false
        }
    }

    LaunchedEffect(seekFeedbackText) {
        if (seekFeedbackText != null) {
            delay(750L)
            seekFeedbackText = null
        }
    }

    LaunchedEffect(skipBannerText) {
        if (skipBannerText != null) {
            delay(2400L)
            onClearSkipBanner()
        }
    }

    val displayedPosMs = if (isScrubbing) scrubPosMs else currentPosMs

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isFullscreen) Modifier.fillMaxSize() else Modifier.aspectRatio(16f / 9f))
            .clip(if (isFullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(16.dp))
            .background(Color.Black)
            .onSizeChanged { viewportWidthPx = it.width }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        if (controlsVisible) {
                            controlsVisible = false
                        } else {
                            bumpControls()
                        }
                    },
                    onDoubleTap = { offset ->
                        val isLeft = viewportWidthPx > 0 && offset.x < viewportWidthPx / 2f
                        if (isLeft) {
                            val target = (exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)
                            exoPlayer.seekTo(target)
                            currentPosMs = target
                            seekFeedbackText = "-10s"
                        } else {
                            val maxDur = exoPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                            val target = (exoPlayer.currentPosition + 10_000L).coerceAtMost(maxDur)
                            exoPlayer.seekTo(target)
                            currentPosMs = target
                            seekFeedbackText = "+10s"
                        }
                        interactionTick++
                    }
                )
            },
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
            modifier = Modifier.fillMaxSize()
        )

        // Subtle loading indicator
        if (loadingStream || (isBuffering && streamError == null)) {
            CircularProgressIndicator(
                color = Color(0xFFF59E0B),
                strokeWidth = 3.dp,
                modifier = Modifier.size(38.dp)
            )
        }

        // Double-tap seek feedback pill
        AnimatedVisibility(
            visible = seekFeedbackText != null,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f)
        ) {
            Surface(
                color = Color(0xCC09090B),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color(0x55F59E0B))
            ) {
                Text(
                    text = seekFeedbackText.orEmpty(),
                    color = Color(0xFFFDE68A),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }
        }

        // SponsorBlock skipped notification
        AnimatedVisibility(
            visible = !skipBannerText.isNullOrBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            Surface(
                color = Color(0xFF34D399),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = skipBannerText.orEmpty(),
                    color = Color(0xFF09090B),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        val activeErrorText = streamError ?: playbackErrorText

        // Stream Error Overlay with Sign In & Retry
        if (!activeErrorText.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xE009090B)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Text(
                        text = activeErrorText,
                        color = Color(0xFFFCA5A5),
                        fontSize = 13.sp,
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (isLoginRequired) {
                            Button(
                                onClick = onOpenSignIn,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFF59E0B),
                                    contentColor = Color(0xFF09090B)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Rounded.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sign in with YouTube", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Button(
                            onClick = {
                                playbackErrorText = null
                                onRetry()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isLoginRequired) Color(0xFF27272A) else Color(0xFFF59E0B),
                                contentColor = if (isLoginRequired) Color(0xFFF4F4F5) else Color(0xFF09090B)
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Sleek Translucent OSD Controls
        AnimatedVisibility(
            visible = controlsVisible && activeErrorText == null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.0f to Color(0xAA09090B),
                            0.35f to Color(0x3309090B),
                            0.65f to Color(0x3309090B),
                            1.0f to Color(0xD809090B)
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                // Top quick-setting pills
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlayerOverlayPill(
                            text = quality?.label ?: "Auto",
                            highlighted = true,
                            onClick = {
                                showQualitySheet = true
                                bumpControls()
                            }
                        )
                        PlayerOverlayPill(
                            text = "${playbackSpeed}x",
                            highlighted = playbackSpeed != 1.0f,
                            onClick = {
                                showSpeedSheet = true
                                bumpControls()
                            }
                        )
                        PlayerOverlayPill(
                            text = if (sponsorBlockEnabled) "SB On" else "SB Off",
                            accentColor = if (sponsorBlockEnabled) Color(0xFF34D399) else Color(0xFFA1A1AA),
                            onClick = {
                                onToggleSponsorBlock()
                                bumpControls()
                            }
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                onToggleShuffle()
                                bumpControls()
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (shuffle) Color(0xFFF59E0B) else Color(0xFFD4D4D8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                onToggleLoop()
                                bumpControls()
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Repeat,
                                contentDescription = "Loop",
                                tint = if (loopQueue) Color(0xFFF59E0B) else Color(0xFFD4D4D8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                onToggleFullscreen()
                                bumpControls()
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                contentDescription = "Fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Center Play / Pause / Skip cluster
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    IconButton(
                        onClick = {
                            onPrevious()
                            bumpControls()
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0x6609090B))
                    ) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Surface(
                        onClick = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            bumpControls()
                        },
                        shape = CircleShape,
                        color = Color(0xFFF59E0B),
                        contentColor = Color(0xFF09090B),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            onNext()
                            bumpControls()
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0x6609090B))
                    ) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "Next",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Bottom Timeline + Timestamps
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    Text(
                        text = formatMillis(displayedPosMs),
                        color = Color(0xFFF4F4F5),
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
                                            size = Size(
                                                (size.width * (endFrac - startFrac)).coerceAtLeast(4f),
                                                size.height
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        Slider(
                            value = if (durationMs > 0L) displayedPosMs.toFloat() else 0f,
                            onValueChange = {
                                isScrubbing = true
                                scrubPosMs = it.toLong()
                                interactionTick++
                            },
                            onValueChangeFinished = {
                                exoPlayer.seekTo(scrubPosMs)
                                currentPosMs = scrubPosMs
                                isScrubbing = false
                                bumpControls()
                            },
                            valueRange = 0f..(if (durationMs > 0L) durationMs.toFloat() else 1f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFF59E0B),
                                activeTrackColor = Color(0xFFF59E0B),
                                inactiveTrackColor = Color(0x66FFFFFF)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Text(
                        text = formatMillis(durationMs),
                        color = Color(0xFFD4D4D8),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .width(44.dp)
                            .padding(start = 6.dp)
                    )
                }
            }
        }
    }

    if (showQualitySheet) {
        ModalBottomSheet(
            onDismissRequest = { showQualitySheet = false },
            containerColor = Color(0xFF111114),
            contentColor = Color(0xFFF4F4F5)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Video Quality", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                qualities.forEach { option ->
                    val selected = option == quality
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Color(0x22F59E0B) else Color.Transparent)
                            .clickable {
                                onSelectQuality(option)
                                showQualitySheet = false
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "${option.label}${if (option.isMuxed) " · Muxed" else ""}",
                            color = if (selected) Color(0xFFFDE68A) else Color(0xFFE4E4E7),
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                        if (selected) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    if (showSpeedSheet) {
        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        ModalBottomSheet(
            onDismissRequest = { showSpeedSheet = false },
            containerColor = Color(0xFF111114),
            contentColor = Color(0xFFF4F4F5)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Playback Speed", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                speeds.forEach { spd ->
                    val selected = spd == playbackSpeed
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) Color(0x22F59E0B) else Color.Transparent)
                            .clickable {
                                onSetSpeed(spd)
                                showSpeedSheet = false
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = if (spd == 1.0f) "1.0x (Normal)" else "${spd}x",
                            color = if (selected) Color(0xFFFDE68A) else Color(0xFFE4E4E7),
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                        if (selected) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun PlayerOverlayPill(
    text: String,
    highlighted: Boolean = false,
    accentColor: Color? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = Color(0x99111114),
        border = BorderStroke(
            width = 1.dp,
            color = if (highlighted) Color(0x99F59E0B) else Color(0x44FFFFFF)
        )
    ) {
        Text(
            text = text,
            color = accentColor ?: if (highlighted) Color(0xFFFBBF24) else Color(0xFFF4F4F5),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}

fun formatMillis(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
