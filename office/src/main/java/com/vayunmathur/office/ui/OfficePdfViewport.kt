package com.vayunmathur.office.ui


import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectTapGestures

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding


import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.input.pointer.pointerInput

import androidx.compose.ui.unit.dp


import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val RENDER_SETTLE_DELAY_MS = 180L
private const val INDICATOR_IDLE_DELAY_MS = 1_500L

@Composable
internal fun OfficePdfViewport(
    uriKey: String,
    pageCount: Int,
    cache: PdfPageBitmapCache,
    matches: List<PdfTextMatch>,
    currentMatch: PdfTextMatch?,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(),
) {
    var zoom by remember(uriKey) { mutableFloatStateOf(MIN_ZOOM) }
    var renderZoom by remember(uriKey) { mutableFloatStateOf(MIN_ZOOM) }
    var offsetX by remember(uriKey) { mutableFloatStateOf(0f) }
    var gestureActive by remember(uriKey) { mutableStateOf(false) }
    var scrollbarDragging by remember(uriKey) { mutableStateOf(false) }
    var indicatorsVisible by remember(uriKey) { mutableStateOf(false) }
    val gestureScope = rememberCoroutineScope()

    LaunchedEffect(zoom) {
        delay(RENDER_SETTLE_DELAY_MS)
        renderZoom = zoom
    }
    LaunchedEffect(uriKey) {
        listState.scrollToItem(0)
        zoom = MIN_ZOOM
        renderZoom = MIN_ZOOM
        offsetX = 0f
    }
    LaunchedEffect(listState.isScrollInProgress, gestureActive, scrollbarDragging) {
        if (listState.isScrollInProgress || gestureActive || scrollbarDragging) {
            indicatorsVisible = true
        } else if (indicatorsVisible) {
            delay(INDICATOR_IDLE_DELAY_MS)
            indicatorsVisible = false
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().padding(contentPadding).clipToBounds(),
        contentAlignment = Alignment.TopStart,
    ) {
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val scrollbarWidthPx = with(density) { 32.dp.toPx() }
        val scrollbarRightInset = with(density) {
            8.dp + WindowInsets.systemGestures.getRight(this, layoutDirection).toDp()
        }
        val scrollbarRightInsetPx = with(density) { scrollbarRightInset.toPx() }
        val scrollbarStartX = viewportWidthPx - scrollbarRightInsetPx - scrollbarWidthPx

        val flingBehavior = ScrollableDefaults.flingBehavior()
        val transformGestureModifier = Modifier
            .pointerInput(listState, viewportWidthPx, scrollbarStartX, flingBehavior) {
                awaitEachGesture {
                    try {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        val startedOnScrollbar = down.position.x >= scrollbarStartX
                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)
                        var hadVerticalDrag = false
                        var previousCentroid = down.position
                        var previousSpan = 0f
                        var previousPointerCount = 1
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pointers = event.changes.filter { it.pressed }
                            if (pointers.isEmpty()) {
                                event.changes.firstOrNull()?.let {
                                    velocityTracker.addPosition(it.uptimeMillis, it.position)
                                }
                                break
                            }

                            val centroid = pointers.fold(Offset.Zero) { sum, pointer -> sum + pointer.position } /
                                pointers.size.toFloat()
                            val span = if (pointers.size > 1) {
                                pointers.sumOf { (it.position - centroid).getDistance().toDouble() }
                                    .toFloat() / pointers.size
                            } else {
                                0f
                            }
                            val ownsGesture = !startedOnScrollbar && (pointers.size > 1 || zoom > MIN_ZOOM)
                            if (ownsGesture) {
                                gestureActive = true
                                velocityTracker.addPosition(event.changes.first().uptimeMillis, centroid)
                                event.changes.forEach { if (it.pressed) it.consume() }
                                if (pointers.size == previousPointerCount) {
                                    val oldZoom = zoom
                                    val zoomChange = if (pointers.size > 1 && previousSpan > 0f) {
                                        span / previousSpan
                                    } else {
                                        1f
                                    }
                                    val nextZoom = (oldZoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                                    val factor = nextZoom / oldZoom
                                    val panX = centroid.x - previousCentroid.x
                                    hadVerticalDrag = hadVerticalDrag || centroid.y != previousCentroid.y
                                    offsetX = clampOffsetX(
                                        offsetX * factor + previousCentroid.x * (1f - factor) + panX,
                                        viewportWidthPx,
                                        nextZoom,
                                    )
                                    val verticalDelta =
                                        previousCentroid.y / oldZoom - centroid.y / nextZoom
                                    if (verticalDelta != 0f) listState.dispatchRawDelta(verticalDelta)
                                    zoom = nextZoom
                                }
                            }
                            previousCentroid = centroid
                            previousSpan = span
                            previousPointerCount = pointers.size
                        }
                        if (!startedOnScrollbar && hadVerticalDrag && zoom > MIN_ZOOM) {
                            val initialVelocity = -velocityTracker.calculateVelocity().y / zoom
                            if (kotlin.math.abs(initialVelocity) > 100f) {
                                gestureScope.launch {
                                    listState.scroll {
                                        with(flingBehavior) { performFling(initialVelocity) }
                                    }
                                }
                            }
                        }
                    } finally {
                        gestureActive = false
                    }
                }
            }
            .pointerInput(listState, viewportWidthPx, scrollbarStartX) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (tap.x >= scrollbarStartX) return@detectTapGestures
                    val oldZoom = zoom
                    val nextZoom = if (oldZoom > MIN_ZOOM) MIN_ZOOM else DOUBLE_TAP_ZOOM
                    val factor = nextZoom / oldZoom
                    offsetX = clampOffsetX(
                        tap.x + (offsetX - tap.x) * factor,
                        viewportWidthPx,
                        nextZoom,
                    )
                    listState.dispatchRawDelta(tap.y / oldZoom - tap.y / nextZoom)
                    zoom = nextZoom
                })
            }

        val scaledBottomPadding = 12.dp + maxHeight * (1f - 1f / zoom)
        Box(Modifier.fillMaxSize().then(transformGestureModifier)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = zoom
                    scaleY = zoom
                    translationX = offsetX
                },
                state = listState,
                contentPadding = PaddingValues(top = 12.dp, bottom = scaledBottomPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pageCount) { pageIndex ->
                    PdfPage(
                        cache = cache,
                        pageIndex = pageIndex,
                        renderZoom = renderZoom,
                        matches = matches.filter { it.pageIndex == pageIndex },
                        currentMatch = currentMatch,
                    )
                }
            }
            OfficePdfScrollIndicators(
                listState = listState,
                pageCount = pageCount,
                visible = indicatorsVisible,
                onScrollbarDragging = { scrollbarDragging = it },
                modifier = Modifier.fillMaxSize(),
                rightInset = scrollbarRightInset,
            )
        }
    }
}

private fun clampOffsetX(offset: Float, viewportWidth: Float, zoom: Float): Float =
    if (zoom <= MIN_ZOOM) 0f else offset.coerceIn(viewportWidth * (1f - zoom), 0f)
