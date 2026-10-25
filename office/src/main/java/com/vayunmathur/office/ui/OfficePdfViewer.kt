package com.vayunmathur.office.ui

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember

import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconKeyboardArrowDown
import com.vayunmathur.library.ui.IconKeyboardArrowUp
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextField
import com.vayunmathur.library.ui.appBarScrollBehavior

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val MAX_RENDER_WIDTH = 2048
private const val MAX_RENDER_HEIGHT = 4096

private class PdfRendererHandle(
    private val descriptor: ParcelFileDescriptor,
    val renderer: PdfRenderer,
) : AutoCloseable {
    override fun close() {
        renderer.close()
        descriptor.close()
    }
}

private sealed interface PdfLoadState {
    data object Loading : PdfLoadState
    data class Ready(val handle: PdfRendererHandle) : PdfLoadState
    data object Error : PdfLoadState
}

private data class RenderedPage(val bitmap: Bitmap, val aspectRatio: Float)

@Composable
fun OfficePdfViewer(uri: Uri, title: String, onBack: () -> Unit) {
    val context = LocalContext.current

    val loadState by produceState<PdfLoadState>(PdfLoadState.Loading, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: error("Cannot open PDF")
                try {
                    PdfRendererHandle(descriptor, PdfRenderer(descriptor))
                } catch (error: Exception) {
                    descriptor.close()
                    throw error
                }
            }.fold({ PdfLoadState.Ready(it) }, { PdfLoadState.Error })
        }
    }
    val handle = (loadState as? PdfLoadState.Ready)?.handle
    DisposableEffect(handle) { onDispose { handle?.close() } }

    val listState = rememberLazyListState()
    val renderMutex = remember(handle) { Mutex() }
    var searching by remember(uri) { mutableStateOf(false) }
    var query by remember(uri) { mutableStateOf("") }
    var matches by remember(uri) { mutableStateOf<List<PdfTextMatch>>(emptyList()) }
    var matchIndex by remember(uri) { mutableIntStateOf(0) }
    var searchComplete by remember(uri) { mutableStateOf(false) }
    var searchFailed by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(query, handle) {
        if (query.isBlank() || handle == null) {
            matches = emptyList()
            searchComplete = false
            searchFailed = false
            return@LaunchedEffect
        }
        matches = emptyList()
        matchIndex = 0
        delay(300)
        searchComplete = false
        searchFailed = false
        matchIndex = 0
        try {
            matches = withContext(Dispatchers.IO) { searchPdf(context, uri, query) }
            searchComplete = true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            searchComplete = true
            searchFailed = true
        }
    }

    LaunchedEffect(matchIndex, matches) {
        matches.getOrNull(matchIndex)?.let { listState.animateScrollToItem(it.pageIndex) }
    }

    BackHandler {
        if (searching) {
            searching = false
            query = ""
            matches = emptyList()
        } else {
            onBack()
        }
    }

    AppScaffold(
        modifier = Modifier.fillMaxSize(),
        title = {
            if (searching) {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(io.github.farhun.office.R.string.pdf_search)) },
                        singleLine = true,
                    )
                    IconButton({
                        searching = false
                        query = ""
                        matches = emptyList()
                    }) { IconClose() }
                }
            } else {
                Text(title, maxLines = 1)
            }
        },
        navigationIcon = {
            IconNavigation {
                if (searching) {
                    searching = false
                    query = ""
                    matches = emptyList()
                } else {
                    onBack()
                }
            }
        },
        actions = {
            if (searching) {
                if (matches.isNotEmpty()) {
                    Text(
                        stringResource(io.github.farhun.office.R.string.pdf_match_count, matchIndex + 1, matches.size),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    IconButton({ matchIndex = (matchIndex - 1 + matches.size) % matches.size }) {
                        IconKeyboardArrowUp()
                    }
                    IconButton({ matchIndex = (matchIndex + 1) % matches.size }) {
                        IconKeyboardArrowDown()
                    }
                } else if (searchComplete && !searchFailed && query.isNotBlank()) {
                    Text(stringResource(io.github.farhun.office.R.string.pdf_no_matches))
                } else if (searchFailed) {
                    Text(stringResource(io.github.farhun.office.R.string.pdf_search_error))
                }
            } else {
                IconButton({ searching = true }) { IconSearch() }
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { innerPadding ->
        when {
            loadState is PdfLoadState.Loading -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            loadState is PdfLoadState.Error -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(io.github.farhun.office.R.string.pdf_open_error)) }

            handle != null -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                state = listState,
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(handle.renderer.pageCount) { pageIndex ->
                    PdfPage(
                        renderer = handle.renderer,
                        mutex = renderMutex,
                        pageIndex = pageIndex,
                        matches = matches.filter { it.pageIndex == pageIndex },
                        currentMatch = matches.getOrNull(matchIndex),
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPage(
    renderer: PdfRenderer,
    mutex: Mutex,
    pageIndex: Int,
    matches: List<PdfTextMatch>,
    currentMatch: PdfTextMatch?,
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val targetWidth = with(density) { maxWidth.roundToPx() }
        val rendered by produceState<RenderedPage?>(null, renderer, pageIndex, targetWidth) {
            value = withContext(Dispatchers.IO) {
                renderPdfPage(renderer, mutex, pageIndex, targetWidth)
            }
        }
        val page = rendered
        if (page == null) {
            Box(
                Modifier.fillMaxWidth().height(480.dp).background(Color.White),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        } else {
            Box(
                Modifier.fillMaxWidth()
                    .height(maxWidth / page.aspectRatio)
                    .background(Color.White),
            ) {
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

private suspend fun renderPdfPage(
    renderer: PdfRenderer,
    mutex: Mutex,
    pageIndex: Int,
    maxWidth: Int,
): RenderedPage? = mutex.withLock {
    if (pageIndex !in 0 until renderer.pageCount) return@withLock null
    renderer.openPage(pageIndex).use { page ->
        val scale = minOf(
            MAX_RENDER_WIDTH.toFloat() / page.width,
            MAX_RENDER_HEIGHT.toFloat() / page.height,
            maxWidth.coerceAtLeast(1).toFloat() / page.width,
        )
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        RenderedPage(bitmap, page.width.toFloat() / page.height.coerceAtLeast(1))
    }
}
