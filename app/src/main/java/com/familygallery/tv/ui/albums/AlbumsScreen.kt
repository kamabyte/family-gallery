package com.familygallery.tv.ui.albums

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.PagingData
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import coil3.compose.AsyncImage
import com.familygallery.tv.R
import com.familygallery.tv.data.AlbumCategory
import com.familygallery.tv.data.AlbumSection
import com.familygallery.tv.data.MonthSummary
import com.familygallery.tv.data.PhotoEntity
import com.familygallery.tv.data.SeasonSummary
import com.familygallery.tv.data.Seasons
import com.familygallery.tv.data.SmartAlbum
import com.familygallery.tv.data.SmartAlbumKind
import com.familygallery.tv.smb.SmbImage
import com.familygallery.tv.ui.DateFormats
import com.familygallery.tv.ui.GridMetrics
import com.familygallery.tv.ui.LocalCatalogCacheVersion
import com.familygallery.tv.ui.LocalFormFactor
import com.familygallery.tv.ui.components.CenterMessage
import com.familygallery.tv.ui.components.FocusableCardGrid
import com.familygallery.tv.ui.components.cardGridPadding
import com.familygallery.tv.ui.rememberTileColumns
import kotlinx.coroutines.flow.Flow

private val PLACEHOLDER_COLOR = Color(0xFF1C2230)
private val TILE_SHAPE = RoundedCornerShape(12.dp)
private val ROW_SHAPE = RoundedCornerShape(14.dp)

/**
 * Albums tab, redesigned as a drill-down instead of horizontally scrolling carousels:
 *
 *  * **Root** — a vertical list of categories (Places / Cameras / Years);
 *  * **Places / Cameras** → a tile grid of albums → a photo grid;
 *  * **Years** → a tile grid of years → the year's months → that month's photo grid.
 *
 * Navigation lives inside this feature as a saveable back-stack; the shell only knows the tab
 * is selected. Every level uses [FocusableCardGrid] / [com.familygallery.tv.ui.components.PhotoBrowser],
 * i.e. the same single-lazy-layout focus contract as the timeline — which is what fixes the
 * old carousel focus loss (nested LazyRow scrolling desynced the focus requesters).
 */
