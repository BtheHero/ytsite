package com.framestack.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.framestack.android.R
import com.framestack.android.data.VideoItem
import com.framestack.android.data.VideoSource

@Composable
fun MainScreen(viewModel: PlayerViewModel) {
    val state by viewModel.uiState.collectAsState()
    var isFullscreen by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showSignInDialog by remember { mutableStateOf(false) }
    var queueQuery by remember { mutableStateOf("") }
    var showDescription by remember { mutableStateOf(false) }

    val filteredQueue = remember(state.queue, queueQuery) {
        val q = queueQuery.trim().lowercase()
        if (q.isEmpty()) state.queue
        else state.queue.filter {
            it.title.lowercase().contains(q) || it.author.lowercase().contains(q)
        }
    }

    Scaffold(
        containerColor = Color(0xFF09090B),
        topBar = {
            if (!isFullscreen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF1F1F26), Color(0xFF0E0E12))
                                    )
                                )
                                .border(1.dp, Color(0x44F59E0B), RoundedCornerShape(10.dp)),
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
                            color = Color(0xFFF4F4F5),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { showSignInDialog = true },
                            border = BorderStroke(
                                1.dp,
                                if (state.isSignedIn) Color(0x6634D399) else Color(0xFF27272A)
                            ),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = Color(0xFF111114),
                                contentColor = if (state.isSignedIn) Color(0xFF34D399) else Color(0xFFD4D4D8)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = if (state.isSignedIn) Icons.Rounded.VerifiedUser else Icons.Rounded.Login,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (state.isSignedIn) "Account" else "Sign In",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Button(
                            onClick = { showAddDialog = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF59E0B),
                                contentColor = Color(0xFF09090B)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add", fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                // Expandable Now Playing Info Card
                state.activeVideo?.let { vid ->
                    val description = state.resolvedStreams?.description.orEmpty()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF111114))
                            .border(1.dp, Color(0xFF1E1E24), RoundedCornerShape(14.dp))
                            .clickable(enabled = description.isNotBlank()) { showDescription = !showDescription }
                            .animateContentSize()
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
                                    color = Color(0xFFF4F4F5),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = if (showDescription) 4 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 20.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = vid.author,
                                        color = Color(0xFFA1A1AA),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val sbCount = state.resolvedStreams?.sponsorSegments?.size ?: 0
                                    if (sbCount > 0) {
                                        Surface(
                                            color = Color(0x2234D399),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "$sbCount skips",
                                                color = Color(0xFF34D399),
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
                                    tint = Color(0xFF71717A),
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                        }
                        if (showDescription && description.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = Color(0xFF27272A))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = description.take(400),
                                color = Color(0xFFA1A1AA),
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }
                }

                // Channels & Playlists Strip
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 4.dp)
                ) {
                    items(state.sources, key = { it.id }) { source ->
                        val active = source.id == state.activeSourceId
                        SourceChip(
                            source = source,
                            active = active,
                            onSelect = { viewModel.selectSource(source) },
                            onDelete = { viewModel.removeSource(source.id) }
                        )
                    }
                }

                // Queue Search + Refresh
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 8.dp)
                ) {
                    OutlinedTextField(
                        value = queueQuery,
                        onValueChange = { queueQuery = it },
                        placeholder = { Text("Search ${state.queue.size} videos...", fontSize = 13.sp, color = Color(0xFF71717A)) },
                        leadingIcon = {
                            Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF71717A), modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (queueQuery.isNotEmpty()) {
                                IconButton(onClick = { queueQuery = "" }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = Color(0xFF71717A), modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFF59E0B),
                            unfocusedBorderColor = Color(0xFF27272A),
                            focusedTextColor = Color(0xFFF4F4F5),
                            unfocusedTextColor = Color(0xFFF4F4F5),
                            focusedContainerColor = Color(0xFF111114),
                            unfocusedContainerColor = Color(0xFF111114)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        onClick = {
                            state.sources.firstOrNull { it.id == state.activeSourceId }
                                ?.let { viewModel.selectSource(it, forceRefresh = true) }
                        },
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF111114),
                        border = BorderStroke(1.dp, Color(0xFF27272A)),
                        modifier = Modifier.size(50.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = "Refresh source",
                                tint = Color(0xFFD4D4D8),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Queue List
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.loadingQueue -> {
                            CircularProgressIndicator(
                                color = Color(0xFFF59E0B),
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                        !state.queueError.isNullOrBlank() && state.queue.isEmpty() -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.align(Alignment.Center).padding(16.dp)
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
                                        containerColor = Color(0xFFF59E0B),
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
                                color = Color(0xFFA1A1AA),
                                fontSize = 13.sp,
                                modifier = Modifier.align(Alignment.Center).padding(16.dp)
                            )
                        }
                        else -> {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 24.dp)
                            ) {
                                itemsIndexed(filteredQueue, key = { _, item -> item.videoId }) { index, video ->
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
            onSignedIn = { cookies ->
                showSignInDialog = false
                viewModel.onYouTubeCookiesSaved(cookies)
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
    val bg by animateColorAsState(if (active) Color(0x26F59E0B) else Color(0xFF111114))
    val border by animateColorAsState(if (active) Color(0xFFF59E0B) else Color(0xFF27272A))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(width = 1.dp, color = border, shape = RoundedCornerShape(12.dp))
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Column {
            Text(
                text = source.title,
                color = if (active) Color(0xFFFDE68A) else Color(0xFFE4E4E7),
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = "${source.type.replaceFirstChar { it.uppercase() }} · ${source.category}",
                color = if (active) Color(0xFFF59E0B) else Color(0xFF71717A),
                fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Rounded.DeleteOutline,
                contentDescription = "Remove ${source.title}",
                tint = Color(0xFF71717A),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun QueueRow(
    index: Int,
    video: VideoItem,
    active: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(if (active) Color(0x22F59E0B) else Color(0xFF111114))
    val border by animateColorAsState(if (active) Color(0x99F59E0B) else Color(0xFF1E1E24))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(width = 1.dp, color = border, shape = RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(114.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(9.dp))
                .background(Color(0xFF18181B))
        ) {
            AsyncImage(
                model = video.thumbnailUrl,
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
                        .background(Color(0xFFF59E0B), RoundedCornerShape(4.dp))
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
                        .background(Color(0xCC09090B), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                color = if (active) Color(0xFFFEF3C7) else Color(0xFFF4F4F5),
                fontSize = 14.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = "#%02d · %s".format(index, video.author),
                color = if (active) Color(0xFFFBBF24) else Color(0xFF71717A),
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
        containerColor = Color(0xFF111114),
        titleContentColor = Color(0xFFF4F4F5),
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
                    containerColor = Color(0xFFF59E0B),
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
                Text("Cancel", color = Color(0xFFA1A1AA))
            }
        }
    )
}
