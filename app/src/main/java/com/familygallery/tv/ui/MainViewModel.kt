package com.familygallery.tv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.familygallery.tv.GalleryApplication
import com.familygallery.tv.R
import com.familygallery.tv.data.AlbumSection
import com.familygallery.tv.data.MonthSummary
import com.familygallery.tv.data.PhotoEntity
import com.familygallery.tv.data.SeasonSummary
import com.familygallery.tv.data.SmartAlbum
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

sealed interface GalleryUiState {
    data object Loading : GalleryUiState
    /** [cacheVersion] is the loaded catalog's derivative generation; it keys the image cache. */
    data class Ready(val itemCount: Int, val cacheVersion: String) : GalleryUiState
    data class Error(val message: String) : GalleryUiState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as GalleryApplication).container.repository

    private val _state = MutableStateFlow<GalleryUiState>(GalleryUiState.Loading)
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    /** All photos/videos, newest-first, for the timeline grid + viewer. */
    val timelinePhotos: Flow<PagingData<PhotoEntity>> =
        repo.timeline().cachedIn(viewModelScope)

    private val _albumSections = MutableStateFlow<List<AlbumSection>>(emptyList())
    val albumSections: StateFlow<List<AlbumSection>> = _albumSections.asStateFlow()

    private val _smartAlbums = MutableStateFlow<List<SmartAlbum>>(emptyList())
    val smartAlbums: StateFlow<List<SmartAlbum>> = _smartAlbums.asStateFlow()

    private val _seasons = MutableStateFlow<List<SeasonSummary>>(emptyList())
    val seasons: StateFlow<List<SeasonSummary>> = _seasons.asStateFlow()

    // Captured once per load so the "On this day" summary card and its photo grid agree on the
    // date even if the app stays open across midnight.
    private val todayMonthDay = DateFormats.todayMonthDay()

    /** True while a [refresh] is in flight, so pull-to-refresh can show its spinner. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var connectJob: Job? = null

    init {
        connect()
    }

    /**
     * Initial connect (and the retry from the error screen): tears the UI down to the splash
     * while the catalog is synced, because at this point there is nothing worth showing.
     */
    fun connect() {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            _state.value = GalleryUiState.Loading
            load()
        }
    }

    /**
     * Re-sync the catalog *without* tearing the UI down — what phone pull-to-refresh needs.
     *
     * The difference from [connect] is only the missing `Loading` transition: the grid stays on
     * screen and scrolled where it was while the sync runs, and the repository invalidates its
     * paging windows on a successful swap, so new photos simply appear. A failure still surfaces
     * the error screen: [GalleryRepository.initialize] closes the catalog before syncing, so
     * there is genuinely nothing left to browse if it could not reopen one. (An unreachable NAS
     * is *not* such a failure — the sync falls back to the retained local catalog and succeeds.)
     */
    fun refresh() {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            _refreshing.value = true
            try {
                load()
            } finally {
                _refreshing.value = false
            }
        }
    }

    private suspend fun load() {
        repo.initialize().fold(
            onSuccess = { count ->
                // Publish Ready only once the lightweight album rows are present. Rendering
                // an empty Albums tab for one frame could consume initial focus on no target.
                _albumSections.value = runCatching { repo.albumSections() }
                    .getOrDefault(emptyList())
                _smartAlbums.value = runCatching { repo.smartAlbums(todayMonthDay) }
                    .getOrDefault(emptyList())
                _seasons.value = runCatching { repo.seasons() }.getOrDefault(emptyList())
                _state.value = GalleryUiState.Ready(count, repo.currentCacheVersion())
            },
            onFailure = { error ->
                val fallback = getApplication<Application>().getString(R.string.error_unknown)
                _state.value = GalleryUiState.Error(error.message ?: fallback)
            },
        )
    }

    fun albumPhotos(albumId: Long): Flow<PagingData<PhotoEntity>> =
        repo.albumPhotos(albumId).cachedIn(viewModelScope)

    fun videoPhotos(): Flow<PagingData<PhotoEntity>> =
        repo.videoPhotos().cachedIn(viewModelScope)

    fun onThisDayPhotos(): Flow<PagingData<PhotoEntity>> =
        repo.onThisDayPhotos(todayMonthDay).cachedIn(viewModelScope)

    fun seasonPhotos(season: Int): Flow<PagingData<PhotoEntity>> =
        repo.seasonPhotos(season).cachedIn(viewModelScope)

    /** Months (with covers) that have photos in [year]. */
    suspend fun monthsInYear(year: Int): List<MonthSummary> = repo.monthsInYear(year)

    fun monthPhotos(year: Int, month: Int): Flow<PagingData<PhotoEntity>> =
        repo.monthPhotos(year, month).cachedIn(viewModelScope)
}