@Composable
fun AlbumsScreen(
    sections: List<AlbumSection>,
    smartAlbums: List<SmartAlbum>,
    seasons: List<SeasonSummary>,
    monthsProvider: suspend (Int) -> List<MonthSummary>,
    albumPhotos: (Long) -> Flow<PagingData<PhotoEntity>>,
    monthPhotos: (Int, Int) -> Flow<PagingData<PhotoEntity>>,
    videoPhotos: () -> Flow<PagingData<PhotoEntity>>,
    onThisDayPhotos: () -> Flow<PagingData<PhotoEntity>>,
    seasonPhotos: (Int) -> Flow<PagingData<PhotoEntity>>,
    onRevealRail: () -> Unit,
    contentFocusRequester: FocusRequester,
    contentFocusRequestToken: Int,
    backEnabled: Boolean = true,
    autoFocusFirst: Boolean = false,
    onAutoFocusConsumed: () -> Unit = {},
    resetToRootToken: Int = 0,
) {
    if (sections.isEmpty() && smartAlbums.isEmpty() && seasons.isEmpty()) {
        CenterMessage(
            title = stringResource(R.string.no_albums_title),
            subtitle = stringResource(R.string.no_albums_subtitle),
        )
        return
    }

    val context = LocalContext.current
    val stateHolder = rememberSaveableStateHolder()
    val stack = rememberSaveable(saver = AlbumsStackSaver) {
        mutableStateListOf<AlbumsLevel>(AlbumsLevel.Root)
    }
    // Bumped on every push/pop so the active level re-focuses its remembered card when it
    // (re)appears — combined with the shell's rail-return token into one signal below.
    var levelBump by rememberSaveable { mutableIntStateOf(0) }

    fun push(level: AlbumsLevel) {
        stack.add(level)
        levelBump++
    }

    fun pop() {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            levelBump++
        }
    }

    // Back pops a drilled-in level. Disabled at the root (so the shell reveals the rail) and
    // while the rail owns focus (so Back there exits, matching the timeline). Photo-grid leaves
    // install their own Back via PhotoBrowser, which nests deeper and takes precedence.
    BackHandler(enabled = backEnabled && stack.size > 1) { pop() }

    // Re-tapping the already-selected Albums tab returns to the root of the drill-down — the
    // standard phone behaviour, and the only way back to the top without pressing Back N times.
    // The token starts at 0 and the shell only ever increments it, so this never fires on first
    // composition or on Activity recreation.
    var handledResetToken by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(resetToRootToken) {
        if (resetToRootToken > handledResetToken) {
            handledResetToken = resetToRootToken
            if (stack.size > 1) {
                stack.removeRange(1, stack.size)
                levelBump++
            }
        }
    }

    val current = stack.last()
    val effectiveToken = contentFocusRequestToken + levelBump
    val levelKey = remember(current) { encodeLevel(current) }
    // Breadcrumb trail for the current path, e.g. ["Альбомы", "Годы", "2025"].
    val crumbs = stack.map { crumbLabel(it, context) }

    stateHolder.SaveableStateProvider(levelKey) {
        when (current) {
            AlbumsLevel.Root -> {
                // Computed albums lead the list (Videos, On this day, Seasons), then the
                // catalog categories (Places, Cameras, Years).
                val categories = remember(sections, smartAlbums, seasons) {
                    buildList {
                        addAll(smartCategoriesOf(smartAlbums))
                        if (seasons.isNotEmpty()) add(seasonsCategory(seasons))
                        addAll(categoriesOf(sections))
                    }
                }
                CategoryListLevel(
                    categories = categories,
                    onRevealRail = onRevealRail,
                    contentFocusRequester = contentFocusRequester,
                    contentFocusRequestToken = effectiveToken,
                    autoFocus = autoFocusFirst,
                    onAutoFocusConsumed = onAutoFocusConsumed,
                    onOpen = { category ->
                        when (category.type) {
                            TYPE_VIDEOS -> push(AlbumsLevel.Videos)
                            TYPE_ON_THIS_DAY -> push(AlbumsLevel.OnThisDay)
                            TYPE_SEASONS -> push(AlbumsLevel.Seasons)
                            else -> push(AlbumsLevel.Category(category.type))
                        }
                    },
                )
            }

            AlbumsLevel.Seasons -> {
                val tiles = remember(seasons) {
                    seasons.mapIndexed { i, s ->
                        TileUi(
                            index = i,
                            key = "season-${s.season}",
                            cover = s.coverThumbPath,
                            title = seasonNameString(s.season, context),
                            subtitle = context.getString(R.string.album_item_count, s.photoCount),
                            icon = seasonIcon(s.season),
                        )
                    }
                }
                CoverTilesLevel(
                    crumbs = crumbs,
                    tiles = tiles,
                    onRevealRail = onRevealRail,
                    contentFocusRequester = contentFocusRequester,
                    contentFocusRequestToken = effectiveToken,
                    onOpen = { index -> push(AlbumsLevel.SeasonPhotos(seasons[index].season)) },
                    onBack = { pop() },
                )
            }

            is AlbumsLevel.SeasonPhotos -> AlbumDetailScreen(
                crumbs = crumbs,
                pagingFlow = remember(current.season) { seasonPhotos(current.season) },
                onBack = { pop() },
                backEnabled = backEnabled,
                onRevealRail = onRevealRail,
                contentFocusRequester = contentFocusRequester,
                contentFocusRequestToken = effectiveToken,
                // A season spans every year, so float the scroll date like the timeline.
                showMonthOverlay = true,
            )

            is AlbumsLevel.Category -> {
                val section = remember(sections, current) {
                    sections.firstOrNull { it.type == current.type }
                }
                val albums = section?.albums.orEmpty()
                val tiles = remember(albums) {
                    albums.mapIndexed { i, a ->
                        TileUi(i, a.id, a.coverThumbPath, a.name, context.getString(R.string.album_item_count, a.photoCount))
                    }
                }
                CoverTilesLevel(
                    crumbs = crumbs,
                    tiles = tiles,
                    onRevealRail = onRevealRail,
                    contentFocusRequester = contentFocusRequester,
                    contentFocusRequestToken = effectiveToken,
                    onOpen = { index ->
                        val album = albums[index]
                        if (current.type == "year") {
                            push(AlbumsLevel.YearMonths(album.name.toIntOrNull() ?: 0, album.name))
                        } else {
                            push(AlbumsLevel.AlbumPhotos(album.id, album.name))
                        }
                    },
                    onBack = { pop() },
                )
            }

            is AlbumsLevel.YearMonths -> {
                var months by remember(current.year) { mutableStateOf<List<MonthSummary>?>(null) }
                LaunchedEffect(current.year) { months = monthsProvider(current.year) }
                val list = months
                if (list == null) {
                    CenterMessage(stringResource(R.string.loading_photos))
                } else {
                    val tiles = remember(list) {
                        list.mapIndexed { i, m ->
                            TileUi(
                                index = i,
                                key = "${m.year}-${m.month}",
                                cover = m.coverThumbPath,
                                title = DateFormats.monthName(m.month),
                                subtitle = context.getString(R.string.album_item_count, m.photoCount),
                            )
                        }
                    }
                    CoverTilesLevel(
                        crumbs = crumbs,
                        tiles = tiles,
                        onRevealRail = onRevealRail,
                        contentFocusRequester = contentFocusRequester,
                        contentFocusRequestToken = effectiveToken,
                        onOpen = { index ->
                            val m = list[index]
                            push(AlbumsLevel.MonthPhotos(m.year, m.month, DateFormats.yearMonthLabel(m.year, m.month)))
                        },
                        onBack = { pop() },
                    )
                }
            }

            is AlbumsLevel.AlbumPhotos -> AlbumDetailScreen(
                crumbs = crumbs,
                pagingFlow = remember(current.albumId) { albumPhotos(current.albumId) },
                onBack = { pop() },
                backEnabled = backEnabled,
                onRevealRail = onRevealRail,
                contentFocusRequester = contentFocusRequester,
                contentFocusRequestToken = effectiveToken,
                // Place / camera albums span many months, so float the scroll date like the timeline.
                showMonthOverlay = true,
            )

            is AlbumsLevel.MonthPhotos -> AlbumDetailScreen(
                crumbs = crumbs,
                pagingFlow = remember(current.year, current.month) { monthPhotos(current.year, current.month) },
                onBack = { pop() },
                backEnabled = backEnabled,
                onRevealRail = onRevealRail,
                contentFocusRequester = contentFocusRequester,
                contentFocusRequestToken = effectiveToken,
            )

            AlbumsLevel.Videos -> AlbumDetailScreen(
                crumbs = crumbs,
                pagingFlow = remember { videoPhotos() },
                onBack = { pop() },
                backEnabled = backEnabled,
                onRevealRail = onRevealRail,
                contentFocusRequester = contentFocusRequester,
                contentFocusRequestToken = effectiveToken,
                // Videos accumulate across years, so float the scroll date like the timeline.
                showMonthOverlay = true,
            )

            AlbumsLevel.OnThisDay -> AlbumDetailScreen(
                crumbs = crumbs,
                pagingFlow = remember { onThisDayPhotos() },
                onBack = { pop() },
                backEnabled = backEnabled,
                onRevealRail = onRevealRail,
                contentFocusRequester = contentFocusRequester,
                contentFocusRequestToken = effectiveToken,
            )
        }
    }
}

