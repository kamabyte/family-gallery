package com.familygallery.tv.ui.viewer

import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.paging.compose.LazyPagingItems
import coil3.PlatformContext
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import com.familygallery.tv.GalleryApplication
import com.familygallery.tv.R
import com.familygallery.tv.data.PhotoEntity
import com.familygallery.tv.smb.SmbDataSource
import com.familygallery.tv.smb.SmbImage
import com.familygallery.tv.ui.DateFormats
import com.familygallery.tv.ui.LocalCatalogCacheVersion
import com.familygallery.tv.ui.LocalFormFactor
import com.familygallery.tv.ui.share.ShareStatus
import com.familygallery.tv.ui.share.rememberShareController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val SLIDESHOW_INTERVAL_MS = 4000L
private const val PAGE_ANIMATION_MS = 150

private enum class VideoState { IDLE, BUFFERING, PLAYING, PAUSED, ERROR }

/**
 * Fullscreen media viewer. Photos use the pre-generated 1600px preview; videos stream the
 * indexer's bounded H.264/AAC proxy through a seekable SMB Media3 source.
 *
 * It is driven differently per form factor, over the same pager:
 *
 *  * **TV** — left/right on the D-pad move one item. Rapid presses update a single navigation
 *    target, so animations are cancelled and retargeted rather than queuing up behind the remote.
 *    OK plays/pauses a video, Play/Pause starts a slideshow, and the info overlay is permanent.
 *  * **Touch** — swipe horizontally to page, drag down to dismiss, pinch or double-tap to zoom
 *    and drag to pan, tap to hide the chrome (and the system bars with it) so the photo is seen
 *    whole. Explicit close and slideshow buttons stand in for the keys a remote has and a phone
 *    does not. All four drags share one gesture detector and are disambiguated once per gesture
 *    — see [ViewerDrag].
 */
