package com.framestack.android.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
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

@OptIn(UnstableApi::class)
@Composable
fun TvVideoPlayer(
    title: String,
    author: String,
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
    onExitPlayer: () -> Unit
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
    var osdVisible by remember { mutableStateOf(true) }
    var osdInteractionTick by remember { mutableLongStateOf(0L) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var lastSkippedUuid by remember { mutableStateOf<String?>(null) }

    val rootFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }
    val retryFocusRequester = remember { FocusRequester() }

    fun bumpOsd() {
        osdVisible = true
        osdInteractionTick++
    }

    // Keep TV screen awake while full-screen player is mounted
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Handle Android TV Back button cleanly
    BackHandler {
        when {
            showQualityDialog -> showQualityDialog = false
            showSpeedDialog -> showSpeedDialog = false
            osdVisible && streamError == null -> osdVisible = false
            else -> onExitPlayer()
        }
    }

    var playbackErrorText by remember(quality?.videoUrl) { mutableStateOf<String?>(null) }

    // Listen to ExoPlayer events
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
                playbackErrorText = "Playback failed (${error.errorCodeName}). Press Retry to refresh stream."
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Prepare media source when quality/video changes
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
        bumpOsd()
    }

    LaunchedEffect(playbackSpeed) {
        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
    }

    // Poll progress and enforce SponsorBlock skips
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
            delay(2600L)
            onClearSkipBanner()
        }
    }

    // Auto-hide TV OSD after 5s of idle when playing
    LaunchedEffect(osdVisible, osdInteractionTick, isPlaying, showQualityDialog, showSpeedDialog, streamError) {
        if (osdVisible && isPlaying && !showQualityDialog && !showSpeedDialog && streamError == null) {
            delay(5000L)
            osdVisible = false
        }
    }

    // Shift focus automatically between player root and OSD controls
    LaunchedEffect(osdVisible, streamError) {
        delay(50L)
        runCatching {
            when {
                streamError != null -> retryFocusRequester.requestFocus()
                osdVisible -> playPauseFocusRequester.requestFocus()
                else -> rootFocusRequester.requestFocus()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                if (osdVisible) {
                    osdInteractionTick++
                }

                when (event.key) {
                    Key.MediaPlayPause -> {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        bumpOsd()
                        true
                    }
                    Key.MediaPlay -> {
                        exoPlayer.play()
                        bumpOsd()
                        true
                    }
                    Key.MediaPause -> {
                        exoPlayer.pause()
                        bumpOsd()
                        true
                    }
                    Key.MediaNext -> {
                        onNext()
                        bumpOsd()
                        true
                    }
                    Key.MediaPrevious -> {
                        onPrevious()
                        bumpOsd()
                        true
                    }
                    Key.MediaRewind -> {
                        exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                        bumpOsd()
                        true
                    }
                    Key.MediaFastForward -> {
                        exoPlayer.seekTo(exoPlayer.currentPosition + 10_000L)
                        bumpOsd()
                        true
                    }
                    Key.Menu -> {
                        bumpOsd()
                        true
                    }
                    else -> {
                        if (!osdVisible && streamError == null) {
                            when (event.key) {
                                Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    bumpOsd()
                                    true
                                }
                                Key.DirectionLeft -> {
                                    exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                                    bumpOsd()
                                    true
                                }
                                Key.DirectionRight -> {
                                    exoPlayer.seekTo(exoPlayer.currentPosition + 10_000L)
                                    bumpOsd()
                                    true
                                }
                                Key.DirectionUp, Key.DirectionDown -> {
                                    bumpOsd()
                                    true
                                }
                                else -> false
                            }
                        } else {
                            false
                        }
                    }
                }
            }
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
                .clickable { bumpOsd() }
        )

        if (loadingStream || (isBuffering && streamError == null)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (loadingStream) Color(0x8809090B) else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = Color(0xFFF59E0B),
                        modifier = Modifier.size(48.dp)
                    )
                    if (loadingStream) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Resolving native stream...",
                            color = Color(0xFFE4E4E7),
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }

        if (!skipBannerText.isNullOrBlank()) {
            Text(
                text = skipBannerText,
                color = Color(0xFF09090B),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .background(Color(0xFF34D399), RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }

        val activeErrorText = streamError ?: playbackErrorText

        if (!activeErrorText.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xDD09090B)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .widthIn(max = 580.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF111114))
                        .border(1.dp, Color(0xFF27272A), RoundedCornerShape(18.dp))
                        .padding(28.dp)
                ) {
                    Text(
                        text = if (isLoginRequired) "YouTube Sign-In Verification" else "Playback Error",
                        color = Color(0xFFFCA5A5),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = activeErrorText,
                        color = Color(0xFFD4D4D8),
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (isLoginRequired) {
                            TvActionButton(
                                label = "Sign in with YouTube",
                                icon = Icons.Rounded.Login,
                                highlighted = true,
                                focusRequester = retryFocusRequester,
                                onClick = onOpenSignIn
                            )
                        }
                        TvActionButton(
                            label = "Retry",
                            icon = Icons.Rounded.Refresh,
                            highlighted = !isLoginRequired,
                            focusRequester = if (isLoginRequired) null else retryFocusRequester,
                            onClick = {
                                playbackErrorText = null
                                onRetry()
                            }
                        )
                        TvActionButton(
                            label = "Back to Queue",
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            onClick = onExitPlayer
                        )
                    }
                }
            }
        }

        // TV On-Screen Display (OSD)
        AnimatedVisibility(
            visible = osdVisible && activeErrorText == null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top Info Bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xE609090B), Color.Transparent)
                            )
                        )
                        .padding(horizontal = 36.dp, vertical = 26.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        TvActionButton(
                            label = "Back",
                            icon = Icons.AutoMirrored.Rounded.ArrowBack,
                            onClick = onExitPlayer
                        )
                        Spacer(modifier = Modifier.width(18.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                color = Color(0xFFF4F4F5),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = author,
                                color = Color(0xFFA1A1AA),
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        quality?.let {
                            Surface(
                                color = Color(0xFF18181B),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFF3F3F46))
                            ) {
                                Text(
                                    text = it.label,
                                    color = Color(0xFFF59E0B),
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                        Surface(
                            color = Color(0xFF18181B),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFF3F3F46))
                        ) {
                            Text(
                                text = "${playbackSpeed}x",
                                color = Color(0xFFE4E4E7),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                // Bottom Control Bar + Timeline
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xEC09090B), Color(0xF709090B))
                            )
                        )
                        .padding(horizontal = 36.dp, vertical = 28.dp)
                ) {
                    // Non-focusable clean timeline bar with SponsorBlock segments
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = formatMillis(currentPosMs),
                            color = Color(0xFFE4E4E7),
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.width(64.dp)
                        )

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color(0x553F3F46))
                        ) {
                            val progress = if (durationMs > 0L) {
                                (currentPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                            } else 0f

                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(progress)
                                    .background(Color(0xFFF59E0B))
                            )

                            if (sponsorBlockEnabled && durationMs > 0L && sponsorSegments.isNotEmpty()) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
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
                        }

                        Text(
                            text = formatMillis(durationMs),
                            color = Color(0xFFA1A1AA),
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .width(64.dp)
                                .padding(start = 10.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // D-pad Focusable Transport & Settings Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvActionButton(
                                label = "Prev",
                                icon = Icons.Rounded.SkipPrevious,
                                onClick = {
                                    onPrevious()
                                    bumpOsd()
                                }
                            )
                            TvActionButton(
                                label = "-10s",
                                icon = Icons.Rounded.Replay10,
                                onClick = {
                                    exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L))
                                    bumpOsd()
                                }
                            )
                            TvActionButton(
                                label = if (isPlaying) "Pause" else "Play",
                                icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                highlighted = true,
                                focusRequester = playPauseFocusRequester,
                                onClick = {
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    bumpOsd()
                                }
                            )
                            TvActionButton(
                                label = "+10s",
                                icon = Icons.Rounded.Forward10,
                                onClick = {
                                    exoPlayer.seekTo(exoPlayer.currentPosition + 10_000L)
                                    bumpOsd()
                                }
                            )
                            TvActionButton(
                                label = "Next",
                                icon = Icons.Rounded.SkipNext,
                                onClick = {
                                    onNext()
                                    bumpOsd()
                                }
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TvActionButton(
                                label = quality?.label ?: "Quality",
                                icon = Icons.Rounded.HighQuality,
                                onClick = {
                                    showQualityDialog = true
                                    bumpOsd()
                                }
                            )
                            TvActionButton(
                                label = "${playbackSpeed}x",
                                icon = Icons.Rounded.Speed,
                                onClick = {
                                    showSpeedDialog = true
                                    bumpOsd()
                                }
                            )
                            TvActionButton(
                                label = "Sponsor",
                                icon = Icons.Rounded.ContentCut,
                                activeTint = if (sponsorBlockEnabled) Color(0xFF34D399) else null,
                                onClick = {
                                    onToggleSponsorBlock()
                                    bumpOsd()
                                }
                            )
                            TvActionButton(
                                label = "Shuffle",
                                icon = Icons.Rounded.Shuffle,
                                activeTint = if (shuffle) Color(0xFFF59E0B) else null,
                                onClick = {
                                    onToggleShuffle()
                                    bumpOsd()
                                }
                            )
                            TvActionButton(
                                label = "Loop",
                                icon = Icons.Rounded.Repeat,
                                activeTint = if (loopQueue) Color(0xFFF59E0B) else null,
                                onClick = {
                                    onToggleLoop()
                                    bumpOsd()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showQualityDialog) {
        TvOptionPickerDialog(
            title = "Select Video Quality",
            options = qualities.map {
                "${it.label}${if (it.isMuxed) " (Muxed)" else ""}" to (it == quality)
            },
            onSelectIndex = { idx ->
                qualities.getOrNull(idx)?.let(onSelectQuality)
                showQualityDialog = false
                bumpOsd()
            },
            onDismiss = { showQualityDialog = false }
        )
    }

    if (showSpeedDialog) {
        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        TvOptionPickerDialog(
            title = "Playback Speed",
            options = speeds.map { "${it}x" to (it == playbackSpeed) },
            onSelectIndex = { idx ->
                speeds.getOrNull(idx)?.let(onSetSpeed)
                showSpeedDialog = false
                bumpOsd()
            },
            onDismiss = { showSpeedDialog = false }
        )
    }
}

@Composable
fun TvActionButton(
    label: String,
    icon: ImageVector? = null,
    highlighted: Boolean = false,
    activeTint: Color? = null,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(if (focused) 1.07f else 1f)
    val bg by animateColorAsState(
        when {
            focused -> Color(0xFFF59E0B)
            highlighted -> Color(0xFF27272A)
            else -> Color(0xFF18181B)
        }
    )
    val fg = when {
        focused -> Color(0xFF09090B)
        activeTint != null -> activeTint
        else -> Color(0xFFF4F4F5)
    }
    val borderColor by animateColorAsState(
        if (focused) Color(0xFFFDE68A) else Color(0xFF27272A)
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(if (focused) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = fg,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = label,
            color = fg,
            fontSize = 14.sp,
            fontWeight = if (focused || highlighted) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun TvOptionPickerDialog(
    title: String,
    options: List<Pair<String, Boolean>>,
    onSelectIndex: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .widthIn(min = 320.dp, max = 420.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF111114))
                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(18.dp))
                .padding(24.dp)
        ) {
            Text(
                text = title,
                color = Color(0xFFF4F4F5),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(14.dp))
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 320.dp)
            ) {
                items(options.indices.toList()) { idx ->
                    val (label, isSelected) = options[idx]
                    var focused by remember { mutableStateOf(false) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (focused) Color(0xFFF59E0B) else Color(0xFF18181B))
                            .border(
                                width = if (focused) 2.dp else 1.dp,
                                color = if (focused) Color(0xFFFDE68A) else if (isSelected) Color(0xFFF59E0B) else Color(0xFF27272A),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .onFocusChanged { focused = it.isFocused }
                            .clickable { onSelectIndex(idx) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (focused) Color(0xFF09090B) else Color(0xFFF4F4F5),
                            fontSize = 16.sp,
                            fontWeight = if (isSelected || focused) FontWeight.Bold else FontWeight.Medium
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = if (focused) Color(0xFF09090B) else Color(0xFFF59E0B),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