/** One card the [FocusableCardGrid] draws; [index] maps a click back to the source list.
 * [icon] overlays a badge on the cover (used to brand the four Seasons tiles). */
private data class TileUi(
    val index: Int,
    val key: Any,
    val cover: String?,
    val title: String,
    val subtitle: String,
    val icon: Int? = null,
)

@Composable
private fun CategoryListLevel(
    categories: List<AlbumCategory>,
    onRevealRail: () -> Unit,
    contentFocusRequester: FocusRequester,
    contentFocusRequestToken: Int,
    autoFocus: Boolean,
    onAutoFocusConsumed: () -> Unit,
    onOpen: (AlbumCategory) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LevelTitle(stringResource(R.string.tab_albums))
        FocusableCardGrid(
            items = categories,
            itemKey = { it.type },
            // Full-width rows on every screen: the category list is short, and a single column
            // reads as a menu rather than a grid on both a TV and a phone.
            columns = 1,
            onRevealRail = onRevealRail,
            contentFocusRequester = contentFocusRequester,
            contentFocusRequestToken = contentFocusRequestToken,
            onClick = onOpen,
            modifier = Modifier.weight(1f),
            contentPadding = cardGridPadding(),
            verticalSpacing = 14.dp,
            autoFocus = autoFocus,
            onAutoFocusConsumed = onAutoFocusConsumed,
        ) { category, focused ->
            CategoryRow(category, focused)
        }
    }
}

