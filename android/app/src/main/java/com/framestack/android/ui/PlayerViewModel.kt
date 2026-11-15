package com.framestack.android.ui

import android.app.Application
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.framestack.android.data.QualityOption
import com.framestack.android.data.ResolvedStreams
import com.framestack.android.data.VideoItem
import com.framestack.android.data.VideoSource
import com.framestack.android.data.YouTubeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlayerUiState(
    val sources: List<VideoSource> = emptyList(),
    val activeSourceId: String? = null,
    val queue: List<VideoItem> = emptyList(),
    val activeVideo: VideoItem? = null,
    val resolvedStreams: ResolvedStreams? = null,
    val selectedQuality: QualityOption? = null,
    val loadingQueue: Boolean = false,
    val loadingStream: Boolean = false,
    val queueError: String? = null,
    val streamError: String? = null,
    val shuffle: Boolean = false,
    val loopQueue: Boolean = true,
    val sponsorBlockEnabled: Boolean = true,
    val playbackSpeed: Float = 1.0f,
    val skipBannerText: String? = null
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = YouTubeRepository(application)

    private val isTvDevice: Boolean = run {
        val uiModeManager = application.getSystemService(UiModeManager::class.java)
        uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            application.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        val initialSources = repository.loadSources()
        _uiState.update { it.copy(sources = initialSources) }
        initialSources.firstOrNull()?.let { selectSource(it) }
    }

    fun selectSource(source: VideoSource, autoPlay: Boolean = !isTvDevice) {
        _uiState.update {
            it.copy(
                activeSourceId = source.id,
                loadingQueue = true,
                queueError = null,
                queue = emptyList(),
                activeVideo = if (autoPlay) null else it.activeVideo,
                resolvedStreams = if (autoPlay) null else it.resolvedStreams,
                selectedQuality = if (autoPlay) null else it.selectedQuality
            )
        }
        viewModelScope.launch {
            repository.fetchSourceVideos(source)
                .onSuccess { videos ->
                    _uiState.update {
                        it.copy(
                            queue = videos,
                            loadingQueue = false,
                            queueError = if (videos.isEmpty()) "No videos found in this feed." else null
                        )
                    }
                    if (autoPlay) {
                        videos.firstOrNull()?.let { playVideo(it) }
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            loadingQueue = false,
                            queueError = err.message ?: "Failed to load channel/playlist."
                        )
                    }
                }
        }
    }

    fun playVideo(video: VideoItem) {
        _uiState.update {
            it.copy(
                activeVideo = video,
                loadingStream = true,
                streamError = null,
                resolvedStreams = null,
                selectedQuality = null,
                skipBannerText = null
            )
        }
        viewModelScope.launch {
            repository.resolveVideoStreams(video)
                .onSuccess { resolved ->
                    val preferredQuality =
                        resolved.qualities.firstOrNull { it.height <= 1080 }
                            ?: resolved.qualities.firstOrNull()
                    _uiState.update {
                        it.copy(
                            resolvedStreams = resolved,
                            selectedQuality = preferredQuality,
                            loadingStream = false
                        )
                    }
                }
                .onFailure { err ->
                    _uiState.update {
                        it.copy(
                            loadingStream = false,
                            streamError = err.message ?: "Could not resolve video stream."
                        )
                    }
                }
        }
    }

    fun selectQuality(quality: QualityOption) {
        _uiState.update { it.copy(selectedQuality = quality) }
    }

    fun setPlaybackSpeed(speed: Float) {
        _uiState.update { it.copy(playbackSpeed = speed) }
    }

    fun toggleShuffle() {
        _uiState.update { it.copy(shuffle = !it.shuffle) }
    }

    fun toggleLoop() {
        _uiState.update { it.copy(loopQueue = !it.loopQueue) }
    }

    fun toggleSponsorBlock() {
        _uiState.update { it.copy(sponsorBlockEnabled = !it.sponsorBlockEnabled) }
    }

    fun showSkipBanner(message: String) {
        _uiState.update { it.copy(skipBannerText = message) }
    }

    fun clearSkipBanner() {
        _uiState.update { it.copy(skipBannerText = null) }
    }

    fun playNext() {
        val state = _uiState.value
        val queue = state.queue
        if (queue.isEmpty()) return

        if (state.shuffle && queue.size > 1) {
            val candidates = queue.filter { it.videoId != state.activeVideo?.videoId }
            candidates.randomOrNull()?.let { playVideo(it) }
            return
        }

        val currentIndex = queue.indexOfFirst { it.videoId == state.activeVideo?.videoId }
        when {
            currentIndex in 0 until queue.lastIndex -> playVideo(queue[currentIndex + 1])
            state.loopQueue -> playVideo(queue.first())
        }
    }

    fun playPrevious() {
        val state = _uiState.value
        val queue = state.queue
        if (queue.isEmpty()) return

        val currentIndex = queue.indexOfFirst { it.videoId == state.activeVideo?.videoId }
        when {
            currentIndex > 0 -> playVideo(queue[currentIndex - 1])
            state.loopQueue -> playVideo(queue.last())
        }
    }

    fun addSource(
        rawUrl: String,
        customTitle: String,
        category: String,
        onResult: (Result<VideoSource>) -> Unit
    ) {
        viewModelScope.launch {
            val result = repository.resolveAndAddSource(rawUrl, customTitle, category)
            result.onSuccess { newSource ->
                val updated = listOf(newSource) + _uiState.value.sources
                repository.saveSources(updated)
                _uiState.update { it.copy(sources = updated) }
                selectSource(newSource)
            }
            onResult(result)
        }
    }

    fun removeSource(sourceId: String) {
        val remaining = _uiState.value.sources.filterNot { it.id == sourceId }
        repository.saveSources(remaining)
        _uiState.update { it.copy(sources = remaining) }
        if (_uiState.value.activeSourceId == sourceId) {
            remaining.firstOrNull()?.let { selectSource(it) }
        }
    }

    fun resetDefaults() {
        val defaults = repository.resetDefaultSources()
        _uiState.update { it.copy(sources = defaults) }
        defaults.firstOrNull()?.let { selectSource(it) }
    }
}
