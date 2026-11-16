package com.framestack.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.framestack.android.R
import com.framestack.android.data.VideoItem
import com.framestack.android.data.VideoSource
import kotlinx.coroutines.delay

@Composable
fun TvMainScreen(viewModel: PlayerViewModel) {
    val state by viewModel.uiState.collectAsState()
    var isWatchingPlayer by rememberSaveable { mutableStateOf(false) }
    var showSideMenu by rememberSaveable { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showSignInDialog by remember { mutableStateOf(false) }

    val activeSource = remember(state.sources, state.activeSourceId) {
        state.sources.firstOrNull { it.id == state.activeSourceId } ?: state.sources.firstOrNull()
    }

    if (isWatchingPlayer) {
        val activeVideo = state.activeVideo
        TvVideoPlayer(
            videoId = activeVideo?.videoId,
            title = state.resolvedStreams?.title ?: activeVideo?.title ?: "FrameStack TV",
            author = state.resolvedStreams?.author ?: activeVideo?.author ?: "",
            quality = state.selectedQuality,
            qualities = state.resolvedStreams?.qualities ?: emptyList(),
            sponsorSegments = state.resolvedStreams?.sponsorSegments ?: emptyList(),
            sponsorBlockEnabled = state.sponsorBlockEnabled,
            playbackSpeed = state.playbackSpeed,
            shuffle = state.shuffle,
            loopQueue = state.loopQueue,
            loadingStream = state.loadingStream,
            streamError = state.streamError,
            isLoginRequired = state.isLoginRequired,
            skipBannerText = state.skipBannerText,
            onSelectQuality = viewModel::selectQuality,
            onSetSpeed = viewModel::setPlaybackSpeed,
            onToggleShuffle = viewModel::toggleShuffle,
            onToggleLoop = viewModel::toggleLoop,
            onToggleSponsorBlock = viewModel::toggleSponsorBlock,
            onSkipBanner = viewModel::showSkipBanner,
            onClearSkipBanner = viewModel::clearSkipBanner,
            onRetry = {
                activeVideo?.let { viewModel.playVideo(it, forceRefresh = true) }
            },
            onOpenSignIn = { showSignInDialog = true },
            onNext = viewModel::playNext,
            onPrevious = viewModel::playPrevious,
            onExitPlayer = { isWatchingPlayer = false }
        )
        if (showSignInDialog) {
            YouTubeSignInDialog(
                isSignedIn = state.isSignedIn,
                authManager = viewModel.authManager,
                defaultToPairCode = true,
                onSignedIn = { cookies ->
                    showSignInDialog = false
                    viewModel.onYouTubeCookiesSaved(cookies)
                },
                onOAuthSignedIn = {
                    showSignInDialog = false
                    viewModel.onOAuthSignedIn()
                },
                onSignOut = {
                    viewModel.signOutYouTube()
                    showSignInDialog = false
                },
                onDismiss = { showSignInDialog = false }
            )
        }
        return
    }

    val addSourceFocusRequester = remember { FocusRequester() }
    val menuToggleFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(80L)
        runCatching {
            if (showSideMenu) addSourceFocusRequester.requestFocus()
            else menuToggleFocusRequester.requestFocus()
        }
    }

    GlassBackdrop {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            // Hideable 10-foot Frosted Glass Navigation Rail (instant toggle for zero grid remeasure lag)
            if (showSideMenu) {
                Column(
                    modifier = Modifier
                        .width(276.dp)
                        .fillMaxHeight()
                        .padding(end = 22.dp)
                        .glassPanel(shape = RoundedCornerShape(22.dp), elevated = true)
                        .padding(18.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .glassPanel(shape = RoundedCornerShape(10.dp), highlighted = true),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                                    contentDescription = "FrameStack Logo",
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(38.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "FrameStack",
                                color = GlassColors.TextPrimary,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Surface(
                            color = Color(0x33F59E0B),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, GlassColors.AccentAmber)
                        ) {
                            Text(
                                text = "TV",
                                color = GlassColors.AccentAmberSoft,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    TvSidebarButton(
                        label = "Add Source",
                        icon = Icons.Rounded.Add,
                        isPrimary = true,
                        focusRequester = addSourceFocusRequester,
                        onClick = { showAddDialog = true }
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = "CHANNELS & PLAYLISTS",
                        color = GlassColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(
                            items = state.sources,
                            key = { it.id },
                            contentType = { "tv_source_nav" }
                        ) { source ->
                            TvSourceNavItem(
                                source = source,
                                isActive = source.id == activeSource?.id,
                                onClick = { viewModel.selectSource(source, autoPlay = false) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    TvSidebarButton(
                        label = if (state.isSignedIn) "YouTube Signed In" else "YouTube Sign-In",
                        icon = if (state.isSignedIn) Icons.Rounded.VerifiedUser else Icons.Rounded.Login,
                        isPrimary = false,
                        onClick = { showSignInDialog = true }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    TvSidebarButton(
                        label = "Reset Defaults",
                        icon = Icons.Rounded.RestartAlt,
                        isPrimary = false,
                        onClick = { viewModel.resetDefaults() }
                    )
                }
            }

            // Right 10-foot Frosted Glass Content Grid
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(shape = RoundedCornerShape(18.dp))
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        TvActionButton(
                            label = if (showSideMenu) "Hide Menu" else "Menu",
                            icon = if (showSideMenu) Icons.Rounded.MenuOpen else Icons.Rounded.Menu,
                            focusRequester = menuToggleFocusRequester,
                            onClick = { showSideMenu = !showSideMenu }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = activeSource?.title ?: "Select a Source",
                                color = GlassColors.TextPrimary,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = buildString {
                                    activeSource?.let {
                                        append(it.category)
                                        append(" • ")
                                    }
                                    append("${state.queue.size} videos")
                                },
                                color = GlassColors.TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.activeVideo != null) {
                            TvActionButton(
                                label = "Now Playing",
                                icon = Icons.Rounded.LiveTv,
                                highlighted = true,
                                onClick = { isWatchingPlayer = true }
                            )
                        } else if (state.queue.isNotEmpty()) {
                            TvActionButton(
                                label = "Play All",
                                icon = Icons.Rounded.PlayArrow,
                                highlighted = true,
                                onClick = {
                                    state.queue.firstOrNull()?.let { first ->
                                        viewModel.playVideo(first)
                                        isWatchingPlayer = true
                                    }
                                }
                            )
                        }

                        if (activeSource != null) {
                            TvActionButton(
                                label = "Refresh",
                                icon = Icons.Rounded.Refresh,
                                onClick = { viewModel.selectSource(activeSource, autoPlay = false, forceRefresh = true) }
                            )

                            if (state.sources.size > 1) {
                                TvActionButton(
                                    label = "Remove",
                                    icon = Icons.Rounded.DeleteOutline,
                                    onClick = { viewModel.removeSource(activeSource.id) }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                when {
                    state.loadingQueue -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .glassPanel(shape = RoundedCornerShape(18.dp))
                                    .padding(24.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = GlassColors.AccentAmber,
                                    modifier = Modifier.size(42.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Loading channel & playlist feed...",
                                    color = GlassColors.TextSecondary,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    }

                    !state.queueError.isNullOrBlank() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .glassPanel(shape = RoundedCornerShape(18.dp), elevated = true)
                                    .padding(28.dp)
                            ) {
                                Text(
                                    text = state.queueError ?: "Could not load feed.",
                                    color = Color(0xFFFCA5A5),
                                    fontSize = 16.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                TvActionButton(
                                    label = "Retry",
                                    icon = Icons.Rounded.Refresh,
                                    highlighted = true,
                                    onClick = {
                                        activeSource?.let { viewModel.selectSource(it, autoPlay = false, forceRefresh = true) }
                                    }
                                )
                            }
                        }
                    }

                    state.queue.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "No videos found for this source.",
                                color = GlassColors.TextSecondary,
                                fontSize = 16.sp
                            )
                        }
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(if (showSideMenu) 3 else 4),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                            contentPadding = PaddingValues(6.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(
                                items = state.queue,
                                key = { it.videoId },
                                contentType = { "tv_video_card" }
                            ) { video ->
                                TvVideoCard(
                                    video = video,
                                    isPlayingVideo = video.videoId == state.activeVideo?.videoId,
                                    onClick = {
                                        viewModel.playVideo(video)
                                        isWatchingPlayer = true
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        TvAddSourceDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { rawUrl, title, category, onDone ->
                viewModel.addSource(rawUrl, title, category) { result ->
                    if (result.isSuccess) {
                        showAddDialog = false
                        onDone(null)
                    } else {
                        onDone(result.exceptionOrNull()?.message ?: "Failed to add source.")
                    }
                }
            }
        )
    }

    if (showSignInDialog) {
        YouTubeSignInDialog(
            isSignedIn = state.isSignedIn,
            authManager = viewModel.authManager,
            defaultToPairCode = true,
            onSignedIn = { cookies ->
                showSignInDialog = false
                viewModel.onYouTubeCookiesSaved(cookies)
            },
            onOAuthSignedIn = {
                showSignInDialog = false
                viewModel.onOAuthSignedIn()
            },
            onSignOut = {
                viewModel.signOutYouTube()
                showSignInDialog = false
            },
            onDismiss = { showSignInDialog = false }
        )
    }
}

private val TvSidebarShape = RoundedCornerShape(12.dp)
private val TvCardShape = RoundedCornerShape(16.dp)
private val TvThumbClipShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)

@Composable
private fun TvSidebarButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isPrimary: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    val bg = when {
        focused -> GlassColors.AccentAmber
        isPrimary -> Color(0x36F59E0B)
        else -> Color(0x66171C2B)
    }
    val fg = when {
        focused -> Color(0xFF09090B)
        isPrimary -> GlassColors.AccentAmberSoft
        else -> GlassColors.TextPrimary
    }
    val border = when {
        focused -> GlassColors.AccentAmberSoft
        isPrimary -> GlassColors.AccentAmber
        else -> Color(0x2EFFFFFF)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .background(bg, TvSidebarShape)
            .border(if (focused) 2.dp else 1.dp, border, TvSidebarShape)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = fg,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

@Composable
private fun TvSourceNavItem(
    source: VideoSource,
    isActive: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    val bg = when {
        focused -> Color(0xAA242B3E)
        isActive -> Color(0x33F59E0B)
        else -> Color(0x66131722)
    }
    val border = when {
        focused -> GlassColors.AccentAmber
        isActive -> Color(0x99F59E0B)
        else -> Color(0x24FFFFFF)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, TvSidebarShape)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = border,
                shape = TvSidebarShape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(26.dp)
                .background(
                    color = if (isActive || focused) GlassColors.AccentAmber else Color(0x44FFFFFF),
                    shape = RoundedCornerShape(2.dp)
                )
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.title,
                color = GlassColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = if (isActive || focused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${source.category} • ${source.type.uppercase()}",
                color = if (isActive) GlassColors.AccentAmber else GlassColors.TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TvVideoCard(
    video: VideoItem,
    isPlayingVideo: Boolean,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val thumbRequest = remember(video.thumbnailUrl) {
        ImageRequest.Builder(context)
            .data(video.thumbnailUrl)
            .size(480, 270)
            .crossfade(false)
            .build()
    }

    val cardBg = if (focused) Color(0xCC1E2538) else GlassColors.GlassSurface
    val border = when {
        focused -> GlassColors.AccentAmber
        isPlayingVideo -> Color(0x99F59E0B)
        else -> Color(0x2EFFFFFF)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(cardBg, TvCardShape)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = border,
                shape = TvCardShape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(TvThumbClipShape)
                .background(Color(0xFF141822))
        ) {
            AsyncImage(
                model = thumbRequest,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            if (isPlayingVideo) {
                Text(
                    text = "PLAYING",
                    color = Color(0xFF09090B),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(GlassColors.AccentAmber, RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            if (video.durationSeconds > 0L) {
                Text(
                    text = formatTvDuration(video.durationSeconds),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color(0xBB06070B), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = video.title,
                color = if (focused) Color.White else GlassColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 19.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = video.author,
                color = if (focused) GlassColors.AccentAmber else GlassColors.TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TvAddSourceDialog(
    onDismiss: () -> Unit,
    onAdd: (url: String, title: String, category: String, onDone: (String?) -> Unit) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Curated") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (!loading) onDismiss() }) {
        Column(
            modifier = Modifier
                .widthIn(min = 440.dp, max = 560.dp)
                .glassPanel(shape = RoundedCornerShape(22.dp), elevated = true)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Add YouTube Channel or Playlist",
                color = GlassColors.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Channel URL, @handle, UC... or Playlist URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Custom Title (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = category,
                onValueChange = { category = it },
                label = { Text("Category") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (!loading && url.isNotBlank()) {
                            loading = true
                            error = null
                            onAdd(url, title, category) { errMsg ->
                                loading = false
                                error = errMsg
                            }
                        }
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            if (!error.isNullOrBlank()) {
                Text(error!!, color = Color(0xFFFCA5A5), fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TvActionButton(
                    label = "Cancel",
                    onClick = { if (!loading) onDismiss() }
                )
                Spacer(modifier = Modifier.width(12.dp))
                TvActionButton(
                    label = if (loading) "Adding..." else "Add Source",
                    icon = Icons.Rounded.Add,
                    highlighted = true,
                    onClick = {
                        if (!loading && url.isNotBlank()) {
                            loading = true
                            error = null
                            onAdd(url, title, category) { errMsg ->
                                loading = false
                                error = errMsg
                            }
                        }
                    }
                )
            }
        }
    }
}

private fun formatTvDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0L)
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0L) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