@Composable
private fun CoverTilesLevel(
    crumbs: List<String>,
    tiles: List<TileUi>,
    onRevealRail: () -> Unit,
    contentFocusRequester: FocusRequester,
    contentFocusRequestToken: Int,
    onOpen: (Int) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    val formFactor = LocalFormFactor.current
    Column(Modifier.fillMaxSize()) {
        AlbumsBreadcrumb(crumbs = crumbs, onBack = onBack)
        if (tiles.isEmpty()) {
            CenterMessage(stringResource(R.string.albums_empty_category))
            return
        }
        val spacing = GridMetrics.tileSpacing(formFactor)
        FocusableCardGrid(
            items = tiles,
            itemKey = { it.key },
            // Four across on TV; on a phone the count follows the window width, so portrait shows
            // two readable tiles and landscape four rather than four slivers either way.
            columns = rememberTileColumns(),
            onRevealRail = onRevealRail,
            contentFocusRequester = contentFocusRequester,
            contentFocusRequestToken = contentFocusRequestToken,
            onClick = { tile -> onOpen(tile.index) },
            modifier = Modifier.weight(1f),
            contentPadding = cardGridPadding(),
            horizontalSpacing = spacing,
            verticalSpacing = spacing,
        ) { tile, focused ->
            CoverTile(tile, focused)
        }
    }
}

@Composable
private fun LevelTitle(title: String) {
    val horizontal = GridMetrics.screenPadding(LocalFormFactor.current)
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = horizontal, end = horizontal, top = 24.dp, bottom = 4.dp),
    )
}

/**
 * Breadcrumb header for a drilled-in Albums level: a Back affordance plus the path trail, so it's
 * always evident where you are and how to leave. Shared with [AlbumDetailScreen] (same package).
 *
 * The Back affordance means two different things per form factor:
 *
 *  * **TV** — a passive, non-focusable hint. The remote's Back key owns "go up a level" (handled
 *    by the screen's BackHandler / [PhotoBrowser]); making it focusable would only add a D-pad
 *    stop between the rail and the content.
 *  * **Touch** — a real tap target. Phones do have a system Back gesture, but an on-screen back
 *    button is the expected way out of a drill-down, and gesture navigation makes an edge swipe
 *    easy to miss.
 *
 * On a phone the trail is also reduced to the current level plus its parent: a four-deep path
 * ("Albums › Years › 2025 › July") does not fit in portrait, and the tail is the part that
 * carries the information.
 */
