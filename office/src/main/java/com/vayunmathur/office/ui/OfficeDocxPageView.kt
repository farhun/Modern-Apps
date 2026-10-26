package com.vayunmathur.office.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues


import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.office.OfficeLightTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val LETTER_WIDTH_PX = 816f
private const val LETTER_MARGIN_PX = 96f
private val PAGE_GUTTER = 12.dp

@Composable
internal fun OfficeDocxPageView(
    document: OdfDocument.TextDocument,
    listState: LazyListState,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val pageSetup = document.pageSetup
    val pageWidth = pageSetup?.widthPx?.takeIf { it > 0f } ?: LETTER_WIDTH_PX
    val leftMargin = pageSetup?.marginLeftPx ?: LETTER_MARGIN_PX
    val rightMargin = pageSetup?.marginRightPx ?: LETTER_MARGIN_PX
    val topMargin = pageSetup?.marginTopPx ?: LETTER_MARGIN_PX
    val bottomMargin = pageSetup?.marginBottomPx ?: LETTER_MARGIN_PX
    val zoomState = rememberOfficeZoomPanState(document.title)
    val flingBehavior = ScrollableDefaults.flingBehavior()

    BoxWithConstraints(
        modifier.background(Color(0xFFE2E2E2)).clipToBounds(),
    ) {
        val pageViewportWidth = (maxWidth - PAGE_GUTTER * 2).coerceAtLeast(1.dp)
        val pageViewportHeight = (maxHeight - PAGE_GUTTER * 2).coerceAtLeast(1.dp)
        val fitScale = with(LocalDensity.current) {
            (pageViewportWidth.toPx() / pageWidth.dp.toPx()).coerceIn(0.01f, 1f)
        }
        val logicalContentHeight = pageViewportHeight / fitScale
        val contentWidthPx = with(LocalDensity.current) { pageWidth.dp.roundToPx() }
        val contentHeightPx = with(LocalDensity.current) { logicalContentHeight.roundToPx() }
        OfficeLightTheme {
            Box(Modifier.fillMaxSize().padding(PAGE_GUTTER)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.White,
                ) {}
                OfficeZoomPanContainer(
                    state = zoomState,
                    modifier = Modifier.fillMaxSize(),
                    minZoom = 0.5f,
                    maxZoom = 5f,
                    baseScale = fitScale,
                    contentWidth = pageWidth.dp,
                    contentHeight = logicalContentHeight,
                    panAtBaseZoom = false,
                    onVerticalPan = { delta -> listState.dispatchRawDelta(delta) },
                    onVerticalFling = { velocity ->
                        if (kotlin.math.abs(velocity) > 100f) {
                            scope.launch {
                                listState.scroll {
                                    with(flingBehavior) { performFling(velocity) }
                                }
                            }
                        }
                    },
                ) { transformModifier ->
                    Layout(
                        modifier = Modifier.fillMaxSize(),
                        content = {
                            Box(transformModifier) {
                                TextDocumentView(
                                    doc = document,
                                    listState = listState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(
                                        start = leftMargin.coerceAtLeast(0f).dp,
                                        top = topMargin.coerceAtLeast(0f).dp,
                                        end = rightMargin.coerceAtLeast(0f).dp,
                                        bottom = bottomMargin.coerceAtLeast(0f).dp,
                                    ),
                                    readOnly = true,
                                    showPageBreaks = true,
                                )
                            }
                        },
                    ) { measurables, constraints ->
                        val content = measurables.single().measure(
                            Constraints.fixed(contentWidthPx, contentHeightPx),
                        )
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            content.place(0, 0)
                        }
                    }
                }
            }
        }
    }
}
