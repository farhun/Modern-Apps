package com.vayunmathur.office.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding


import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

private const val MAX_RENDER_WIDTH = 2048
private const val MAX_RENDER_HEIGHT = 4096
private const val MAX_RENDER_PIXELS = 4_194_304L
private const val MAX_CACHED_PAGES = 4

internal data class RenderedPage(val bitmap: Bitmap, val aspectRatio: Float)

private data class PdfPageCacheKey(val pageIndex: Int, val targetWidth: Int)

internal class PdfPageBitmapCache(
    private val renderer: PdfRenderer,
    private val renderMutex: Mutex,
) : AutoCloseable {
    private data class Entry(val page: RenderedPage, var references: Int)

    private val lock = Any()
    private val entries = LinkedHashMap<PdfPageCacheKey, Entry>(8, 0.75f, true)
    private var closed = false

    suspend fun acquire(pageIndex: Int, targetWidth: Int): RenderedPage? {
        val key = PdfPageCacheKey(pageIndex, targetWidth.coerceAtLeast(1))
        synchronized(lock) {
            if (closed) return null
            entries[key]?.let { entry ->
                entry.references++
                return entry.page
            }
        }

        val rendered = renderMutex.withLock { renderPdfPage(renderer, pageIndex, key.targetWidth) } ?: return null
        return synchronized(lock) {
            if (closed) {
                rendered.bitmap.recycle()
                null
            } else {
                val existing = entries[key]
                if (existing != null) {
                    rendered.bitmap.recycle()
                    existing.references++
                    existing.page
                } else {
                    entries[key] = Entry(rendered, references = 1)
                    trimLocked()
                    rendered
                }
            }
        }
    }

    fun release(page: RenderedPage) {
        synchronized(lock) {
            val key = entries.entries.firstOrNull { it.value.page === page }?.key ?: return
            val entry = entries[key] ?: return
            entry.references = (entry.references - 1).coerceAtLeast(0)
            if (closed && entry.references == 0) {
                entries.remove(key)?.page?.bitmap?.recycle()
            } else {
                trimLocked()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.value.references == 0) {
                    iterator.remove()
                    entry.value.page.bitmap.recycle()
                }
            }
        }
    }

    private fun trimLocked() {
        if (entries.size <= MAX_CACHED_PAGES) return
        val iterator = entries.entries.iterator()
        while (entries.size > MAX_CACHED_PAGES && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.references == 0) {
                iterator.remove()
                entry.value.page.bitmap.recycle()
            }
        }
    }
}

@Composable
internal fun PdfPage(
    cache: PdfPageBitmapCache,
    pageIndex: Int,

    renderZoom: Float,
    matches: List<PdfTextMatch>,
    currentMatch: PdfTextMatch?,
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val targetWidth = with(density) { (maxWidth * renderZoom).roundToPx() }.coerceAtLeast(1)
        var rendered by remember(cache, pageIndex) { mutableStateOf<RenderedPage?>(null) }
        LaunchedEffect(cache, pageIndex, targetWidth) {
            val updated = withContext(Dispatchers.IO) { cache.acquire(pageIndex, targetWidth) }
            val previous = rendered
            rendered = updated
            previous?.let(cache::release)
        }
        DisposableEffect(cache, pageIndex) {
            onDispose { rendered?.let(cache::release) }
        }

        val pageHeight = rendered?.let { maxWidth / it.aspectRatio } ?: 480.dp
        Box(
            Modifier.fillMaxWidth()
                .height(pageHeight)
                .background(Color.White),
        ) {
            val page = rendered
            if (page == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Image(
                    bitmap = page.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
                if (matches.isNotEmpty()) {
                    Canvas(Modifier.fillMaxSize()) {
                        matches.forEach { match ->
                            val current = match == currentMatch
                            drawRect(
                                color = if (current) Color(0x88FF9800) else Color(0x66FFEB3B),
                                topLeft = Offset(match.left * size.width, match.top * size.height),
                                size = Size(
                                    (match.right - match.left).coerceAtLeast(0.003f) * size.width,
                                    (match.bottom - match.top).coerceAtLeast(0.003f) * size.height,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun renderPdfPage(
    renderer: PdfRenderer,
    pageIndex: Int,
    targetWidth: Int,
): RenderedPage? {
    if (pageIndex !in 0 until renderer.pageCount) return null
    return renderer.openPage(pageIndex).use { page ->
        val pixelScale = sqrt(MAX_RENDER_PIXELS.toDouble() / (page.width.toDouble() * page.height))
        val scale = minOf(
            MAX_RENDER_WIDTH.toFloat() / page.width,
            MAX_RENDER_HEIGHT.toFloat() / page.height,
            targetWidth.toFloat() / page.width,
            pixelScale.toFloat(),
        )
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        RenderedPage(bitmap, page.width.toFloat() / page.height.coerceAtLeast(1))
    }
}
