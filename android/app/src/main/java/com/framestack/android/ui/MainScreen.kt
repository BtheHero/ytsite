package com.framestack.android.ui

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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.framestack.android.data.VideoItem
import com.framestack.android.data.VideoSource

@Composable
fun MainScreen(viewModel: PlayerViewModel) {
    val state by viewModel.uiState.collectAsState()
    var isFullscreen by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var queueQuery by remember { mutableStateOf("") }

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
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "FrameStack",
                        color = Color(0xFFF4F4F5),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Button(
                        onClick = { showAddDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF59E0B),
                            contentColor = Color(0xFF09090B)
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Source", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                skipBannerText = state.skipBannerText,
                onSelectQuality = viewModel::selectQuality,
                onSetSpeed = viewModel::setPlaybackSpeed,
                onToggleShuffle = viewModel::toggleShuffle,
                onToggleLoop = viewModel::toggleLoop,
                onToggleSponsorBlock = viewModel::toggleSponsorBlock,
                onSkipBanner = viewModel::showSkipBanner,
                onClearSkipBanner = viewModel::clearSkipBanner,
                onNext = viewModel::playNext,
                onPrevious = viewModel::playPrevious,
                isFullscreen = isFullscreen,
                onToggleFullscreen = { isFullscreen = !isFullscreen }
            )

            if (!isFullscreen) {
                // Active Video Title & Metadata
                state.activeVideo?.let { vid ->
                    Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp)) {
                        Text(
                            text = state.resolvedStreams?.title ?: vid.title,
                            color = Color(0xFFF4F4F5),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${vid.author} · ${state.resolvedStreams?.sponsorSegments?.size ?: 0} sponsor markers",
                            color = Color(0xFFA1A1AA),
                            fontSize = 12.sp
                        )
                    }
                }

                // Channels & Playlists Strip
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
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
                        .padding(top = 10.dp, bottom = 6.dp)
                ) {
                    OutlinedTextField(
                        value = queueQuery,
                        onValueChange = { queueQuery = it },
                        placeholder = { Text("Filter queue...", fontSize = 13.sp, color = Color(0xFF71717A)) },
                        leadingIcon = {
                            Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF71717A), modifier = Modifier.size(18.dp))
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
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            state.sources.firstOrNull { it.id == state.activeSourceId }
                                ?.let { viewModel.selectSource(it) }
                        }
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh source", tint = Color(0xFFA1A1AA))
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
                                            ?.let { viewModel.selectSource(it) }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFF59E0B),
                                        contentColor = Color(0xFF09090B)
                                    )
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
                                verticalArrangement = Arrangement.spacedBy(6.dp),
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
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Color(0x22F59E0B) else Color(0xFF111114))
            .border(
                width = 1.dp,
                color = if (active) Color(0xFFF59E0B) else Color(0xFF27272A),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Column {
            Text(
                text = source.title,
                color = if (active) Color(0xFFFDE68A) else Color(0xFFE4E4E7),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = "${source.type.replaceFirstChar { it.uppercase() }} · ${source.category}",
                color = Color(0xFF71717A),
                fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Color(0x1DF59E0B) else Color(0xFF111114))
            .border(
                width = 1.dp,
                color = if (active) Color(0x55F59E0B) else Color(0xFF1E1E24),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(8.dp)
    ) {
        Text(
            text = "%02d".format(index),
            color = if (active) Color(0xFFF59E0B) else Color(0xFF71717A),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(24.dp)
        )

        AsyncImage(
            model = video.thumbnailUrl,
            contentDescription = video.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(96.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF18181B))
        )

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = video.title,
                color = if (active) Color(0xFFFEF3C7) else Color(0xFFE4E4E7),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "${video.author} · ${formatMillis(video.durationSeconds * 1000L)}",
                color = Color(0xFF71717A),
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
                onClick = {
                    loading = true
                    error = null
                    onAdd(url, title, category) { errMsg ->
                        loading = false
                        error = errMsg
                    }
                }
            ) {
                Text(if (loading) "Loading..." else "Add Source")
            }
        },
        dismissButton = {
            TextButton(enabled = !loading, onClick = onDismiss) {
                Text("Cancel", color = Color(0xFFA1A1AA))
            }
        }
    )
}
