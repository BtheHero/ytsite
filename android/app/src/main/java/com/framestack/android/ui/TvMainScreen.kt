package com.framestack.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.framestack.android.data.VideoItem
import com.framestack.android.data.VideoSource
import kotlinx.coroutines.delay

@Composable
fun TvMainScreen(viewModel: PlayerViewModel) {
    val state by viewModel.uiState.collectAsState()
    var isWatchingPlayer by rememberSaveable { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var queueQuery by remember { mutableStateOf("") }

    val activeSource = remember(state.sources, state.activeSourceId) {
        state.sources.firstOrNull { it.id == state.activeSourceId } ?: state.sources.firstOrNull()
    }

    val filteredQueue = remember(state.queue, queueQuery) {
        val q = queueQuery.trim().lowercase()
        if (q.isEmpty()) state.queue
        else state.queue.filter {
            it.title.lowercase().contains(q) || it.author.lowercase().contains(q)
        }
    }

    if (isWatchingPlayer) {
        val activeVideo = state.activeVideo
        TvVideoPlayer(
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
            skipBannerText = state.skipBannerText,
            onSelectQuality = viewModel::selectQuality,
            onSetSpeed = viewModel::setPlaybackSpeed,
            onToggleShuffle = viewModel::toggleShuffle,
            onToggleLoop = viewModel::toggleLoop,
            onToggleSponsorBlock = viewModel::toggleSponsorBlock,
            onSkipBanner = viewModel::showSkipBanner,
            onClearSkipBanner = viewModel::clearSkipBanner,
            onRetry = {
                activeVideo?.let { viewModel.playVideo(it) }
            },
            onNext = viewModel::playNext,
            onPrevious = viewModel::playPrevious,
            onExitPlayer = { isWatchingPlayer = false }
        )
        return
    }

    val addSourceFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80L)
        runCatching { addSourceFocusRequester.requestFocus() }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF09090B))
            .padding(24.dp)
    ) {
        // Left 10-foot TV Navigation Rail
        Column(
            modifier = Modifier
                .width(276.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF111114))
                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(18.dp))
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "FrameStack",
                    color = Color(0xFFF4F4F5),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    color = Color(0x33F59E0B),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, Color(0xFFF59E0B))
                ) {
                    Text(
                        text = "TV",
                        color = Color(0xFFF59E0B),
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
                color = Color(0xFF71717A),
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
                items(state.sources, key = { it.id }) { source ->
                    TvSourceNavItem(
                        source = source,
                        isActive = source.id == activeSource?.id,
                        onClick = { viewModel.selectSource(source, autoPlay = false) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            TvSidebarButton(
                label = "Reset Defaults",
                icon = Icons.Rounded.RestartAlt,
                isPrimary = false,
                onClick = { viewModel.resetDefaults() }
            )
        }

        // Right 10-foot Content Grid
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeSource?.title ?: "Select a Source",
                        color = Color(0xFFF4F4F5),
                        fontSize = 26.sp,
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
                            append("${filteredQueue.size} videos")
                        },
                        color = Color(0xFFA1A1AA),
                        fontSize = 14.sp
                    )
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
                    } else if (filteredQueue.isNotEmpty()) {
                        TvActionButton(
                            label = "Play All",
                            icon = Icons.Rounded.PlayArrow,
                            highlighted = true,
                            onClick = {
                                filteredQueue.firstOrNull()?.let { first ->
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
                            onClick = { viewModel.selectSource(activeSource, autoPlay = false) }
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

            Spacer(modifier = Modifier.height(14.dp))

            OutlinedTextField(
                value = queueQuery,
                onValueChange = { queueQuery = it },
                placeholder = { Text("Filter videos in queue...", color = Color(0xFF71717A)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF71717A)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFF59E0B),
                    unfocusedBorderColor = Color(0xFF27272A),
                    focusedTextColor = Color(0xFFF4F4F5),
                    unfocusedTextColor = Color(0xFFF4F4F5),
                    focusedContainerColor = Color(0xFF111114),
                    unfocusedContainerColor = Color(0xFF111114)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(18.dp))

            when {
                state.loadingQueue -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                color = Color(0xFFF59E0B),
                                modifier = Modifier.size(42.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Loading channel & playlist feed...",
                                color = Color(0xFFA1A1AA),
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
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF111114))
                                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(16.dp))
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
                                    activeSource?.let { viewModel.selectSource(it, autoPlay = false) }
                                }
                            )
                        }
                    }
                }

                filteredQueue.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "No videos match your filter.",
                                color = Color(0xFFA1A1AA),
                                fontSize = 16.sp
                            )
                            if (queueQuery.isNotBlank()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                TvActionButton(
                                    label = "Clear Filter",
                                    icon = Icons.Rounded.Clear,
                                    onClick = { queueQuery = "" }
                                )
                            }
                        }
                    }
                }

                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(6.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredQueue, key = { it.videoId }) { video ->
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
}

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
        focused -> Color(0xFFF59E0B)
        isPrimary -> Color(0x26F59E0B)
        else -> Color(0xFF18181B)
    }
    val fg = when {
        focused -> Color(0xFF09090B)
        isPrimary -> Color(0xFFF59E0B)
        else -> Color(0xFFE4E4E7)
    }
    val border = when {
        focused -> Color(0xFFFDE68A)
        isPrimary -> Color(0xFFF59E0B)
        else -> Color(0xFF27272A)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .scale(if (focused) 1.03f else 1f)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(if (focused) 2.dp else 1.dp, border, RoundedCornerShape(10.dp))
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
        focused -> Color(0xFF27272A)
        isActive -> Color(0x22F59E0B)
        else -> Color(0xFF18181B)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (focused) 1.03f else 1f)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = when {
                    focused -> Color(0xFFF59E0B)
                    isActive -> Color(0x99F59E0B)
                    else -> Color(0xFF27272A)
                },
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (isActive || focused) Color(0xFFF59E0B) else Color(0xFF3F3F46))
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.title,
                color = Color(0xFFF4F4F5),
                fontSize = 14.sp,
                fontWeight = if (isActive || focused) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${source.category} • ${source.type.uppercase()}",
                color = if (isActive) Color(0xFFF59E0B) else Color(0xFF71717A),
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (focused) 1.04f else 1f)
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color(0xFF1C1C22) else Color(0xFF111114))
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = when {
                    focused -> Color(0xFFF59E0B)
                    isPlayingVideo -> Color(0x88F59E0B)
                    else -> Color(0xFF27272A)
                },
                shape = RoundedCornerShape(14.dp)
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color(0xFF18181B))
        ) {
            AsyncImage(
                model = video.thumbnailUrl,
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
                        .background(Color(0xFFF59E0B), RoundedCornerShape(4.dp))
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
                        .background(Color(0xCC09090B), RoundedCornerShape(5.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = video.title,
                color = if (focused) Color(0xFFFFFFFF) else Color(0xFFF4F4F5),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 19.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = video.author,
                color = if (focused) Color(0xFFF59E0B) else Color(0xFFA1A1AA),
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
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF111114))
                .border(1.dp, Color(0xFF27272A), RoundedCornerShape(18.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Add YouTube Channel or Playlist",
                color = Color(0xFFF4F4F5),
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