@Composable
internal fun AlbumsBreadcrumb(
    crumbs: List<String>,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    val formFactor = LocalFormFactor.current
    val horizontal = GridMetrics.screenPadding(formFactor)
    Row(
        modifier = modifier.padding(
            start = horizontal,
            end = horizontal,
            top = if (formFactor.isTv) 18.dp else 12.dp,
            bottom = 8.dp,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (formFactor.isTv) 20.dp else 10.dp),
    ) {
        val hint = Color.White.copy(alpha = 0.55f)
        val tappable = formFactor.isTouch && onBack != null
        Row(
            modifier = Modifier
                .clip(ROW_SHAPE)
                .then(if (tappable) Modifier.clickable(onClick = onBack!!) else Modifier)
                // A 48dp minimum touch target; on TV this is a label, so it stays compact.
                .padding(
                    horizontal = if (tappable) 8.dp else 0.dp,
                    vertical = if (tappable) 12.dp else 0.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_back),
                contentDescription = if (tappable) stringResource(R.string.back) else null,
                tint = if (tappable) Color.White else hint,
                modifier = Modifier.size(if (formFactor.isTv) 18.dp else 22.dp),
            )
            // The word "Back" is a discoverability hint for a remote. Next to a tappable arrow on
            // a narrow phone screen it is redundant and costs room the path trail needs.
            if (formFactor.isTv) {
                Text(
                    text = stringResource(R.string.back),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = hint,
                )
            }
        }

        val visibleCrumbs = if (formFactor.isTv) crumbs else crumbs.takeLast(2)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            visibleCrumbs.forEachIndexed { i, crumb ->
                if (i > 0) {
                    Text(
                        text = "›",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.35f),
                    )
                }
                val isCurrent = i == visibleCrumbs.lastIndex
                Text(
                    text = crumb,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(category: AlbumCategory, focused: Boolean) {
    val formFactor = LocalFormFactor.current
    val isTv = formFactor.isTv
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(ROW_SHAPE)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.06f))
            .then(
                if (focused) Modifier.border(3.dp, Color.White, ROW_SHAPE)
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), ROW_SHAPE),
            )
            .padding(horizontal = if (isTv) 22.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isTv) 18.dp else 14.dp),
    ) {
        val content = if (focused) Color(0xFF171522) else Color.White
        Icon(
            painter = painterResource(categoryIcon(category.type)),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(if (isTv) 28.dp else 24.dp),
        )
        Text(
            text = categoryTitle(category.type),
            // A 10-foot title at 22sp does not fit a 393dp phone: "Путешествия" wrapped and broke
            // mid-word next to the count. One line, ellipsised, at a size the row can hold.
            style = if (isTv) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.titleMedium
            },
            fontWeight = FontWeight.SemiBold,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = categoryCountLabel(category),
            style = if (isTv) {
                MaterialTheme.typography.bodyLarge
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = content.copy(alpha = 0.7f),
            // The count is short and fixed-ish; it must never wrap or steal the title's room.
            maxLines = 1,
        )
    }
}

