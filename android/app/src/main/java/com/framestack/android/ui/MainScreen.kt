package com.framestack.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.framestack.android.R
import com.framestack.android.data.VideoItem
import com.framestack.android.data.VideoSource

@Composable
fun MainScreen(viewModel: PlayerViewModel) {
    val state by viewModel.uiState.collectAsState()
    var isFullscreen by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showSignInDialog by remember { mutableStateOf(false) }
    var showSideMenu by rememberSaveable { mutableStateOf(true) }
    var showDescription by remember { mutableStateOf(false) }

    GlassBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                if (!isFullscreen) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                            .glassPanel(shape = RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .glassPanel(shape = RoundedCornerShape(10.dp), highlighted = true),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                                    contentDescription = "FrameStack Logo",
                                    tint = Color.Unspecified,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(9.dp))
                            Text(
                                text = "FrameStack",
                                color = GlassColors.TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { showSideMenu = !showSideMenu },
                                modifier = Modifier
                                    .size(36.dp)
                                    .glassPanel(shape = RoundedCornerShape(10.dp), highlighted = !showSideMenu)
                            ) {
                                Icon(
                                    imageVector = if (showSideMenu) Icons.AutoMirrored.Rounded.MenuOpen else Icons.Rounded.Menu,
                                    contentDescription = if (showSideMenu) "Hide menu" else "Show menu",
                                    tint = if (showSideMenu) GlassColors.TextPrimary else GlassColors.AccentAmberSoft,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            OutlinedButton(
                                onClick = { showSignInDialog = true },
                                border = BorderStroke(
                                    1.dp,
                                    if (state.isSignedIn) Color(0x6634D399) else Color(0x33FFFFFF)
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = Color(0x66131722),
                                    contentColor = if (state.isSignedIn) GlassColors.AccentEmerald else GlassColors.TextPrimary
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = if (state.isSignedIn) Icons.Rounded.VerifiedUser else Icons.AutoMirrored.Rounded.Login,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (state.isSignedIn) "Account" else "Sign In",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Button(
                                onClick = { showAddDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = GlassColors.AccentAmber,
                                    contentColor = Color(0xFF09090B)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (isFullscreen) PaddingValues(0.dp) else padding)
                    .padding(horizontal = if (isFullscreen) 0.dp else 12.dp)
            ) {
                NativeVideoPlayer(
                    videoId = state.activeVideo?.videoId,
                    videoTitle = state.resolvedStreams?.title ?: state.activeVideo?.title,
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
                        state.activeVideo?.let { viewModel.playVideo(it, forceRefresh = true) }
                    },
                    onOpenSignIn = { showSignInDialog = true },
                    onNext = viewModel::playNext,
                    onPrevious = viewModel::playPrevious,
                    isFullscreen = isFullscreen,
                    onToggleFullscreen = { isFullscreen = !isFullscreen }
                )

                if (!isFullscreen) {
                    // Expandable Frosted Glass Now Playing Info Card
                    state.activeVideo?.let { vid ->
                        val description = state.resolvedStreams?.description.orEmpty()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp)
                                .glassPanel(shape = RoundedCornerShape(16.dp))
                                .clickable(enabled = description.isNotBlank()) { showDescription = !showDescription }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = state.resolvedStreams?.title ?: vid.title,
                                        color = GlassColors.TextPrimary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = if (showDescription) 4 else 2,
                                        overflow = TextOverflow.Ellipsis,
                                        lineHeight = 20.sp
                                    )
                                    Spacer(modifier = Modifier.height(5.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = vid.author,
                                            color = GlassColors.TextSecondary,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        state.selectedQuality?.let { q ->
                                            Surface(
                                                color = Color(0x28F59E0B),
                                                shape = RoundedCornerShape(6.dp),
                                                border = BorderStroke(1.dp, Color(0x55F59E0B))
                                            ) {
                                                Text(
                                                    text = formatResolutionBadge(q.height, q.label),
                                                    color = GlassColors.AccentAmberSoft,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        val sbCount = state.resolvedStreams?.sponsorSegments?.size ?: 0
                                        if (sbCount > 0) {
                                            Surface(
                                                color = Color(0x2234D399),
                                                shape = RoundedCornerShape(6.dp)
                                            ) {
                                                Text(
                                                    text = "$sbCount skips",
                                                    color = GlassColors.AccentEmerald,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontFamily = FontFamily.Monospace,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                if (description.isNotBlank()) {
                                    Icon(
                                        imageVector = if (showDescription) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                        contentDescription = null,
                                        tint = GlassColors.TextSecondary,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }
                            }
                            if (showDescription && description.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = Color(0x22FFFFFF))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = description.take(400),
                                    color = GlassColors.TextSecondary,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                            }
                        }
                    }

                    // Instant Collapsible Sources & Queue Menu (no layout-thrashing expand animation)
                    if (showSideMenu) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            // Channels & Playlists Strip
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp, bottom = 6.dp)
                            ) {
                                items(
                                    items = state.sources,
                                    key = { it.id },
                                    contentType = { "source_chip" }
                                ) { source ->
                                    val active = source.id == state.activeSourceId
                                    SourceChip(
                                        source = source,
                                        active = active,
                                        onSelect = { viewModel.selectSource(source) },
                                        onDelete = { viewModel.removeSource(source.id) }
                                    )
                                }
                            }

                            // Compact Queue Header + Refresh
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp, bottom = 8.dp)
                            ) {
                                Text(
                                    text = "UP NEXT · ${state.queue.size} VIDEOS",
                                    color = GlassColors.TextMuted,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.8.sp
                                )

                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .glassPanel(shape = RoundedCornerShape(10.dp))
                                        .clickable {
                                            state.sources.firstOrNull { it.id == state.activeSourceId }
                                                ?.let { viewModel.selectSource(it, forceRefresh = true) }
                                        }
                                ) {
                                    Icon(
                                        Icons.Rounded.Refresh,
                                        contentDescription = "Refresh source",
                                        tint = GlassColors.TextPrimary,
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                            }

                            // Queue List
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                when {
                                    state.loadingQueue -> {
                                        CircularProgressIndicator(
                                            color = GlassColors.AccentAmber,
                                            modifier = Modifier.align(Alignment.Center)
                                        )
                                    }
                                    !state.queueError.isNullOrBlank() && state.queue.isEmpty() -> {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier
                                                .align(Alignment.Center)
                                                .padding(16.dp)
                                                .glassPanel(shape = RoundedCornerShape(16.dp))
                                                .padding(18.dp)
                                        ) {
                                            Text(
                                                text = state.queueError ?: "Failed to load queue.",
                                                color = Color(0xFFFCA5A5),
                                                fontSize = 13.sp
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Button(
                                                onClick = {
                                                    state.sources.firstOrNull { it.id == state.activeSourceId }
                                                        ?.let { viewModel.selectSource(it, forceRefresh = true) }
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = GlassColors.AccentAmber,
                                                    contentColor = Color(0xFF09090B)
                                                ),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text("Retry", fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                    state.queue.isEmpty() -> {
                                        Text(
                                            text = "No videos returned for this source. Tap refresh to retry.",
                                            color = GlassColors.TextSecondary,
                                            fontSize = 13.sp,
                                            modifier = Modifier.align(Alignment.Center).padding(16.dp)
                                        )
                                    }
                                    else -> {
                                        LazyColumn(
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                            contentPadding = PaddingValues(bottom = 24.dp)
                                        ) {
                                            itemsIndexed(
                                                items = state.queue,
                                                key = { _, item -> item.videoId },
                                                contentType = { _, _ -> "queue_row" }
                                            ) { index, video ->
                                                QueueRow(
                                                    index = index + 1,
                                                    video = video,
                                                    active = video.videoId == state.activeVideo?.videoId,
                                                    onClick = { viewModel.playVideo(video) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddSourceDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { url, title, category, onDone ->
                viewModel.addSource(url, title, category) { res ->
                    res.onSuccess {
                        onDone(null)
                        showAddDialog = false
                    }.onFailure { err ->
                        onDone(err.message ?: "Could not add YouTube source.")
                    }
                }
            }
        )
    }

    if (showSignInDialog) {
        YouTubeSignInDialog(
            isSignedIn = state.isSignedIn,
            authManager = viewModel.authManager,
            defaultToPairCode = false,
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

@Composable
private fun SourceChip(
    source: VideoSource,
    active: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .glassPanel(shape = RoundedCornerShape(14.dp), highlighted = active)
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Column {
            Text(
                text = source.title,
                color = if (active) GlassColors.AccentAmberSoft else GlassColors.TextPrimary,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = "${source.type.replaceFirstChar { it.uppercase() }} · ${source.category}",
                color = if (active) GlassColors.AccentAmber else GlassColors.TextMuted,
                fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Rounded.DeleteOutline,
                contentDescription = "Remove ${source.title}",
                tint = GlassColors.TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private val QueueRowShape = RoundedCornerShape(15.dp)
private val QueueThumbShape = RoundedCornerShape(10.dp)

@Composable
private fun QueueRow(
    index: Int,
    video: VideoItem,
    active: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val thumbRequest = remember(video.thumbnailUrl) {
        ImageRequest.Builder(context)
            .data(video.thumbnailUrl)
            .size(320, 180)
            .crossfade(false)
            .build()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(shape = QueueRowShape, highlighted = active)
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(114.dp)
                .aspectRatio(16f / 9f)
                .clip(QueueThumbShape)
                .background(Color(0xFF141822))
        ) {
            AsyncImage(
                model = thumbRequest,
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (active) {
                Text(
                    text = "PLAYING",
                    color = Color(0xFF09090B),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .background(GlassColors.AccentAmber, RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
            if (video.durationSeconds > 0L) {
                Text(
                    text = formatMillis(video.durationSeconds * 1000L),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp)
                        .background(Color(0xBB06070B), RoundedCornerShape(5.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                color = if (active) Color(0xFFFEF3C7) else GlassColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = "#%02d · %s".format(index, video.author),
                color = if (active) GlassColors.AccentAmber else GlassColors.TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AddSourceDialog(
    onDismiss: () -> Unit,
    onAdd: (url: String, title: String, category: String, onDone: (String?) -> Unit) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Curated") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        containerColor = Color(0xEB111520),
        titleContentColor = GlassColors.TextPrimary,
        title = { Text("Add YouTube Source", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text("Channel URL, @handle, or Playlist URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Custom Title (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!error.isNullOrBlank()) {
                    Text(error!!, color = Color(0xFFFCA5A5), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !loading && url.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = GlassColors.AccentAmber,
                    contentColor = Color(0xFF09090B)
                ),
                shape = RoundedCornerShape(10.dp),
                onClick = {
                    loading = true
                    error = null
                    onAdd(url, title, category) { errMsg ->
                        loading = false
                        error = errMsg
                    }
                }
            ) {
                Text(if (loading) "Loading..." else "Add Source", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(enabled = !loading, onClick = onDismiss) {
                Text("Cancel", color = GlassColors.TextSecondary)
            }
        }
    )
}