@Composable
@OptIn(markerClass = [UnstableApi::class])
fun PhotoViewer(
    items: LazyPagingItems<PhotoEntity>,
    initialIndex: Int,
    onClose: (lastIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val formFactor = LocalFormFactor.current
    val isTv = formFactor.isTv
    val safeInitialIndex = initialIndex.coerceIn(0, (items.itemCount - 1).coerceAtLeast(0))
    val pagerState = rememberPagerState(
        initialPage = safeInitialIndex,
        pageCount = { items.itemCount },
    )
    val focusRequester = remember { FocusRequester() }
    var navigationTarget by remember { mutableIntStateOf(safeInitialIndex) }
    var slideshow by remember { mutableStateOf(false) }
    var activeVideoId by remember { mutableStateOf<Long?>(null) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var videoState by remember { mutableStateOf(VideoState.IDLE) }

    val context = LocalContext.current
    val hostView = LocalView.current
    val smb = remember(context) {
        (context.applicationContext as GalleryApplication).container.smbClient
    }
    val share = rememberShareController()
    val shareChooserTitle = stringResource(R.string.share_chooser_title)

    BackHandler { onClose(pagerState.currentPage) }

    fun go(delta: Int) {
        val last = (items.itemCount - 1).coerceAtLeast(0)
        val target = (navigationTarget + delta).coerceIn(0, last)
        // Reveal the current poster before the pager animates; a platform video surface cannot
        // participate in Compose's page transform and would otherwise cover the transition.
        if (target != navigationTarget) activeVideoId = null
        navigationTarget = target
        slideshow = false
    }

    fun toggleCurrentVideo() {
        val current = items.peek(pagerState.currentPage) ?: return
        if (!current.isVideo || current.videoPath == null) return
        val currentPlayer = player
        when {
            activeVideoId != current.id || currentPlayer == null -> activeVideoId = current.id
            currentPlayer.isPlaying -> currentPlayer.pause()
            currentPlayer.playerError != null -> {
                videoState = VideoState.BUFFERING
                currentPlayer.prepare()
                currentPlayer.play()
            }
            currentPlayer.playbackState == Player.STATE_ENDED -> {
                currentPlayer.seekTo(0)
                currentPlayer.play()
            }
            else -> currentPlayer.play()
        }
    }

    val keepScreenOn = slideshow || videoState == VideoState.BUFFERING ||
        videoState == VideoState.PLAYING
    DisposableEffect(hostView, keepScreenOn) {
        val previous = hostView.keepScreenOn
        hostView.keepScreenOn = keepScreenOn
        onDispose { hostView.keepScreenOn = previous }
    }

    // A changing key cancels the previous animation immediately. This keeps held-D-pad input
    // responsive instead of ignoring presses or building a long animation queue.
    LaunchedEffect(navigationTarget) {
        if (navigationTarget != pagerState.currentPage) {
            pagerState.animateScrollToPage(
                page = navigationTarget,
                animationSpec = tween(PAGE_ANIMATION_MS, easing = LinearOutSlowInEasing),
            )
        }
    }

    // Video must never continue audibly after the user pages to another item.
    LaunchedEffect(pagerState.currentPage) {
        val currentId = items.peek(pagerState.currentPage)?.id
        if (activeVideoId != null && activeVideoId != currentId) activeVideoId = null
    }

    LaunchedEffect(slideshow) {
        if (!slideshow) return@LaunchedEffect
        while (slideshow) {
            delay(SLIDESHOW_INTERVAL_MS)
            val next = navigationTarget + 1
            if (next >= items.itemCount) {
                slideshow = false
            } else {
                activeVideoId = null
                navigationTarget = next
            }
        }
    }

    val activeVideo = items.peek(pagerState.currentPage)
        ?.takeIf { it.id == activeVideoId && it.isVideo && it.videoPath != null }
    DisposableEffect(activeVideo?.id) {
        if (activeVideo == null) {
            player = null
            videoState = VideoState.IDLE
            onDispose { }
        } else {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */ 5_000,
                    /* maxBufferMs = */ 20_000,
                    /* bufferForPlaybackMs = */ 750,
                    /* bufferForPlaybackAfterRebufferMs = */ 1_500,
                )
                .build()
            val exoPlayer = ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .build()
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    videoState = when (playbackState) {
                        Player.STATE_BUFFERING -> VideoState.BUFFERING
                        Player.STATE_READY -> if (exoPlayer.isPlaying) {
                            VideoState.PLAYING
                        } else {
                            VideoState.PAUSED
                        }
                        Player.STATE_ENDED -> VideoState.PAUSED
                        else -> videoState
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (exoPlayer.playbackState == Player.STATE_READY) {
                        videoState = if (isPlaying) VideoState.PLAYING else VideoState.PAUSED
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    videoState = VideoState.ERROR
                }
            }
            exoPlayer.addListener(listener)
            val source = ProgressiveMediaSource.Factory(SmbDataSource.Factory(smb))
                .createMediaSource(MediaItem.fromUri(SmbDataSource.uri(activeVideo.videoPath!!)))
            exoPlayer.setMediaSource(source)
            videoState = VideoState.BUFFERING
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            player = exoPlayer

            onDispose {
                exoPlayer.removeListener(listener)
                exoPlayer.release()
                if (player === exoPlayer) player = null
            }
        }
    }

    // --- Touch state ---------------------------------------------------------------------
    // Zoom is held for the CURRENT page only. Only the current page can be manipulated, so one
    // set of values is enough, and resetting on a page change is a single effect rather than
    // per-page bookkeeping the pager would recycle unpredictably.
    var scale by remember { mutableFloatStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val zoomed = scale > 1f
    val platformContext = LocalPlatformContext.current
    // Latched, not derived directly from [scale]: pinching oscillates around the threshold, and a
    // plain comparison would add and remove the original layer repeatedly, cancelling its SMB
    // fetch each time so it never finished. Once past the threshold the layer stays until the
    // photo is back at fit (below) or the page changes.
    var deepZoom by remember { mutableStateOf(false) }
    if (scale > DEEP_ZOOM_THRESHOLD && !deepZoom) deepZoom = true
    // Chrome (info overlay, close, slideshow) hides on tap so a photo can be seen whole. It is
    // always visible on TV, where there is no tap and the overlay is the only status readout.
    var chromeVisible by remember { mutableStateOf(true) }
    val showChrome = isTv || chromeVisible

    /**
     * Zoom is disabled while a video surface is on screen.
     *
     * The PlayerView is a sibling of the pager, not a child of the zoomed page: it has to sit
     * above every page so it is not clipped or transformed by the pager's own translation. So the
     * zoom transform reaches only the poster images *underneath* it — pinching a playing video
     * visibly magnified the still poster around a fixed video rectangle.
     *
     * Transforming the surface instead is not a fix either: PlayerView renders into a SurfaceView,
     * which the system composites in its own window, so a Compose `graphicsLayer` scale does not
     * apply to the video at all. Making it work would mean switching to a TextureView, which costs
     * an extra offscreen buffer and more power on exactly the weak hardware this app targets — a
     * poor trade for magnifying a 1080p proxy. Zooming the *poster* before playback starts is
     * still allowed, since that is a plain image with nothing on top of it.
     */
    val videoSurfaceActive = player != null

    // Swipe-down-to-dismiss. Held as a plain float rather than an Animatable because the drag has
    // to track the finger synchronously from inside the (restricted, non-suspending) pointer
    // scope; only the settle-back is animated.
    var dismissOffset by remember { mutableFloatStateOf(0f) }
    val dismissScope = rememberCoroutineScope()
    val dismissProgress = containerSize.height
        .takeIf { it > 0 }
        ?.let { (abs(dismissOffset) / it).coerceIn(0f, 1f) }
        ?: 0f
    val dismissing = dismissOffset != 0f

    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        panOffset = Offset.Zero
        // Drop the previous photo's original so its (large) bitmap is not held while browsing on.
        deepZoom = false
    }

    // Returning to fit releases the original too — otherwise a single deep zoom would keep a
    // ~25 MB bitmap alive for as long as the viewer stayed open.
    LaunchedEffect(zoomed) {
        if (!zoomed) deepZoom = false
    }

    // Starting playback while zoomed would leave the poster magnified behind the video. Snap back
    // to fit the moment the surface appears, so the two always agree.
    LaunchedEffect(videoSurfaceActive) {
        if (videoSurfaceActive) {
            scale = 1f
            panOffset = Offset.Zero
        }
    }

    // Full-bleed, immersive photos on a phone: the viewer's dialog gets its own window, so the
    // Activity's edge-to-edge setup does not reach it and has to be repeated here. Bars follow
    // the chrome so a tap reveals both together.
    ImmersiveDialogWindow(enabled = formFactor.isTouch, barsVisible = chromeVisible)

    Box(
        modifier = modifier
            .fillMaxSize()
            // Fading rather than a flat black backdrop is what makes the dismiss legible: the
            // grid the photo came from shows through as it is dragged away. The dialog window is
            // itself transparent, so this alpha is the only thing hiding what is behind.
            .background(Color.Black.copy(alpha = 1f - dismissProgress * DISMISS_FADE))
            .onSizeChanged { containerSize = it }
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val current = items.peek(pagerState.currentPage)
                when (event.key) {
                    Key.DirectionRight -> { go(1); true }
                    Key.DirectionLeft -> { go(-1); true }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        if (current?.isVideo == true) toggleCurrentVideo()
                        else slideshow = !slideshow
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (current?.isVideo == true) toggleCurrentVideo()
                        true
                    }
                    else -> false
                }
            }
            .then(
                if (isTv) {
                    Modifier
                } else {
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { chromeVisible = !chromeVisible },
                                onDoubleTap = {
                                    // Snap between fit and 2.5x rather than stepping, so a
                                    // double tap is a single decisive gesture either way.
                                    if (videoSurfaceActive) {
                                        // Nothing to zoom: see [videoSurfaceActive].
                                    } else if (scale > 1f) {
                                        scale = 1f
                                        panOffset = Offset.Zero
                                    } else {
                                        scale = DOUBLE_TAP_SCALE
                                    }
                                },
                            )
                        }
                        .pointerInput(containerSize, videoSurfaceActive) {
                            val slop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                // A single drag could mean three different things, and the choice
                                // has to be made once and then held for the whole gesture —
                                // re-deciding mid-drag is what makes a viewer feel like it is
                                // fighting the finger.
                                var mode = ViewerDrag.Undecided
                                var totalX = 0f
                                var totalY = 0f
                                do {
                                    val event = awaitPointerEvent()
                                    val pan = event.calculatePan()

                                    if (mode == ViewerDrag.Undecided) {
                                        mode = when {
                                            // Two fingers, or already magnified: a transform.
                                            // Never over a video — see [videoSurfaceActive].
                                            !videoSurfaceActive &&
                                                (event.changes.size > 1 || scale > 1f) ->
                                                ViewerDrag.Transform
                                            // The pager already claimed it: a page swipe.
                                            event.changes.any { it.isConsumed } -> ViewerDrag.Pager
                                            else -> {
                                                totalX += pan.x
                                                totalY += pan.y
                                                when {
                                                    // Clearly vertical: dismiss. The bias makes a
                                                    // sloppy diagonal resolve to a page swipe,
                                                    // which is the far more frequent intent.
                                                    abs(totalY) > slop &&
                                                        abs(totalY) > abs(totalX) * DISMISS_BIAS ->
                                                        ViewerDrag.Dismiss
                                                    abs(totalX) > slop -> ViewerDrag.Pager
                                                    else -> ViewerDrag.Undecided
                                                }
                                            }
                                        }
                                        // A surface cannot be translated with the rest of the
                                        // content (same reason zoom is blocked), so releasing the
                                        // player hands the drag a poster it *can* move. Stopping
                                        // playback is what closing would do anyway.
                                        if (mode == ViewerDrag.Dismiss && videoSurfaceActive) {
                                            activeVideoId = null
                                        }
                                    }

                                    when (mode) {
                                        ViewerDrag.Transform -> {
                                            scale = (scale * event.calculateZoom())
                                                .coerceIn(1f, MAX_SCALE)
                                            panOffset = clampPan(
                                                panOffset + pan,
                                                scale,
                                                containerSize,
                                            )
                                            event.changes.forEach { it.consume() }
                                        }
                                        ViewerDrag.Dismiss -> {
                                            dismissOffset += pan.y
                                            event.changes.forEach { it.consume() }
                                        }
                                        // Left unconsumed so the pager keeps the gesture.
                                        ViewerDrag.Pager, ViewerDrag.Undecided -> Unit
                                    }
                                } while (event.changes.any { it.pressed })

                                when (mode) {
                                    // Pinching back below fit snaps cleanly to centred+unzoomed.
                                    ViewerDrag.Transform -> if (scale <= 1f) {
                                        scale = 1f
                                        panOffset = Offset.Zero
                                    }
                                    ViewerDrag.Dismiss -> {
                                        val height = containerSize.height.takeIf { it > 0 } ?: 1
                                        if (abs(dismissOffset) > height * DISMISS_TRIGGER) {
                                            onClose(pagerState.currentPage)
                                        } else {
                                            // Not far enough: spring back rather than snap, so an
                                            // abandoned drag reads as "that did nothing".
                                            val from = dismissOffset
                                            dismissScope.launch {
                                                animate(from, 0f) { value, _ ->
                                                    dismissOffset = value
                                                }
                                            }
                                        }
                                    }
                                    ViewerDrag.Pager, ViewerDrag.Undecided -> Unit
                                }
                            }
                        }
                },
            ),
    ) {
        // The media layer — pager, video surface and playback indicators — moves as one under the
        // dismiss drag. The chrome below stays put and simply hides, because a close button
        // sliding off with the photo is not something the user can still aim at.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = dismissOffset
                    // Shrinking slightly as it travels reads as "the photo is going back where it
                    // came from" rather than "the photo fell off the screen".
                    val shrink = 1f - dismissProgress * DISMISS_SHRINK
                    scaleX = shrink
                    scaleY = shrink
                },
        ) {
        HorizontalPager(
            state = pagerState,
            // TV drives the pager from the D-pad through [go], so a drag must not fight it. On
            // touch, swiping is the primary navigation — except while zoomed in, when a drag
            // means "pan this photo" instead.
            userScrollEnabled = !isTv && !zoomed,
        ) { page ->
            val media = items[page]
            if (media != null) {
                val cacheVersion = LocalCatalogCacheVersion.current
                Box(
                    Modifier
                        .fillMaxSize()
                        // Only the current page carries the zoom; neighbours the pager keeps
                        // composed must stay at rest so they slide in unscaled.
                        .then(
                            if (page == pagerState.currentPage && zoomed) {
                                Modifier.graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = panOffset.x
                                    translationY = panOffset.y
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    // Three layers of increasing resolution, each drawing nothing until it has
                    // decoded, so the one below stays visible. Nothing shifts between them: all
                    // three are the same photo under the same Fit scaling.

                    // 1. The 320px grid thumbnail is already in Coil's memory cache (the user just
                    // tapped it), so it paints on the first frame. Without it, opening a photo
                    // showed pure black until the preview arrived — which on phone Wi-Fi measured
                    // over 20 seconds against a busy SMB share, with no sign anything was going on.
                    AsyncImage(
                        model = SmbImage(media.thumbPath, cacheVersion),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 2. The indexer's 1600px preview, decoded at its baked size rather than at
                    // the layout size. Sizing it to the composable (Coil's default) throws the
                    // extra pixels away at decode time, so zooming magnified a screen-resolution
                    // bitmap and went soft immediately. TV keeps the default: it cannot zoom, and
                    // the smaller decode is deliberate headroom on weak boxes.
                    AsyncImage(
                        model = previewRequest(platformContext, media, cacheVersion, isTv),
                        contentDescription = media.filename,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 3. The untouched original off the share, fetched only once the user has
                    // zoomed past what the preview can actually resolve, and only for the page
                    // they are looking at. This is the layer that makes deep zoom show real
                    // detail instead of interpolation; it is deliberately not loaded up front,
                    // since it is megabytes over SMB per photo.
                    if (page == pagerState.currentPage && deepZoom && !media.isVideo) {
                        AsyncImage(
                            model = originalRequest(platformContext, media, cacheVersion),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.loading_photos),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFAAB2BD),
                    )
                }
            }
        }

        player?.let { currentPlayer ->
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        isFocusable = false
                        isFocusableInTouchMode = false
                        this.player = currentPlayer
                    }
                },
                update = { it.player = currentPlayer },
                modifier = Modifier.fillMaxSize(),
            )
        }

        items.peek(pagerState.currentPage)?.let { current ->
            // On touch the play affordances are the control: tapping them starts/pauses the
            // video, where a remote uses OK. Elsewhere a tap toggles the chrome instead.
            val videoTap: (() -> Unit)? =
                if (formFactor.isTouch && current.isVideo) ({ toggleCurrentVideo() }) else null

            if (current.isVideo && player == null) {
                VideoPlayHint(
                    available = current.videoPath != null,
                    onTap = if (current.videoPath != null) videoTap else null,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else if (videoState == VideoState.BUFFERING || videoState == VideoState.ERROR) {
                VideoStatus(videoState, Modifier.align(Alignment.Center))
            } else if (current.isVideo && videoState == VideoState.PAUSED) {
                // Clear "press OK to play" affordance while paused; nothing while playing.
                VideoPausedIndicator(onTap = videoTap, modifier = Modifier.align(Alignment.Center))
            }
        }
        } // end of the dismiss-translated media layer

        // Chrome sits outside that layer: it stays anchored to the screen and hides for the
        // duration of a dismiss drag, rather than sliding away with the photo.
        if (showChrome && !dismissing) {
            items.peek(pagerState.currentPage)?.let { current ->
                InfoOverlay(
                    photo = current,
                    position = pagerState.currentPage + 1,
                    total = items.itemCount,
                    slideshow = slideshow,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }

        // Touch has no dedicated Back or Play key, so the two actions a remote gets for free
        // need visible controls. Sharing joins them: it is a phone-only action (a TV has nowhere
        // to send a photo to), so it lives in the same touch-only row.
        if (formFactor.isTouch && showChrome && !dismissing) {
            ViewerTopControls(
                slideshow = slideshow,
                shareEnabled = !share.busy,
                onShare = {
                    items.peek(pagerState.currentPage)?.let {
                        share.share(listOf(it), shareChooserTitle)
                    }
                },
                onToggleSlideshow = { slideshow = !slideshow },
                onClose = { onClose(pagerState.currentPage) },
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }

        ShareStatus(
            state = share.state,
            onDismissError = share::dismissError,
            modifier = Modifier.align(Alignment.Center),
        )
    }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}

/**
 * What a one-or-two-finger drag in the viewer turned out to mean. Decided once, from the first
 * few pixels of movement, and then held until every finger lifts — a viewer that re-classifies
 * mid-drag feels like it is fighting you.
 */
private enum class ViewerDrag {
    /** Not yet past touch slop; still could become any of the others. */
    Undecided,

    /** Horizontal: the pager owns it, so events are deliberately left unconsumed. */
    Pager,

    /** Pinch or pan of a magnified photo. */
    Transform,

    /** Vertical: drag the photo away to close. */
    Dismiss,
}

/**
 * Largest allowed pinch magnification.
 *
 * Raised from 5x once deep zoom started loading the original: with only the 1600px preview behind
 * it, anything past ~1.5x on a 1080px-wide screen was pure interpolation, so a higher cap would
 * only have magnified blur. Against a 12–48 MP original there is genuine detail to reach, and 12x
 * is roughly where a modern phone photo runs out of pixels.
 */
private const val MAX_SCALE = 12f

/**
 * Zoom at which the full original is worth fetching.
 *
 * The preview is 1600px on its long edge and the screen is ~1080px wide, so the preview stops
 * resolving new detail at about 1.5x. Just past that is the first moment the original earns its
 * cost — several megabytes over SMB — and it is also late enough that ordinary browsing, tapping
 * through photos and swiping, never triggers it.
 */
private const val DEEP_ZOOM_THRESHOLD = 1.6f

/** Decode cap for the preview: its baked long edge, so no detail is discarded at decode time. */
private const val PREVIEW_DECODE_PX = 1600

/**
 * Decode cap for a deep-zoom original. A 48 MP source decoded whole would be ~190 MB of bitmap;
 * capping the long edge keeps one image near 25 MB while still carrying about 2.5x the detail of
 * the preview, which is what the extra zoom range needs.
 */
private const val ORIGINAL_DECODE_PX = 3072

/** The preview layer: decoded at full baked size on touch, at layout size on TV. */
private fun previewRequest(
    context: PlatformContext,
    media: PhotoEntity,
    cacheVersion: String,
    isTv: Boolean,
): Any = if (isTv) {
    SmbImage(media.previewPath, cacheVersion)
} else {
    ImageRequest.Builder(context)
        .data(SmbImage(media.previewPath, cacheVersion))
        .size(PREVIEW_DECODE_PX, PREVIEW_DECODE_PX)
        .precision(Precision.INEXACT)
        .crossfade(false)
        .build()
}

/**
 * The deep-zoom layer: the untouched source file off the share.
 *
 * `relativePath` is the original the indexer catalogued, not a derivative, so this is the only
 * path to detail beyond what was baked. HEIF sources decode natively from API 28 up, which covers
 * every phone we target; this layer is never requested on TV, where old boxes cannot decode HEIC
 * and there is no way to zoom anyway.
 */
private fun originalRequest(
    context: PlatformContext,
    media: PhotoEntity,
    cacheVersion: String,
): ImageRequest = ImageRequest.Builder(context)
    .data(SmbImage(media.relativePath, cacheVersion))
    .size(ORIGINAL_DECODE_PX, ORIGINAL_DECODE_PX)
    .precision(Precision.INEXACT)
    // Never write originals to the disk cache. That cache is sized for small derivatives and is
    // what keeps grid scrolling smooth; a handful of multi-megabyte originals would evict
    // thousands of thumbnails and make the whole library slow to browse afterwards. The memory
    // cache still covers zooming back into the same photo.
    .diskCachePolicy(CachePolicy.DISABLED)
    // No fade: this replaces an already-correct preview in place, and a fade would read as the
    // photo flickering while the user is holding a pinch.
    .crossfade(false)
    .build()

/**
 * How far down the photo must travel, as a fraction of the viewport height, before releasing
 * closes rather than springs back. A fifth of the screen is short enough to feel light but long
 * enough that a stray flick while scrolling never dismisses by accident.
 */
private const val DISMISS_TRIGGER = 0.2f

/**
 * How strongly a drag must favour the vertical axis to count as a dismiss rather than a page
 * swipe. Above 1, so an ambiguous diagonal resolves to paging — much the more common intent.
 */
private const val DISMISS_BIAS = 1.4f

/** Backdrop opacity given up at a full-height drag, revealing the grid underneath. */
private const val DISMISS_FADE = 0.9f

/** How much the photo shrinks at a full-height drag, so it reads as receding, not falling. */
private const val DISMISS_SHRINK = 0.35f
private const val DOUBLE_TAP_SCALE = 2.5f

/**
 * Keeps a panned photo from being dragged off-screen: at magnification [scale] the image can move
 * at most half of the extra size in each direction before its edge crosses the centre line.
 */
private fun clampPan(raw: Offset, scale: Float, size: IntSize): Offset {
    if (scale <= 1f || size == IntSize.Zero) return Offset.Zero
    val maxX = (size.width * (scale - 1f)) / 2f
    val maxY = (size.height * (scale - 1f)) / 2f
    return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
}

/**
 * Makes the viewer's dialog window full-bleed and drives the system bars from [barsVisible].
 *
 * A Compose `Dialog` gets its own window, so neither the Activity's `enableEdgeToEdge()` nor the
 * theme's cutout mode applies to it. Without this the fullscreen photo would sit inside a status
 * bar's worth of black on a phone.
 */
@Composable
private fun ImmersiveDialogWindow(enabled: Boolean, barsVisible: Boolean) {
    val view = LocalView.current
    if (!enabled) return
    val dialogWindow = (view.parent as? DialogWindowProvider)?.window
    DisposableEffect(dialogWindow, barsVisible) {
        val window = dialogWindow ?: return@DisposableEffect onDispose { }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        val controller = WindowInsetsControllerCompat(window, view)
        // A swipe brings the bars back transiently even while hidden, so the user is never
        // trapped without navigation.
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (barsVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
}

/**
 * Share + close + slideshow controls, shown only on touch (a remote has Back and Play/Pause keys,
 * and a TV has nowhere to share a photo to).
 */
@Composable
private fun ViewerTopControls(
    slideshow: Boolean,
    shareEnabled: Boolean,
    onShare: () -> Unit,
    onToggleSlideshow: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val slideshowLabel = stringResource(
        if (slideshow) R.string.stop_slideshow else R.string.slideshow,
    )
    val shareLabel = stringResource(R.string.share)
    val closeLabel = stringResource(R.string.close)
    Row(
        modifier = modifier
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        IconButton(
            onClick = onShare,
            enabled = shareEnabled,
            modifier = Modifier.semantics { contentDescription = shareLabel },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_share),
                contentDescription = null,
                tint = if (shareEnabled) Color.White else Color(0x66FFFFFF),
                modifier = Modifier.size(20.dp),
            )
        }
        // The glyphs match the house style used by the video badge and breadcrumbs, but a screen
        // reader would announce "black right-pointing triangle", so each button carries a real
        // label instead.
        IconButton(
            onClick = onToggleSlideshow,
            modifier = Modifier.semantics { contentDescription = slideshowLabel },
        ) {
            Text(
                text = if (slideshow) "❚❚" else "▶",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.semantics { contentDescription = closeLabel },
        ) {
            Text(text = "✕", style = MaterialTheme.typography.titleLarge, color = Color.White)
        }
    }
}

/** [onTap] is non-null only on touch, where this label *is* the play control. */
@Composable
private fun VideoPlayHint(
    available: Boolean,
    onTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Text(
        text = if (available) {
            stringResource(R.string.play_video)
        } else {
            stringResource(R.string.video_requires_reindex)
        },
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .background(Color(0xCC000000))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}

@Composable
private fun VideoStatus(state: VideoState, modifier: Modifier = Modifier) {
    val label = when (state) {
        VideoState.BUFFERING -> stringResource(R.string.buffering_video)
        VideoState.ERROR -> stringResource(R.string.video_playback_error)
        else -> return
    }
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        modifier = modifier
            .background(Color(0xCC000000), RoundedCornerShape(10.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
    )
}

/**
 * A fixed-size, centered play glyph shown only while a video is paused ("press OK to play" on a
 * remote). [onTap] is non-null only on touch, where tapping the glyph resumes playback.
 */
@Composable
private fun VideoPausedIndicator(onTap: (() -> Unit)?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(96.dp)
            .clip(CircleShape)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .background(Color(0x66000000)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(38.dp)) {
            val play = Path().apply {
                moveTo(size.width * 0.16f, 0f)
                lineTo(size.width * 0.16f, size.height)
                lineTo(size.width * 0.94f, size.height / 2f)
                close()
            }
            drawPath(play, Color.White)
        }
    }
}

@Composable
private fun InfoOverlay(
    photo: PhotoEntity,
    position: Int,
    total: Int,
    slideshow: Boolean,
    modifier: Modifier = Modifier,
) {
    val place = listOfNotNull(photo.placeCity, photo.placeCountry).joinToString(", ")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
            .padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = DateFormats.fullDateLabel(photo.captureDate),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        if (place.isNotEmpty()) {
            Text(text = place, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE6EAF0))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            photo.cameraModel?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFAAB2BD))
            }
            if (photo.isVideo) {
                // A plain, fixed-width duration — the play/pause state is shown by the centered
                // indicator, so this badge never changes width and never jumps the row.
                Badge(DateFormats.durationLabel(photo.durationMs))
            }
            if (slideshow) Badge(stringResource(R.string.slideshow))
            Text(
                text = "$position / $total",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAAB2BD),
            )
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
            .background(Color(0x55FFFFFF), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