@Composable
private fun CoverTile(tile: TileUi, focused: Boolean) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(TILE_SHAPE)
            .background(Color.White.copy(alpha = 0.07f))
            .then(
                if (focused) Modifier.border(3.dp, Color.White, TILE_SHAPE)
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.10f), TILE_SHAPE),
            ),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.3f)) {
            AsyncImage(
                model = tile.cover?.let { SmbImage(it, LocalCatalogCacheVersion.current) },
                contentDescription = tile.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(PLACEHOLDER_COLOR),
            )
            if (tile.icon != null) {
                Icon(
                    painter = painterResource(tile.icon),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(6.dp)
                        .size(22.dp),
                )
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = tile.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = tile.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

// --- Levels + saver -----------------------------------------------------------

/** One level of the Albums drill-down. Kept saveable via [encodeLevel] / [decodeLevel]. */
sealed interface AlbumsLevel {
    data object Root : AlbumsLevel
    data class Category(val type: String) : AlbumsLevel
    data class YearMonths(val year: Int, val yearName: String) : AlbumsLevel
    data class AlbumPhotos(val albumId: Long, val title: String) : AlbumsLevel
    data class MonthPhotos(val year: Int, val month: Int, val title: String) : AlbumsLevel
    data object Videos : AlbumsLevel
    data object OnThisDay : AlbumsLevel
    data object Seasons : AlbumsLevel
    data class SeasonPhotos(val season: Int) : AlbumsLevel
}

// Unit separator: never appears in album/month titles, so it is a safe field delimiter.
private const val SEP = ""

private fun encodeLevel(level: AlbumsLevel): String = when (level) {
    AlbumsLevel.Root -> "root"
    is AlbumsLevel.Category -> "cat$SEP${level.type}"
    is AlbumsLevel.YearMonths -> "year$SEP${level.year}$SEP${level.yearName}"
    is AlbumsLevel.AlbumPhotos -> "album$SEP${level.albumId}$SEP${level.title}"
    is AlbumsLevel.MonthPhotos -> "month$SEP${level.year}$SEP${level.month}$SEP${level.title}"
    AlbumsLevel.Videos -> "videos"
    AlbumsLevel.OnThisDay -> "onthisday"
    AlbumsLevel.Seasons -> "seasons"
    is AlbumsLevel.SeasonPhotos -> "season$SEP${level.season}"
}

private fun decodeLevel(encoded: String): AlbumsLevel {
    val p = encoded.split(SEP)
    return when (p[0]) {
        "cat" -> AlbumsLevel.Category(p[1])
        "year" -> AlbumsLevel.YearMonths(p[1].toInt(), p[2])
        "album" -> AlbumsLevel.AlbumPhotos(p[1].toLong(), p[2])
        "month" -> AlbumsLevel.MonthPhotos(p[1].toInt(), p[2].toInt(), p[3])
        "videos" -> AlbumsLevel.Videos
        "onthisday" -> AlbumsLevel.OnThisDay
        "seasons" -> AlbumsLevel.Seasons
        "season" -> AlbumsLevel.SeasonPhotos(p[1].toInt())
        else -> AlbumsLevel.Root
    }
}

private val AlbumsStackSaver = listSaver<SnapshotStateList<AlbumsLevel>, String>(
    save = { it.map(::encodeLevel) },
    restore = { it.map(::decodeLevel).toMutableStateList() },
)

// --- Derivations + labels -----------------------------------------------------

// Synthetic category types for the smart albums; distinct from the "place"/"camera"/"year"
// types the indexer emits, and used as the FocusableCardGrid item keys at the root.
private const val TYPE_VIDEOS = "videos"
private const val TYPE_ON_THIS_DAY = "onthisday"
private const val TYPE_SEASONS = "seasons"

private fun categoriesOf(sections: List<AlbumSection>): List<AlbumCategory> =
    sections.map { s -> AlbumCategory(s.type, s.albums.size, s.albums.firstOrNull()?.coverThumbPath) }

private fun seasonsCategory(seasons: List<SeasonSummary>): AlbumCategory =
    AlbumCategory(TYPE_SEASONS, seasons.size, seasons.firstOrNull()?.coverThumbPath, isSmart = true)

private fun smartCategoriesOf(smart: List<SmartAlbum>): List<AlbumCategory> =
    smart.map { s ->
        AlbumCategory(
            type = when (s.kind) {
                SmartAlbumKind.VIDEOS -> TYPE_VIDEOS
                SmartAlbumKind.ON_THIS_DAY -> TYPE_ON_THIS_DAY
            },
            itemCount = s.photoCount,
            coverThumbPath = s.coverThumbPath,
            isSmart = true,
        )
    }

/** Count line under a root category: albums for catalog categories, media for smart ones. */
@Composable
private fun categoryCountLabel(category: AlbumCategory): String = when (category.type) {
    TYPE_VIDEOS -> stringResource(R.string.smart_videos_count, category.itemCount)
    TYPE_ON_THIS_DAY -> stringResource(R.string.album_item_count, category.itemCount)
    TYPE_SEASONS -> pluralStringResource(R.plurals.season_count, category.itemCount, category.itemCount)
    else -> pluralStringResource(R.plurals.album_count, category.itemCount, category.itemCount)
}

@Composable
private fun categoryTitle(type: String): String = when (type) {
    "place" -> stringResource(R.string.album_type_place)
    "camera" -> stringResource(R.string.album_type_camera)
    "year" -> stringResource(R.string.album_type_year)
    "trip" -> stringResource(R.string.album_type_trip)
    TYPE_VIDEOS -> stringResource(R.string.smart_videos_title)
    TYPE_ON_THIS_DAY -> stringResource(R.string.smart_on_this_day_title)
    TYPE_SEASONS -> stringResource(R.string.smart_seasons_title)
    else -> type.replaceFirstChar { it.uppercase() }
}

private fun categoryIcon(type: String): Int = when (type) {
    "place" -> R.drawable.ic_cat_places
    "camera" -> R.drawable.ic_cat_cameras
    "year" -> R.drawable.ic_cat_years
    "trip" -> R.drawable.ic_cat_trips
    TYPE_VIDEOS -> R.drawable.ic_cat_videos
    TYPE_ON_THIS_DAY -> R.drawable.ic_cat_on_this_day
    TYPE_SEASONS -> R.drawable.ic_cat_seasons
    else -> R.drawable.ic_nav_albums
}

/** Non-composable label for one breadcrumb segment (built from a plain [Context]). */
private fun crumbLabel(level: AlbumsLevel, context: Context): String = when (level) {
    AlbumsLevel.Root -> context.getString(R.string.tab_albums)
    is AlbumsLevel.Category -> categoryTitleString(level.type, context)
    is AlbumsLevel.YearMonths -> level.yearName
    is AlbumsLevel.AlbumPhotos -> level.title
    is AlbumsLevel.MonthPhotos -> DateFormats.monthName(level.month)
    AlbumsLevel.Videos -> context.getString(R.string.smart_videos_title)
    AlbumsLevel.OnThisDay -> context.getString(R.string.smart_on_this_day_title)
    AlbumsLevel.Seasons -> context.getString(R.string.smart_seasons_title)
    is AlbumsLevel.SeasonPhotos -> seasonNameString(level.season, context)
}

private fun categoryTitleString(type: String, context: Context): String = when (type) {
    "place" -> context.getString(R.string.album_type_place)
    "camera" -> context.getString(R.string.album_type_camera)
    "year" -> context.getString(R.string.album_type_year)
    "trip" -> context.getString(R.string.album_type_trip)
    TYPE_VIDEOS -> context.getString(R.string.smart_videos_title)
    TYPE_ON_THIS_DAY -> context.getString(R.string.smart_on_this_day_title)
    TYPE_SEASONS -> context.getString(R.string.smart_seasons_title)
    else -> type.replaceFirstChar { it.uppercase() }
}

/** Localized season name for a [Seasons] code, built from a plain [Context]. */
private fun seasonNameString(season: Int, context: Context): String = context.getString(
    when (season) {
        Seasons.WINTER -> R.string.season_winter
        Seasons.SPRING -> R.string.season_spring
        Seasons.SUMMER -> R.string.season_summer
        else -> R.string.season_autumn
    }
)

private fun seasonIcon(season: Int): Int = when (season) {
    Seasons.WINTER -> R.drawable.ic_season_winter
    Seasons.SPRING -> R.drawable.ic_season_spring
    Seasons.SUMMER -> R.drawable.ic_season_summer
    else -> R.drawable.ic_season_autumn
}
