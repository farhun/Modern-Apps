package com.vayunmathur.office.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class PdfScrollMetrics(val progress: Float, val thumbFraction: Float, val visiblePage: Int)

@Composable
internal fun OfficePdfScrollIndicators(
    listState: LazyListState,
    pageCount: Int,
    visible: Boolean,
    onScrollbarDragging: (Boolean) -> Unit,
    rightInset: Dp,
    modifier: Modifier = Modifier,
) {
    val metrics by remember(listState, pageCount) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val items = layout.visibleItemsInfo
            val count = pageCount.coerceAtLeast(1)
            val first = items.minByOrNull { it.index }
            val firstFraction = first?.takeIf { it.size > 0 }?.let {
                listState.firstVisibleItemScrollOffset.toFloat() / it.size
            } ?: 0f
            val progress = ((listState.firstVisibleItemIndex + firstFraction) / count).coerceIn(0f, 1f)
            val thumbFraction = (items.size.toFloat() / count).coerceIn(0.025f, 1f)
            val visiblePage = items.maxByOrNull { item ->
                val visibleStart = maxOf(item.offset, layout.viewportStartOffset)
                val visibleEnd = minOf(item.offset + item.size, layout.viewportEndOffset)
                (visibleEnd - visibleStart).coerceAtLeast(0).toFloat() / item.size.coerceAtLeast(1)
            }?.index?.coerceIn(0, count - 1) ?: 0
            PdfScrollMetrics(progress, thumbFraction, visiblePage)
        }
    }

    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
    val scope = rememberCoroutineScope()
    val latestProgress by rememberUpdatedState(metrics.progress)
    val latestThumbFraction by rememberUpdatedState(metrics.thumbFraction)
    var scrollJob by remember { mutableStateOf<Job?>(null) }

    Box(modifier) {
        FadeVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxSize(),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val trackWidth = 2.dp.toPx()
                val thumbWidth = 9.dp.toPx()
                val rightInsetPx = rightInset.toPx()
                val thumbHeight = (size.height * metrics.thumbFraction)
                    .coerceAtLeast(24.dp.toPx())
                    .coerceAtMost(size.height)
                val thumbTop = (size.height - thumbHeight) * metrics.progress
                drawRoundRect(
                    color = trackColor,
                    topLeft = Offset(size.width - rightInsetPx - trackWidth, 0f),
                    size = Size(trackWidth, size.height),
                    cornerRadius = CornerRadius(trackWidth, trackWidth),
                )
                drawRoundRect(
                    color = thumbColor,
                    topLeft = Offset(size.width - rightInsetPx - thumbWidth, thumbTop),
                    size = Size(thumbWidth, thumbHeight),
                    cornerRadius = CornerRadius(thumbWidth, thumbWidth),
                )
            }
        }
        FadeVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.CenterEnd)
                .offset(x = -rightInset)
                .fillMaxHeight()
                .width(32.dp),
        ) {
            Spacer(
                Modifier.fillMaxSize()
                    .pointerInput(listState, pageCount) {
                        var dragProgress = latestProgress
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragProgress = latestProgress
                                onScrollbarDragging(true)
                            },
                            onDragEnd = { onScrollbarDragging(false) },
                            onDragCancel = { onScrollbarDragging(false) },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                val minThumb = 24.dp.toPx()
                                val thumbHeight = (size.height * latestThumbFraction)
                                    .coerceAtLeast(minThumb)
                                    .coerceAtMost(size.height.toFloat())
                                val travel = (size.height - thumbHeight).coerceAtLeast(1f)
                                dragProgress = (dragProgress + dragAmount / travel).coerceIn(0f, 1f)
                                val targetPage = (dragProgress * (pageCount - 1).coerceAtLeast(0)).roundToInt()
                                scrollJob?.cancel()
                                scrollJob = scope.launch { listState.scrollToItem(targetPage) }
                            },
                        )
                    },
            )
        }
        FadeVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(
                    "${metrics.visiblePage + 1} / ${pageCount.coerceAtLeast(1)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
