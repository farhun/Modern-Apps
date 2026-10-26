package com.vayunmathur.office.ui

import androidx.compose.foundation.gestures.ScrollableDefaults
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
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
    val zoomState = rememberOfficeZoomPanState(uriKey, MIN_ZOOM)
    val zoom = zoomState.zoom
    var renderZoom by remember(uriKey) { mutableFloatStateOf(MIN_ZOOM) }
    var scrollbarDragging by remember(uriKey) { mutableStateOf(false) }
    var indicatorsVisible by remember(uriKey) { mutableStateOf(false) }
    val gestureScope = rememberCoroutineScope()

    LaunchedEffect(zoom) {
        delay(RENDER_SETTLE_DELAY_MS)
        renderZoom = zoom
    }
    LaunchedEffect(uriKey) {
        listState.scrollToItem(0)
        zoomState.zoom = MIN_ZOOM
        zoomState.offsetX = 0f
        zoomState.offsetY = 0f
        renderZoom = MIN_ZOOM
    }
    LaunchedEffect(listState.isScrollInProgress, zoomState.gestureActive, scrollbarDragging) {
        if (listState.isScrollInProgress || zoomState.gestureActive || scrollbarDragging) {
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
        val scrollbarRightInset = with(density) {
            8.dp + WindowInsets.systemGestures.getRight(this, layoutDirection).toDp()
        }
        val flingBehavior = ScrollableDefaults.flingBehavior()
        val scaledBottomPadding = 12.dp + maxHeight * (1f - 1f / zoom)

        OfficeZoomPanContainer(
            state = zoomState,
            modifier = Modifier.fillMaxSize(),
            minZoom = MIN_ZOOM,
            maxZoom = MAX_ZOOM,
            doubleTapZoom = DOUBLE_TAP_ZOOM,
            contentWidth = maxWidth,
            contentHeight = maxHeight,
            gestureExclusionWidth = 32.dp,
            gestureExclusionRightInset = scrollbarRightInset,
            onVerticalPan = { delta -> listState.dispatchRawDelta(delta) },
            onVerticalFling = { velocity ->
                if (kotlin.math.abs(velocity) > 100f) {
                    gestureScope.launch {
                        listState.scroll {
                            with(flingBehavior) { performFling(velocity) }
                        }
                    }
                }
            },
        ) { transformModifier ->
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().then(transformModifier),
                    state = listState,
                    contentPadding = PaddingValues(top = 12.dp, bottom = scaledBottomPadding),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
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
}
