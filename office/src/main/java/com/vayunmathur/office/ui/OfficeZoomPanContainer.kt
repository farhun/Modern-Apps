package com.vayunmathur.office.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Stable
internal class OfficeZoomPanState(initialZoom: Float) {
    var zoom by mutableFloatStateOf(initialZoom)
    var offsetX by mutableFloatStateOf(0f)
    var offsetY by mutableFloatStateOf(0f)
    var gestureActive by mutableStateOf(false)
}

@Composable
internal fun rememberOfficeZoomPanState(key: Any, initialZoom: Float = 1f): OfficeZoomPanState =
    remember(key) { OfficeZoomPanState(initialZoom) }

/** Supplies a top-left-origin transform to content while retaining native scrolling at 1x. */
@Composable
internal fun OfficeZoomPanContainer(
    state: OfficeZoomPanState,
    modifier: Modifier = Modifier,
    minZoom: Float = 1f,
    maxZoom: Float = 5f,
    doubleTapZoom: Float = 2.5f,
    baseScale: Float = 1f,
    contentWidth: Dp? = null,
    contentHeight: Dp? = null,
    gestureExclusionWidth: Dp = 0.dp,
    gestureExclusionRightInset: Dp = 0.dp,
    panAtBaseZoom: Boolean = false,
    onVerticalPan: ((Float) -> Unit)? = null,
    onVerticalFling: ((Float) -> Unit)? = null,
    content: @Composable (transformModifier: Modifier) -> Unit,
) {
    val latestVerticalPan by rememberUpdatedState(onVerticalPan)
    val latestVerticalFling by rememberUpdatedState(onVerticalFling)

    BoxWithConstraints(modifier.clipToBounds()) {
        val density = LocalDensity.current
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val viewportHeightPx = with(density) { maxHeight.toPx() }
        val contentWidthPx = with(density) { (contentWidth ?: maxWidth).toPx() }
        val contentHeightPx = with(density) { (contentHeight ?: maxHeight).toPx() }
        val exclusionWidthPx = with(density) { gestureExclusionWidth.toPx() }
        val exclusionRightInsetPx = with(density) { gestureExclusionRightInset.toPx() }
        val excludedStartX = viewportWidthPx - exclusionRightInsetPx - exclusionWidthPx
        val scale = baseScale.coerceAtLeast(0.01f) * state.zoom
        val renderedWidth = contentWidthPx * scale
        val renderedHeight = contentHeightPx * scale
        val renderedOffsetX = clampOffset(state.offsetX, viewportWidthPx, renderedWidth)
        val renderedOffsetY = clampOffset(state.offsetY, viewportHeightPx, renderedHeight)
        val transformModifier = Modifier.graphicsLayer {
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = scale
            scaleY = scale
            translationX = renderedOffsetX
            translationY = renderedOffsetY
        }

        Box(
            Modifier.fillMaxSize()
                .pointerInput(
                    state,
                    viewportWidthPx,
                    viewportHeightPx,
                    contentWidthPx,
                    contentHeightPx,
                    baseScale,
                    minZoom,
                    maxZoom,
                    doubleTapZoom,
                    panAtBaseZoom,
                    excludedStartX,
                ) {
                    awaitEachGesture {
                        try {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            val excluded = down.position.x >= excludedStartX
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

                                val centroid = pointers.fold(Offset.Zero) { sum, pointer ->
                                    sum + pointer.position
                                } / pointers.size.toFloat()
                                val span = if (pointers.size > 1) {
                                    pointers.sumOf { (it.position - centroid).getDistance().toDouble() }
                                        .toFloat() / pointers.size
                                } else {
                                    0f
                                }
                                val ownsGesture = !excluded &&
                                    (pointers.size > 1 || state.zoom > 1f || panAtBaseZoom)
                                if (ownsGesture) {
                                    state.gestureActive = true
                                    velocityTracker.addPosition(
                                        event.changes.first().uptimeMillis,
                                        centroid,
                                    )
                                    event.changes.forEach { if (it.pressed) it.consume() }
                                    if (pointers.size == previousPointerCount) {
                                        val oldZoom = state.zoom
                                        val zoomChange = if (pointers.size > 1 && previousSpan > 0f) {
                                            span / previousSpan
                                        } else {
                                            1f
                                        }
                                        val nextZoom = (oldZoom * zoomChange).coerceIn(minZoom, maxZoom)
                                        val factor = nextZoom / oldZoom
                                        val panX = centroid.x - previousCentroid.x
                                        val panY = centroid.y - previousCentroid.y
                                        hadVerticalDrag = hadVerticalDrag || panY != 0f
                                        state.offsetX = clampOffset(
                                            state.offsetX * factor + previousCentroid.x * (1f - factor) + panX,
                                            viewportWidthPx,
                                            contentWidthPx * baseScale * nextZoom,
                                        )
                                        if (latestVerticalPan != null) {
                                            val verticalDelta =
                                                previousCentroid.y / (baseScale * oldZoom) -
                                                    centroid.y / (baseScale * nextZoom)
                                            if (verticalDelta != 0f) latestVerticalPan?.invoke(verticalDelta)
                                        } else {
                                            state.offsetY = clampOffset(
                                                state.offsetY * factor + previousCentroid.y * (1f - factor) + panY,
                                                viewportHeightPx,
                                                contentHeightPx * baseScale * nextZoom,
                                            )
                                        }
                                        state.zoom = nextZoom
                                    }
                                }
                                previousCentroid = centroid
                                previousSpan = span
                                previousPointerCount = pointers.size
                            }

                            if (!excluded && hadVerticalDrag && (state.zoom > 1f || panAtBaseZoom)) {
                                val initialVelocity =
                                    -velocityTracker.calculateVelocity().y / (baseScale * state.zoom)
                                latestVerticalFling?.invoke(initialVelocity)
                            }
                        } finally {
                            state.gestureActive = false
                        }
                    }
                }
                .pointerInput(
                    state,
                    viewportWidthPx,
                    viewportHeightPx,
                    contentWidthPx,
                    contentHeightPx,
                    baseScale,
                    excludedStartX,
                    minZoom,
                    maxZoom,
                    doubleTapZoom,
                    panAtBaseZoom,
                ) {
                    detectTapGestures(onDoubleTap = { tap ->
                        if (tap.x >= excludedStartX) return@detectTapGestures
                        val oldZoom = state.zoom
                        val nextZoom = if (oldZoom > 1f) {
                            1f
                        } else {
                            doubleTapZoom.coerceIn(minZoom, maxZoom)
                        }
                        val factor = nextZoom / oldZoom
                        state.offsetX = clampOffset(
                            tap.x + (state.offsetX - tap.x) * factor,
                            viewportWidthPx,
                            contentWidthPx * baseScale * nextZoom,
                        )
                        if (latestVerticalPan != null) {
                            val verticalDelta = tap.y / (baseScale * oldZoom) -
                                tap.y / (baseScale * nextZoom)
                            latestVerticalPan?.invoke(verticalDelta)
                        } else {
                            state.offsetY = clampOffset(
                                tap.y + (state.offsetY - tap.y) * factor,
                                viewportHeightPx,
                                contentHeightPx * baseScale * nextZoom,
                            )
                        }
                        state.zoom = nextZoom
                    })
                },
        ) {
            content(transformModifier)
        }
    }
}

private fun clampOffset(offset: Float, viewportSize: Float, contentSize: Float): Float {
    val lower = minOf(0f, viewportSize - contentSize)
    val upper = maxOf(0f, viewportSize - contentSize)
    return offset.coerceIn(lower, upper)
}
