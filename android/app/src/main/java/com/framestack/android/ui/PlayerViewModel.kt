package com.framestack.android.ui

import android.app.Application
import android.app.UiModeManager
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.framestack.android.data.LoginRequiredException
import com.framestack.android.data.QualityOption
import com.framestack.android.data.ResolvedStreams
import com.framestack.android.data.VideoItem
import com.framestack.android.data.VideoSource
import com.framestack.android.data.YouTubeRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException

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
    val isLoginRequired: Boolean = false,
    val isSignedIn: Boolean = false,
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

    private val _uiState = MutableStateFlow(
        PlayerUiState(isSignedIn = repository.authManager.isSignedIn())
    )
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var feedJob: Job? = null
    private var streamJob: Job? = null
    private var prefetchJob: Job? = null

    init {
        val initialSources = repository.loadSources()
        _uiState.update { it.copy(sources = initialSources) }
        initialSources.firstOrNull()?.let { selectSource(it) }
    }

    fun selectSource(
        source: VideoSource,
        autoPlay: Boolean = !isTvDevice,
        forceRefresh: Boolean = false
    ) {
        feedJob?.cancel()
        _uiState.update {
            it.copy(
                activeSourceId = source.id,
                loadingQueue = true,
                queueError = null,
                queue = emptyList(),
                activeVideo = if (autoPlay) null else it.activeVideo,
                resolvedStreams = if (autoPlay) null else it.resolvedStreams,
                selectedQuality = if (autoPlay) null else it.selectedQuality,
                isLoginRequired = false
            )
        }
        feedJob = viewModelScope.launch {
            var startedPreviewPlay = false
            repository.fetchSourceVideos(
                source = source,
                forceRefresh = forceRefresh,
                onFastPreview = { previewItems ->
                    if (_uiState.value.activeSourceId == source.id && previewItems.isNotEmpty()) {
                        _uiState.update {
                            it.copy(
                                queue = previewItems,
                                loadingQueue = false,
                                queueError = null
                            )
                        }
                        if (autoPlay && !startedPreviewPlay && _uiState.value.activeVideo == null) {
                            startedPreviewPlay = true
                            previewItems.firstOrNull()?.let { playVideo(it) }
                        }
                    }
                }
            )
                .onSuccess { videos ->
                    if (_uiState.value.activeSourceId != source.id) return@onSuccess
                    _uiState.update {
                        it.copy(
                            queue = videos,
                            loadingQueue = false,
                            queueError = if (videos.isEmpty()) "No videos found in this feed." else null
                        )
                    }
                    if (autoPlay && !startedPreviewPlay) {
                        videos.firstOrNull()?.let { playVideo(it) }
                    }
                }
                .onFailure { err ->
                    if (_uiState.value.activeSourceId != source.id) return@onFailure
                    _uiState.update {
                        it.copy(
                            loadingQueue = false,
                            queueError = if (it.queue.isEmpty()) err.message ?: "Failed to load channel/playlist." else null
                        )
                    }
                }
        }
    }

    fun playVideo(video: VideoItem, forceRefresh: Boolean = false) {
        streamJob?.cancel()
        prefetchJob?.cancel()
        _uiState.update {
            it.copy(
                activeVideo = video,
                loadingStream = true,
                streamError = null,
                isLoginRequired = false,
                resolvedStreams = null,
                selectedQuality = null,
                skipBannerText = null
            )
        }
        streamJob = viewModelScope.launch {
            repository.resolveVideoStreams(video, forceRefresh = forceRefresh)
                .onSuccess { resolved ->
                    if (_uiState.value.activeVideo?.videoId != video.videoId) return@onSuccess
                    val preferredQuality = resolved.qualities.firstOrNull()
                    _uiState.update {
                        it.copy(
                            resolvedStreams = resolved,
                            selectedQuality = preferredQuality,
                            loadingStream = false,
                            isLoginRequired = false,
                            streamError = null
                        )
                    }
                    prefetchNextInQueue(video)
                }
                .onFailure { err ->
                    if (_uiState.value.activeVideo?.videoId != video.videoId) return@onFailure
                    val loginReq = err is LoginRequiredException ||
                        err is SignInConfirmNotBotException ||
                        (err.message?.contains("LOGIN_REQUIRED", ignoreCase = true) == true) ||
                        (err.message?.contains("not a bot", ignoreCase = true) == true)
                    _uiState.update {
                        it.copy(
                            loadingStream = false,
                            isLoginRequired = loginReq,
                            streamError = err.message ?: "Could not resolve video stream."
                        )
                    }
                }
        }
    }

    fun onYouTubeCookiesSaved(rawCookies: String) {
        repository.authManager.saveCookies(rawCookies)
        repository.clearStreamCache()
        _uiState.update {
            it.copy(
                isSignedIn = repository.authManager.isSignedIn(),
                isLoginRequired = false,
                streamError = null
            )
        }
        val activeVid = _uiState.value.activeVideo
        if (activeVid != null) {
            playVideo(activeVid, forceRefresh = true)
        } else {
            _uiState.value.sources.firstOrNull { it.id == _uiState.value.activeSourceId }
                ?.let { selectSource(it, forceRefresh = true) }
        }
    }

    fun signOutYouTube() {
        repository.authManager.signOut()
        repository.clearStreamCache()
        _uiState.update { it.copy(isSignedIn = false) }
    }

    private fun prefetchNextInQueue(current: VideoItem) {
        val queue = _uiState.value.queue
        if (queue.size <= 1) return
        val idx = queue.indexOfFirst { it.videoId == current.videoId }
        val nextVideo = when {
            idx in 0 until queue.lastIndex -> queue[idx + 1]
            _uiState.value.loopQueue -> queue.firstOrNull()
            else -> null
        } ?: return
        if (nextVideo.videoId == current.videoId) return

        prefetchJob = viewModelScope.launch {
            repository.prefetchVideoStreams(nextVideo)
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
