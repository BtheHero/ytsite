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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
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
import androidx.compose.ui.text.style.TextOverflow
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
    videoId: String?,
    videoTitle: String?,
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
            .setTargetBufferBytes(32 * 1024 * 1024) // Prevent 4K memory/GC freeze
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBufferDurationsMs(8_000, 20_000, 750, 1_500)
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

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableLongStateOf(0L) }
    var seekFeedbackText by remember { mutableStateOf<String?>(null) }
    var viewportWidthPx by remember { mutableIntStateOf(0) }
    var isScrubbing by remember { mutableStateOf(false) }

    var showQualitySheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var lastSkippedUuid by remember { mutableStateOf<String?>(null) }
    var lastPreparedVideoId by remember { mutableStateOf<String?>(null) }
    var playbackErrorText by remember(videoId, quality?.videoUrl) { mutableStateOf<String?>(null) }

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

    // Stop previous codec cleanly when switching videos so 4K decoder slots never deadlock
    LaunchedEffect(videoId) {
        if (videoId != lastPreparedVideoId) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            lastSkippedUuid = null
        }
    }

    // Prepare adaptive MergingMediaSource, HLS, or muxed stream cleanly
    LaunchedEffect(videoId, quality?.videoUrl, quality?.audioUrl, quality?.userAgent, quality?.isHls) {
        val q = quality ?: return@LaunchedEffect
        playbackErrorText = null

        val isSameVideoQualitySwitch = videoId != null && videoId == lastPreparedVideoId
        val resumePos = if (isSameVideoQualitySwitch && exoPlayer.currentPosition > 1000L) {
            exoPlayer.currentPosition
        } else {
            0L
        }
        lastPreparedVideoId = videoId
        lastSkippedUuid = null

        // Flush existing codec before preparing a new (especially 4K) stream
        exoPlayer.stop()
        exoPlayer.clearMediaItems()

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

        if (resumePos > 0L) {
            exoPlayer.setMediaSource(mediaSource, resumePos)
        } else {
            exoPlayer.setMediaSource(mediaSource)
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        bumpControls()
    }

    LaunchedEffect(playbackSpeed) {
        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
    }

    // Isolated SponsorBlock monitor (zero root recompositions)
    SponsorBlockMonitor(
        exoPlayer = exoPlayer,
        isPlaying = isPlaying,
        sponsorSegments = sponsorSegments,
        sponsorBlockEnabled = sponsorBlockEnabled,
        lastSkippedUuid = lastSkippedUuid,
        onSkipped = { uuid, label ->
            lastSkippedUuid = uuid
            onSkipBanner(label)
        }
    )

    val activeErrorText = streamError ?: playbackErrorText

    // Auto-hide controls while playing
    LaunchedEffect(controlsVisible, interactionTick, isPlaying, isScrubbing, showQualitySheet, showSpeedSheet, activeErrorText) {
        if (controlsVisible && isPlaying && !isScrubbing && !showQualitySheet && !showSpeedSheet && activeErrorText == null) {
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

    val shape = if (isFullscreen) RoundedCornerShape(0.dp) else RoundedCornerShape(20.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isFullscreen) Modifier.fillMaxSize() else Modifier.aspectRatio(16f / 9f))
            .clip(shape)
            .background(Color.Black)
            .then(
                if (isFullscreen) Modifier else Modifier.border(
                    1.dp,
                    GlassBrushes.BorderDefault,
                    shape
                )
            )
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
                            seekFeedbackText = "-10s"
                        } else {
                            val maxDur = exoPlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                            val target = (exoPlayer.currentPosition + 10_000L).coerceAtMost(maxDur)
                            exoPlayer.seekTo(target)
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
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
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

        // Frosted loading badge
        if (loadingStream || (isBuffering && activeErrorText == null)) {
            Box(
                modifier = Modifier
                    .glassPanel(shape = CircleShape, elevated = true)
                    .padding(12.dp)
            ) {
                CircularProgressIndicator(
                    color = GlassColors.AccentAmber,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        // Double-tap seek feedback glass pill
        AnimatedVisibility(
            visible = seekFeedbackText != null,
            enter = fadeIn() + scaleIn(initialScale = 0.85f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f)
        ) {
            Box(
                modifier = Modifier
                    .glassPanel(shape = RoundedCornerShape(24.dp), highlighted = true)
                    .padding(horizontal = 20.dp, vertical = 9.dp)
            ) {
                Text(
                    text = seekFeedbackText.orEmpty(),
                    color = GlassColors.AccentAmberSoft,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // SponsorBlock skipped glass pill
        AnimatedVisibility(
            visible = !skipBannerText.isNullOrBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            Surface(
                color = Color(0xCC34D399),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Color(0x88FFFFFF))
            ) {
                Text(
                    text = skipBannerText.orEmpty(),
                    color = Color(0xFF09090B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        // Stream Error Glass Overlay with Sign In & Retry
        if (!activeErrorText.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC06070B)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .glassPanel(shape = RoundedCornerShape(18.dp), elevated = true)
                        .padding(horizontal = 20.dp, vertical = 16.dp)
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
                                    containerColor = GlassColors.AccentAmber,
                                    contentColor = Color(0xFF09090B)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null, modifier = Modifier.size(16.dp))
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
                                containerColor = if (isLoginRequired) Color(0xFF27272A) else GlassColors.AccentAmber,
                                contentColor = if (isLoginRequired) GlassColors.TextPrimary else Color(0xFF09090B)
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

        // Modern Floating Liquid Glass HUD
        AnimatedVisibility(
            visible = controlsVisible && activeErrorText == null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(GlassBrushes.HudScrim)
                    .padding(horizontal = 10.dp, vertical = 10.dp)
            ) {
                // Top Floating Glass Pill Bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .glassPanel(shape = RoundedCornerShape(14.dp), fillColor = GlassColors.GlassHud)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        val badgeLabel = quality?.let { formatResolutionBadge(it.height, it.label) } ?: "Auto"
                        PlayerOverlayPill(
                            text = badgeLabel,
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
                            text = if (sponsorBlockEnabled) "SB" else "SB Off",
                            accentColor = if (sponsorBlockEnabled) GlassColors.AccentEmerald else GlassColors.TextSecondary,
                            onClick = {
                                onToggleSponsorBlock()
                                bumpControls()
                            }
                        )
                        if (isFullscreen && !videoTitle.isNullOrBlank()) {
                            Text(
                                text = videoTitle,
                                color = GlassColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                onToggleShuffle()
                                bumpControls()
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (shuffle) GlassColors.AccentAmber else Color(0xFFD4D4D8),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                onToggleLoop()
                                bumpControls()
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Repeat,
                                contentDescription = "Loop",
                                tint = if (loopQueue) GlassColors.AccentAmber else Color(0xFFD4D4D8),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                onToggleFullscreen()
                                bumpControls()
                            },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                                contentDescription = "Fullscreen",
                                tint = Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }

                // Center Luminous Glass Transport Cluster
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .glassPanel(shape = CircleShape, fillColor = GlassColors.GlassHud)
                            .clickable {
                                onPrevious()
                                bumpControls()
                            }
                    ) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            contentDescription = "Previous",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(56.dp)
                            .background(GlassBrushes.AmberButton, CircleShape)
                            .border(1.5.dp, Color(0x99FFFFFF), CircleShape)
                            .clickable {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                bumpControls()
                            }
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color(0xFF09090B),
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(42.dp)
                            .glassPanel(shape = CircleShape, fillColor = GlassColors.GlassHud)
                            .clickable {
                                onNext()
                                bumpControls()
                            }
                    ) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "Next",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Bottom Detached Floating Glass Control Dock (isolated timeline polling)
                PlayerSeekBarDock(
                    exoPlayer = exoPlayer,
                    videoId = videoId,
                    sponsorSegments = sponsorSegments,
                    sponsorBlockEnabled = sponsorBlockEnabled,
                    onScrubStateChanged = { scrubbing ->
                        isScrubbing = scrubbing
                        interactionTick++
                    },
                    onSeekCompleted = { bumpControls() },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

    if (showQualitySheet) {
        ModalBottomSheet(
            onDismissRequest = { showQualitySheet = false },
            containerColor = Color(0xEB10141E),
            contentColor = GlassColors.TextPrimary
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Video Quality", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(10.dp))
                qualities.forEach { option ->
                    val selected = option == quality
                    val tag = if (option.height >= 2160) " · 4K UHD" else if (option.isMuxed) " · Muxed" else ""
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .glassPanel(shape = RoundedCornerShape(12.dp), highlighted = selected)
                            .clickable {
                                onSelectQuality(option)
                                showQualitySheet = false
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = "${option.label}$tag",
                            color = if (selected) GlassColors.AccentAmberSoft else GlassColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                        if (selected) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = GlassColors.AccentAmber, modifier = Modifier.size(18.dp))
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
            containerColor = Color(0xEB10141E),
            contentColor = GlassColors.TextPrimary
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
                            .padding(vertical = 3.dp)
                            .glassPanel(shape = RoundedCornerShape(12.dp), highlighted = selected)
                            .clickable {
                                onSetSpeed(spd)
                                showSpeedSheet = false
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = if (spd == 1.0f) "1.0x (Normal)" else "${spd}x",
                            color = if (selected) GlassColors.AccentAmberSoft else GlassColors.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                        if (selected) {
                            Icon(Icons.Rounded.Check, contentDescription = null, tint = GlassColors.AccentAmber, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SponsorBlockMonitor(
    exoPlayer: ExoPlayer,
    isPlaying: Boolean,
    sponsorSegments: List<SponsorSegment>,
    sponsorBlockEnabled: Boolean,
    lastSkippedUuid: String?,
    onSkipped: (uuid: String, label: String) -> Unit
) {
    LaunchedEffect(exoPlayer, isPlaying, sponsorSegments, sponsorBlockEnabled, lastSkippedUuid) {
        if (!sponsorBlockEnabled || !isPlaying || sponsorSegments.isEmpty()) return@LaunchedEffect
        while (true) {
            val sec = exoPlayer.currentPosition / 1000.0
            val hit = sponsorSegments.firstOrNull { seg ->
                seg.segment.size == 2 && sec >= seg.segment[0] && sec < seg.segment[1] - 0.6
            }
            if (hit != null && hit.UUID != lastSkippedUuid) {
                val targetMs = (hit.segment[1] * 1000.0).toLong()
                exoPlayer.seekTo(targetMs)
                val label = when (hit.category) {
                    "selfpromo" -> "Skipped self-promotion"
                    "interaction" -> "Skipped interaction reminder"
                    else -> "Skipped sponsor segment"
                }
                onSkipped(hit.UUID, label)
                break
            }
            delay(350L)
        }
    }
}

@Composable
private fun PlayerSeekBarDock(
    exoPlayer: ExoPlayer,
    videoId: String?,
    sponsorSegments: List<SponsorSegment>,
    sponsorBlockEnabled: Boolean,
    onScrubStateChanged: (Boolean) -> Unit,
    onSeekCompleted: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentPosMs by remember(videoId) { mutableLongStateOf(exoPlayer.currentPosition.coerceAtLeast(0L)) }
    var bufferedPosMs by remember(videoId) { mutableLongStateOf(exoPlayer.bufferedPosition.coerceAtLeast(0L)) }
    var durationMs by remember(videoId) { mutableLongStateOf(exoPlayer.duration.coerceAtLeast(0L)) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosMs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(exoPlayer, videoId) {
        while (true) {
            if (!isScrubbing) {
                currentPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            }
            bufferedPosMs = exoPlayer.bufferedPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
            delay(250L)
        }
    }

    val displayedPosMs = if (isScrubbing) scrubPosMs else currentPosMs

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .glassPanel(shape = RoundedCornerShape(16.dp), fillColor = GlassColors.GlassHud)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = formatMillis(displayedPosMs),
            color = GlassColors.TextPrimary,
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
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
            ) {
                if (durationMs > 0L) {
                    val bufFrac = (bufferedPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    drawRect(
                        color = Color(0x44FFFFFF),
                        topLeft = Offset.Zero,
                        size = Size(size.width * bufFrac, size.height)
                    )
                    if (sponsorBlockEnabled && sponsorSegments.isNotEmpty()) {
                        val durSec = durationMs / 1000f
                        for (seg in sponsorSegments) {
                            if (seg.segment.size == 2) {
                                val startFrac = (seg.segment[0].toFloat() / durSec).coerceIn(0f, 1f)
                                val endFrac = (seg.segment[1].toFloat() / durSec).coerceIn(0f, 1f)
                                drawRect(
                                    color = GlassColors.AccentEmerald,
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
            }

            Slider(
                value = if (durationMs > 0L) displayedPosMs.toFloat() else 0f,
                onValueChange = {
                    isScrubbing = true
                    scrubPosMs = it.toLong()
                    onScrubStateChanged(true)
                },
                onValueChangeFinished = {
                    exoPlayer.seekTo(scrubPosMs)
                    currentPosMs = scrubPosMs
                    isScrubbing = false
                    onScrubStateChanged(false)
                    onSeekCompleted()
                },
                valueRange = 0f..(if (durationMs > 0L) durationMs.toFloat() else 1f),
                colors = SliderDefaults.colors(
                    thumbColor = GlassColors.AccentAmberSoft,
                    activeTrackColor = GlassColors.AccentAmber,
                    inactiveTrackColor = Color(0x33FFFFFF)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Text(
            text = formatMillis(durationMs),
            color = GlassColors.TextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .width(44.dp)
                .padding(start = 6.dp)
        )
    }
}

@Composable
private fun PlayerOverlayPill(
    text: String,
    highlighted: Boolean = false,
    accentColor: Color? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .glassPanel(shape = RoundedCornerShape(9.dp), highlighted = highlighted)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = accentColor ?: if (highlighted) GlassColors.AccentAmberSoft else GlassColors.TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
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
